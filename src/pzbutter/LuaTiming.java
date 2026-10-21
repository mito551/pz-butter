package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import zombie.Lua.Event;

/**
 * luaTiming (Smooth Frames 3.8): time spent in each Lua handler called through LuaCaller.protectedCallVoid on the
 * main thread, inclusive of what it calls, grouped by the handler's function (file:line) and the event it ran for.
 * Once a minute the ten slowest go to the console and, with frameLog, to Zomboid/pzbutter/lua.out.
 */
final class LuaTiming {
    static final Guard guard = new Guard("luaTiming");
    private static final int MAX_DEPTH = 64;
    private static final long WINDOW_NS = 60_000_000_000L;

    static final class Stat {
        final String file;
        final int line;
        final String event;
        long ns, calls;

        Stat(String file, int line, String event) { this.file = file; this.line = line; this.event = event; }
    }

    private static final IdentityHashMap<Prototype, Stat> stats = new IdentityHashMap<>();
    private static final Object[] fns = new Object[MAX_DEPTH];
    private static final long[] starts = new long[MAX_DEPTH];
    private static final Object[] events = new Object[MAX_DEPTH];
    private static int depth, eventDepth;
    private static long windowStartNs;

    private LuaTiming() {}

    static void init() { guard.on = Settings.flag("luaTiming"); }

    private static boolean mainThread() { return Thread.currentThread() == FrameClock.mainThread; }

    static void eventEnter(Object event) {
        if (!mainThread()) return;
        if (eventDepth < MAX_DEPTH) events[eventDepth] = event;
        eventDepth++;
    }

    static void eventExit() {
        if (!mainThread() || eventDepth == 0) return;
        eventDepth--;
        if (eventDepth < MAX_DEPTH) events[eventDepth] = null;
    }

    /** A call starts; a skipped call is pushed too (without timing), so every exit pops one. */
    static void callEnter(Object fn, boolean skipped) {
        if (!mainThread()) return;
        if (depth < MAX_DEPTH) { fns[depth] = skipped ? null : fn; starts[depth] = System.nanoTime(); }
        depth++;
    }

    static void callExit() {
        if (!mainThread() || depth == 0) return;
        depth--;
        if (depth >= MAX_DEPTH) return;
        Object fn = fns[depth];
        fns[depth] = null;
        long now = System.nanoTime();
        if (fn instanceof LuaClosure c && c.prototype != null) record(c.prototype, now - starts[depth]);
        if (windowStartNs == 0) windowStartNs = now;
        else if (now - windowStartNs >= WINDOW_NS && depth == 0) report(now);
    }

    private static void record(Prototype p, long ns) {
        Stat s = stats.get(p);
        if (s == null) {
            Object ev = eventDepth > 0 && eventDepth <= MAX_DEPTH ? events[eventDepth - 1] : null;
            String name = ev instanceof Event e ? e.name : "-";
            String file = p.filename != null ? p.filename : p.file != null ? p.file : "?";
            stats.put(p, s = new Stat(file, p.lines != null && p.lines.length > 0 ? p.lines[0] : 0, name));
        }
        s.ns += ns;
        s.calls++;
    }

    private static void report(long now) {
        List<String> lines = reportLines(stats.values(), now - windowStartNs);
        for (String l : lines) System.out.println(l);
        if (Settings.frameLog) {
            try (FileWriter w = new FileWriter(new File(System.getProperty("user.home"), "Zomboid/pzbutter/lua.out"), true)) {
                w.write("# " + System.currentTimeMillis() + "\n");
                for (String l : lines) w.write(l + "\n");
            } catch (Throwable t) { guard.fail(t); }
        }
        stats.clear();
        windowStartNs = now;
    }

    /** The ten handlers with the most time, slowest first. */
    static List<String> reportLines(Collection<Stat> all, long elapsedNs) {
        List<Stat> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Long.compare(b.ns, a.ns));
        List<String> out = new ArrayList<>();
        double seconds = Math.max(1e-9, elapsedNs / 1e9);
        for (int i = 0; i < Math.min(10, sorted.size()); i++) {
            Stat s = sorted.get(i);
            out.add(String.format(Locale.ROOT, "[PZButter] lua: %7.3f ms/s  %-16s %6d calls  %s:%d",
                s.ns / 1e6 / seconds, s.event, s.calls, s.file, s.line));
        }
        return out;
    }
}
