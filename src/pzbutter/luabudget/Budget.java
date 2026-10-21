package pzbutter.luabudget;

import pzbutter.Main;
import pzbutter.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

/**
 * Frame-time pressure plus the skip rules. Everything except the config load is touched from the
 * main thread only; the hooks ignore other threads.
 *
 * Pressure is the main thread's CPU time per frame, so vsync, fps caps and GPU waits don't count.
 * A rule is "over" when that time is above 1000/fps ms, where fps is the rule's own or targetFps.
 */
public final class Budget {
    static final class Rule {
        final String needle;       // lower-case, '/' separated part of the Lua file path
        final int every;           // run a handler (or entity) once every N frames while over
        final String[] events;
        final double budgetMs;
        boolean over;
        long flippedNs;
        long eligible, skipped;
        Rule(String needle, int every, String[] events, double fps) {
            this.needle = needle; this.every = every; this.events = events; this.budgetMs = 1000.0 / fps;
        }
        boolean allows(String ev) { return ev != null && Arrays.asList(events).contains(ev); }
    }

    /** Per handler closure. Holds no reference to the closure, so the weak key can be collected. */
    static final class State {
        final Rule rule;                                  // null = handler isn't covered by any rule
        long lastFrame;
        final WeakHashMap<Object, long[]> entities = new WeakHashMap<>(); // per zombie/player
        State(Rule rule, long frame) { this.rule = rule; this.lastFrame = frame - (rule == null ? 0 : rule.every); }
    }

