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
        long frameStamp = -1, frameNs;

        Stat(String file, int line, String event) { this.file = file; this.line = line; this.event = event; }
    }

    private static final IdentityHashMap<Prototype, Stat> stats = new IdentityHashMap<>();
    private static final Object[] fns = new Object[MAX_DEPTH];
    private static final long[] starts = new long[MAX_DEPTH];
    private static final Object[] events = new Object[MAX_DEPTH];
    private static int depth, eventDepth;
    private static long windowStartNs;
    private static long lastSeenFrameNo = -1, curFrame = 1, lastSlowPrintMs;

    private LuaTiming() {}

    static void init() {
        guard.on = Settings.flag("luaTiming");
        lastSeenFrameNo = FrameClock.frameNo;
        curFrame = 1;
    }

    private static boolean mainThread() { return Thread.currentThread() == FrameClock.mainThread; }

    static void eventEnter(Object event) {
        if (!mainThread()) return;
        noteFrame();
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
        noteFrame();
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
        long frame = curFrame;
        if (s.frameStamp != frame) {
            s.frameStamp = frame;
            s.frameNs = 0;
        }
        s.frameNs += ns;
    }

    /** A new frame: the first Lua activity of a frame names the previous frame's top functions when it was slow. */
    private static void noteFrame() {
        long f = FrameClock.frameNo;
        if (f == lastSeenFrameNo) return;
        lastSeenFrameNo = f;
        curFrame++;
        slowFrame(curFrame - 1, FrameClock.intervalMs, System.currentTimeMillis());
    }

    /** At most one slow-frame line a second, unless the frame was over 250 ms. */
    static boolean allowSlowPrint(double ms, long nowMs) {
        if (lastSlowPrintMs != 0 && nowMs - lastSlowPrintMs < 1000 && ms <= 250) return false;
        lastSlowPrintMs = nowMs;
        return true;
    }

    /** The frame's five slowest handlers; the minute totals are untouched. */
    private static void slowFrame(long frame, double ms, long nowMs) {
        if (ms <= 50 || !allowSlowPrint(ms, nowMs)) return;
        List<Stat> top = new ArrayList<>();
        for (Stat s : stats.values()) if (s.frameStamp == frame && s.frameNs > 0) top.add(s);
        if (top.isEmpty()) return;
        top.sort((a, b) -> Long.compare(b.frameNs, a.frameNs));
        StringBuilder sb = new StringBuilder("[PZButter] lua slow frame ").append(Math.round(ms)).append(" ms:");
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            Stat s = top.get(i);
            sb.append(String.format(Locale.ROOT, " %s:%d %.1f ms;", s.file, s.line, s.frameNs / 1e6));
        }
        System.out.println(sb);
    }

    /** The running Lua handler (deepest non-skipped call), as file:line; "-" outside Lua. */
    static String currentFunction() {
        for (int i = Math.min(depth, MAX_DEPTH) - 1; i >= 0; i--) {
            Object fn = fns[i];
            if (fn instanceof LuaClosure c && c.prototype != null) {
                Prototype p = c.prototype;
                String file = p.filename != null ? p.filename : p.file != null ? p.file : "?";
                return file + ":" + (p.lines != null && p.lines.length > 0 ? p.lines[0] : 0);
            }
        }
        return "-";
    }

    static void resetSlowPrintForTest() { lastSlowPrintMs = 0; }

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
