package etw.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.scripting.objects.CharacterTrait;

/**
 * Single per-tick dispatcher for ETW. Runs right after the game's OnTick event and calls the
 * registered Lua handlers only when their Java-side gate says there is work, so idle players cost
 * no Lua calls at all.
 *
 * Gates:
 *   always      fn()            every tick while registered (Lua adds/removes it as work starts/stops)
 *   players     fn(player)      each online player that has any listed trait or active ETW tick state
 *   local       fn(player)      the local player when it has any listed trait
 *   sleepMoodle fn(player)      the local player when its SleepHealthMoodle backing data is incomplete
 */
public final class Tick {
    private Tick() {}

    private static final int ALWAYS = 0, PLAYERS = 1, LOCAL = 2, SLEEP_MOODLE = 3;
    private static final Object[] NO_ARGS = new Object[0];

    private static final class Handler {
        final Object fn;
        final int gate;
        final CharacterTrait[] traits;
        Handler(Object fn, int gate, CharacterTrait[] traits) { this.fn = fn; this.gate = gate; this.traits = traits; }
    }

    private static final LinkedHashMap<String, Handler> byId = new LinkedHashMap<>();
    // Snapshot rebuilt on every change, so handlers may register/unregister while the tick runs.
    private static Handler[] handlers = new Handler[0];
    // Lua state the handlers belong to; a reload (new save, MP connect) makes them stale.
    private static Object env;
    private static boolean warned;

    @LuaMethod(name = "etwPerfTickRegister", global = true)
    public static void register(String id, Object fn, String gate, Object traits) {
        if (env != LuaManager.env) { byId.clear(); env = LuaManager.env; }
        byId.remove(id);
        byId.put(id, new Handler(fn, parseGate(gate), toTraits(traits)));
        handlers = byId.values().toArray(new Handler[0]);
    }

    @LuaMethod(name = "etwPerfTickUnregister", global = true)
    public static void unregister(String id) {
        if (byId.remove(id) != null) handlers = byId.values().toArray(new Handler[0]);
    }

    @Patch(className = "zombie.gameStates.IngameState", methodName = "onTick")
    public static class OnTickPatch {
        @Patch.OnExit
        public static void exit() { Tick.run(); }
    }

    public static void run() {
        Handler[] hs = handlers;
        if (hs.length == 0) return;
        if (env != LuaManager.env) { byId.clear(); handlers = new Handler[0]; return; }
        ArrayList<IsoPlayer> online = null;
        IsoPlayer local = null;
        boolean localResolved = false;
        for (Handler h : hs) {
            try {
                switch (h.gate) {
                    case ALWAYS -> call(h.fn, null);
                    case PLAYERS -> {
                        if (online == null) online = players();
                        for (int i = 0; i < online.size(); i++) {
                            IsoPlayer p = online.get(i);
                            if (p != null && (hasAny(p, h.traits) || etwStateActive(p.getModData()))) call(h.fn, p);
                        }
                    }
                    case LOCAL, SLEEP_MOODLE -> {
                        if (!localResolved) { local = IsoPlayer.getInstance(); localResolved = true; }
                        if (local == null) break;
                        boolean due = h.gate == LOCAL ? hasAny(local, h.traits) : sleepMoodleIncomplete(local.getModData());
                        if (due) call(h.fn, local);
                    }
                    default -> {}
                }
            } catch (Throwable t) {
                if (!warned) { warned = true; System.out.println("[ETW_Perf] tick handler failed: " + t); }
            }
        }
    }

    private static void call(Object fn, Object arg) {
        if (arg == null) LuaManager.caller.protectedCallVoid(LuaManager.thread, fn, NO_ARGS);
        else LuaManager.caller.protectedCallVoid(LuaManager.thread, fn, arg);
    }

    /** Same list as ETW_CommonFunctions.playersList(): online players, or the local player in SP. */
    private static ArrayList<IsoPlayer> players() {
        ArrayList<IsoPlayer> list = LuaManager.GlobalObject.getOnlinePlayers();
        if (!list.isEmpty()) return list;
        IsoPlayer p = IsoPlayer.getInstance();
        ArrayList<IsoPlayer> one = new ArrayList<>(1);
        if (p != null) one.add(p);
        return one;
    }

    private static boolean hasAny(IsoPlayer p, CharacterTrait[] traits) {
        for (CharacterTrait t : traits) if (p.hasTrait(t)) return true;
        return false;
    }

    // ponytail: reads the player's current ETW table, not the MP-server reference that
    // getETWModData() restores after a client sync replaced it; any trait-gated call or the
    // minute update repairs it, upgrade by passing the reference table from Lua if that matters.
    /** Mirrors the trait-independent work in everyTickUpdate: starting-injury snapshots and Indefatigable protection. */
    static boolean etwStateActive(KahluaTable modData) {
        KahluaTable etw = table(modData, "EvolvingTraitsWorld");
        if (etw == null) return false;
        if (etw.rawget("IndefatigableProtectionExpiresAt") instanceof Double d && d > 0) return true;
        KahluaTable snaps = table(etw, "InjurySnapshotSystem");
        return snaps != null && (nonEmpty(snaps, "InjuredBodyParts") || nonEmpty(snaps, "BurnedBodyParts") || nonEmpty(snaps, "BrokenBodyParts"));
    }

    /** Mirrors ETW_Moodles.ensureMoodleModData(): true when any backing field is missing. */
    static boolean sleepMoodleIncomplete(KahluaTable modData) {
        KahluaTable m = table(table(modData, "Moodles"), "SleepHealthMoodle");
        return m == null || m.rawget("Level") == null || m.rawget("GoodBadNeutral") == null || m.rawget("Value") == null;
    }

    private static KahluaTable table(KahluaTable t, String key) {
        return t != null && t.rawget(key) instanceof KahluaTable v ? v : null;
    }

    private static boolean nonEmpty(KahluaTable t, String key) {
        KahluaTable v = table(t, key);
        return v != null && !v.isEmpty();
    }

    private static int parseGate(String gate) {
        if (gate == null) return ALWAYS;
        return switch (gate) {
            case "players" -> PLAYERS;
            case "local" -> LOCAL;
            case "sleepMoodle" -> SLEEP_MOODLE;
            default -> ALWAYS;
        };
    }

    private static CharacterTrait[] toTraits(Object traits) {
        ArrayList<CharacterTrait> out = new ArrayList<>();
        if (traits instanceof KahluaTable t) {
            for (int i = 1; i <= t.len(); i++) if (t.rawget(i) instanceof CharacterTrait c) out.add(c);
        }
        return out.toArray(new CharacterTrait[0]);
    }
}