    // Per-frame events whose first argument is the entity being updated.
    private static final Set<String> ENTITY_EVENTS = new HashSet<>(Arrays.asList("OnZombieUpdate", "OnPlayerUpdate"));
    // Only these may be skipped. One-shot events (OnGameStart, OnSave, OnCreatePlayer...) must always run.
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
        "OnTick", "OnTickEvenPaused", "OnPlayerUpdate", "OnZombieUpdate"));

    private static final String DEFAULTS =
        "# Lua Budget. Takes effect at game start.\n"
      + "#\n"
      + "# targetFps: the frame rate you want to keep. A rule starts skipping when the main thread's CPU work\n"
      + "# per frame is above 1000/fps ms (40 fps = 25 ms) and stops again at 85% of that, after at least 2 s.\n"
      + "# It is the work the main thread does, so vsync, an fps cap or a slow GPU don't trigger it.\n"
      + "targetFps=40\n"
      + "#\n"
      + "# rule.<n>=<part of the Lua file path>|<run once every N frames>|<events>[|<fps for this rule>]\n"
      + "#   events: comma separated, only OnTick, OnTickEvenPaused, OnPlayerUpdate, OnZombieUpdate.\n"
      + "#   fps: optional, overrides targetFps for this rule (10-240).\n"
      + "# Per-zombie/per-player events are skipped per entity, so each one still runs every Nth frame.\n"
      + "# Only skip handlers that refresh something. One that adds up a value per call (boredom, traits,\n"
      + "# timers) changes how fast that value moves. No rules means the mod does nothing.\n"
      + "#\n"
      + "# rule.1=wkt_pointer.lua|3|OnTick\n"
      + "# rule.2=ViewpointExtendedSupport.lua|2|OnTick|30\n";

    private static volatile boolean enabled;
    /** The old LuaBudget mod's class; two copies would compound their skips. */
    static String oldMod = "luabudget.Budget";
    /** ZombieBuddy may add the old mod's jar after init; Lua events only start once every mod's Java is in. */
    private static volatile boolean oldModChecked;
    private static volatile Thread mainThread;
    private static List<Rule> rules = new ArrayList<>();
    private static final WeakHashMap<Object, State> states = new WeakHashMap<>();
    private static final Object[] eventStack = new Object[16];
    private static int depth;
    private static Field nameField;
    private static long frameNo, frames, overFrames, lastReportNs, startCpu;
    private static double emaMs;
    private static int failures;
    private static ThreadMXBean cpuBean;

    private Budget() {}

    static boolean isEnabled() { return enabled; }

    /** True (and luaBudget off, with one log line) when the old LuaBudget mod is on the class path. */
    private static boolean oldModPresent() {
        if (!Main.classPresent(oldMod)) return false;
        enabled = false;
        System.out.println("[PZButter] luaBudget off: the LuaBudget mod is also loaded; remove it from the mod list");
        return true;
    }

    public static void init() { init(new File(System.getProperty("user.home"), "Zomboid/LuaBudget.properties")); }

    static void init(File f) {
        enabled = false;
        if (!Settings.flag("luaBudget")) return;
        oldModChecked = false;
        if (oldModPresent()) return;
        try {
            if (!f.exists()) try (FileWriter w = new FileWriter(f)) { w.write(DEFAULTS); }
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
            double fps = fps(p.getProperty("targetFps", "40"), 40);
            List<Rule> loaded = new ArrayList<>();
            for (String k : new TreeSet<>(p.stringPropertyNames())) {
                if (!k.startsWith("rule.")) continue;
                try { loaded.add(parse(p.getProperty(k), fps)); }
                catch (Exception e) { System.out.println("[PZButter] luaBudget: ignoring " + k + ": " + e.getMessage()); }
            }
            try {
                ThreadMXBean b = ManagementFactory.getThreadMXBean();
                if (b.isCurrentThreadCpuTimeSupported()) cpuBean = b;
            } catch (Throwable t) { cpuBean = null; }
            rules = loaded;
            enabled = !rules.isEmpty();
            System.out.println("[PZButter] luaBudget: target " + fps + " fps, " + rules.size() + " rule(s)"
                + (enabled ? "" : ", idle") + (cpuBean == null ? ", no CPU timer, using wall time" : ""));
        } catch (Throwable t) {
            enabled = false;
            System.out.println("[PZButter] luaBudget: config failed, disabled: " + t);
        }
    }

    private static double fps(String s, double dflt) {
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isNaN(v) ? dflt : Math.max(10, Math.min(240, v));
        } catch (NumberFormatException e) { return dflt; }
    }

    static Rule parse(String line, double defaultFps) {
        String[] parts = line.split("\\|");
        if (parts.length < 3) throw new IllegalArgumentException("needs path|every|events");
        String needle = parts[0].trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) throw new IllegalArgumentException("empty path");
        int every = Integer.parseInt(parts[1].trim());
        if (every < 2 || every > 60) throw new IllegalArgumentException("every must be 2-60");
        String[] events = Arrays.stream(parts[2].split(",")).map(String::trim).toArray(String[]::new);
        for (String e : events) if (!ALLOWED.contains(e)) throw new IllegalArgumentException("event " + e + " can't be skipped");
        double fps = parts.length > 3 ? fps(parts[3], defaultFps) : defaultFps;
        return new Rule(needle, every, events, fps);
    }

    private static long cpuNow() {
        try { return cpuBean != null ? cpuBean.getCurrentThreadCpuTime() : System.nanoTime(); }
        catch (Throwable t) { return System.nanoTime(); }
    }

    public static void frameStart() {
        if (!enabled) return;
        try {
            mainThread = Thread.currentThread();
            frameNo++;
            startCpu = cpuNow();
        } catch (Throwable t) { fail(t); }
    }

    public static void frameEnd() {
        if (!enabled || Thread.currentThread() != mainThread || startCpu == 0) return;
        try {
            long now = System.nanoTime();
            double ms = Math.max(0, Math.min(250, (cpuNow() - startCpu) / 1e6)); // one long hitch must not pin us over
            startCpu = 0;
            emaMs = emaMs == 0 ? ms : emaMs * 0.9 + ms * 0.1;
            boolean any = false;
            for (Rule r : rules) {
                boolean want = r.over ? emaMs > r.budgetMs * 0.85 : emaMs > r.budgetMs;
                if (want != r.over && now - r.flippedNs > 2_000_000_000L) { r.over = want; r.flippedNs = now; }
                any |= r.over;
            }
            frames++;
            if (any) overFrames++;
            if (now - lastReportNs > 60_000_000_000L) report(now);
        } catch (Throwable t) { fail(t); }
    }

    public static void eventEnter(Object event) {
        if (Thread.currentThread() != mainThread) return;
        if (depth < eventStack.length) eventStack[depth] = event;
        depth++;
    }

    public static void eventExit() {
        if (Thread.currentThread() != mainThread) return;
        if (depth > 0) { depth--; if (depth < eventStack.length) eventStack[depth] = null; }
    }

    /** True when this Lua call should be skipped. */
    public static boolean shouldSkip(Object fn, Object[] args) {
        if (!enabled) return false;
        if (!oldModChecked) { oldModChecked = true; if (oldModPresent()) return false; }
        if (depth == 0 || Thread.currentThread() != mainThread) return false;
        try {
            State s = states.get(fn);
            if (s == null) { s = new State(lookup(fn), frameNo); states.put(fn, s); }
            Rule r = s.rule;
            if (r == null || !r.over) return false;
            String ev = eventName();
            if (!r.allows(ev)) return false;
            r.eligible++;
            Object entity = ENTITY_EVENTS.contains(ev) && args != null && args.length > 0 ? args[0] : null;
            long[] last = null;
            if (entity != null) { last = s.entities.get(entity); if (last == null) { last = new long[] { frameNo - r.every }; s.entities.put(entity, last); } }
            long prev = last != null ? last[0] : s.lastFrame;
            if (frameNo - prev >= r.every) {
                if (last != null) last[0] = frameNo; else s.lastFrame = frameNo;
                return false;
            }
            r.skipped++;
            return true;
        } catch (Throwable t) { fail(t); return false; }
    }

    // The game's classes are newer than the JDK this was built with, so they are read by reflection.
    // Closures are looked up once and cached; the event name only when a rule already matched.
    private static Rule lookup(Object fn) throws ReflectiveOperationException {
        if (!fn.getClass().getName().equals("se.krka.kahlua.vm.LuaClosure")) return null;
        Object p = fn.getClass().getField("prototype").get(fn);
        if (p == null) return null;
        String path = (str(p, "filename") + "|" + str(p, "file")).replace('\\', '/').toLowerCase(Locale.ROOT);
        for (Rule r : rules) if (path.contains(r.needle)) return r;
        return null;
    }

    private static String str(Object o, String field) throws ReflectiveOperationException {
        Object v = o.getClass().getField(field).get(o);
        return v == null ? "" : v.toString();
    }

    private static String eventName() throws ReflectiveOperationException {
        Object ev = eventStack[Math.min(depth, eventStack.length) - 1];
        if (ev == null) return null;
        if (nameField == null) nameField = ev.getClass().getField("name");
        return String.valueOf(nameField.get(ev));
    }

    private static void report(long now) {
        lastReportNs = now;
        StringBuilder sb = new StringBuilder("[PZButter] luaBudget: last 60s: skipping active in " + overFrames + "/" + frames
            + " frames, main-thread work " + String.format(Locale.ROOT, "%.1f", emaMs) + " ms.");
        for (Rule r : rules) {
            sb.append(" ").append(r.needle).append(": skipped ").append(r.skipped).append("/").append(r.eligible).append(";");
            r.eligible = r.skipped = 0;
        }
        System.out.println(sb);
        frames = overFrames = 0;
    }

    private static void fail(Throwable t) {
        if (++failures >= 5) { enabled = false; System.out.println("[PZButter] luaBudget: disabled after errors: " + t); }
    }
}
