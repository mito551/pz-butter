package cpd.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;

/**
 * Doors the game toggled since Lua last asked, with the state each had before its first toggle
 * of the tick. Lua reads the state after the toggle itself, so a door toggled twice in one tick
 * ends up with prior == current and no transition, exactly as a poll would have seen.
 */
public final class Toggles {
    private static final int CAP = 256;
    private static final Map<Object, Boolean> PENDING = new LinkedHashMap<>();

    private Toggles() {}

    static Boolean stateOf(Object door) {
        if (door instanceof IsoDoor d) return d.IsOpen();
        if (door instanceof IsoThumpable t) return t.IsOpen();
        return null;
    }

    /** Called before the game flips the door. */
    public static void record(Object door) {
        if (door == null) return;
        Boolean was = stateOf(door);
        synchronized (PENDING) {
            if (PENDING.size() < CAP) PENDING.putIfAbsent(door, was);
        }
    }

    /**
     * nil when nothing was toggled, so the per-tick call allocates nothing in the common case.
     * Otherwise an array of doors with a parallel table {@code priors} (index -> open before).
     */
    @LuaMethod(name = "cpdDrainDoorToggles", global = true)
    public static KahluaTable drain() {
        ArrayList<Map.Entry<Object, Boolean>> taken;
        synchronized (PENDING) {
            if (PENDING.isEmpty()) return null;
            taken = new ArrayList<>(PENDING.entrySet());
            PENDING.clear();
        }
        KahluaTable out = LuaManager.platform.newTable();
        KahluaTable priors = LuaManager.platform.newTable();
        for (int i = 0; i < taken.size(); i++) {
            Map.Entry<Object, Boolean> e = taken.get(i);
            out.rawset(i + 1, e.getKey());
            if (e.getValue() != null) priors.rawset(i + 1, e.getValue());
        }
        out.rawset("priors", priors);
        return out;
    }
}
