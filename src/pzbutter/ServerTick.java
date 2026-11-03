package pzbutter;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Server tick log (Plan 18). One line per server tick in the frames.out format
 * (epoch_ms, tick number, ms since the previous tick start, ms in the tick, main-thread CPU ms),
 * so tools/frames.py reads it unchanged. A minute line gives n/p50/p99/max. Ticks over 100 ms
 * carry the stage that took most of the tick: net (before ServerMap.preupdate), a Lua event,
 * or world (the rest).
 *
 * <p>Written to Zomboid/pzbutter/ticks.out in the same place FrameClock writes frames.out.
 * Closed on GameServer.startServer exit, GameServer.close and a JVM shutdown hook (Ctrl-C).
 */
public final class ServerTick {
    static final Guard guard = new Guard("serverTickLog");
    static final Guard luaGuard = new Guard("serverLuaSample");

    public static volatile boolean on;
    static volatile long tickNo, tickStartNs;   // read by HitchSampler's server mode

    static LogSink log;   // nullable; tests open it directly
    static long logLimit = 50L << 20;

    private static final ThreadMXBean MX = mx();
    private static final boolean CPU = MX != null && MX.isCurrentThreadCpuTimeSupported();

    private static long prevStartNs, netNs = -1, luaNs, luaStartNs, cpuStartNs;
    private static int luaDepth, failures;
    private static boolean running;   // between begin() and end(), on the server thread

    private static final int WINDOW = 4096;
    private static final long[] window = new long[WINDOW];
    private static int windowN;
    private static long windowStartMs;

    private ServerTick() {}

    static void init() {
        try {
            on = Settings.flag("serverTickLog");
            if (on) {
                openLog(new File(System.getProperty("user.home"), "Zomboid/pzbutter/ticks.out"));
                try {
                    Runtime.getRuntime().addShutdownHook(new Thread(ServerTick::stop, "PZButter tick flush"));
                } catch (Throwable t) { fail(t); }   // already shutting down: stop() still runs on exit
            }
            if (Settings.flag("serverLuaSample")) {
                luaGuard.on = true;
                HitchSampler.initServer(Thread.currentThread());
            }
        } catch (Throwable t) { fail(t); }
    }

    /** Tick start: PoolCaps.updateServerCaps, the first server call after the loop's nanoTime. */
    public static void begin() { beginAt(System.nanoTime()); }

    static void beginAt(long nowNs) { beginAt(nowNs, cpu()); }

    static void beginAt(long nowNs, long cpuNs) {
        if (!on && !luaGuard.on) return;
        try {
            running = true;
            tickNo++;
            tickStartNs = nowNs;
            netNs = -1;
            luaNs = 0;
            luaDepth = 0;
            cpuStartNs = cpuNs;
            prevStartNs = prevStartNs == 0 ? nowNs : prevStartNs;   // first tick: interval 0 until the next begin
        } catch (Throwable t) { fail(t); }
    }

    /** ServerMap.preupdate entry: network work (and the rest before the world update) is over. */
    public static void netDone() { netDoneAt(System.nanoTime()); }

    static void netDoneAt(long nowNs) {
        if ((!on && !luaGuard.on) || !running) return;
        try { netNs = Math.max(0, nowNs - tickStartNs); } catch (Throwable t) { fail(t); }
    }

    public static void luaEnter() {
        if ((!on && !luaGuard.on) || !running) return;
        try { if (luaDepth++ == 0) luaStartNs = System.nanoTime(); } catch (Throwable t) { fail(t); }
    }

    public static void luaExit() {
        if ((!on && !luaGuard.on) || !running) return;
        try {
            if (luaDepth <= 0) return;
            if (--luaDepth == 0) luaNs += Math.max(0, System.nanoTime() - luaStartNs);
        } catch (Throwable t) { fail(t); }
    }

    /** Tick end: WorldMapVisitedServer.update exit, just before the game's own frame probe closes. */
    public static void end() { endAt(System.nanoTime(), CPU ? cpu() : 0); }

    static void endAt(long nowNs, long nowCpuNs) {
        if (!on && !luaGuard.on) return;
        try {
            if (!running) return;
            running = false;
            long tickNs = Math.max(0, nowNs - tickStartNs);
            long intervalNs = prevStartNs == 0 ? tickNs : Math.max(0, tickStartNs - prevStartNs);
            long cpuNs = CPU ? Math.max(0, nowCpuNs - cpuStartNs) : tickNs;
            prevStartNs = tickStartNs;

            long net = netNs < 0 ? 0 : netNs;
            long lua = Math.min(luaNs, Math.max(0, tickNs - net));
            long world = Math.max(0, tickNs - net - lua);

            LogSink s = log;
            if (s != null) {
                long epoch = System.currentTimeMillis();
                s.begin(epoch).num(tickNo).ms(intervalNs).ms(tickNs).ms(cpuNs);
                if (tickNs >= 100_000_000L) {
                    if (net >= world && net >= lua) s.text("stage=net").ms(net);
                    else if (lua >= world) s.text("stage=lua").ms(lua);
                    else s.text("stage=world").ms(world);
                }
                s.end(epoch);
            }
            if (on) minute(tickNs);        } catch (Throwable t) { fail(t); }
    }

    /** One line a minute: ticks seen, median, 99th percentile and worst, all in whole ms. */
    private static void minute(long tickNs) {
        long now = System.currentTimeMillis();
        if (windowStartMs == 0) windowStartMs = now;
        if (windowN < WINDOW) window[windowN++] = Math.round(tickNs / 1e6);
        if (now - windowStartMs < 60_000) return;
        if (windowN > 0) {
            long[] a = java.util.Arrays.copyOf(window, windowN);
            java.util.Arrays.sort(a);
            long p50 = a[(a.length - 1) * 50 / 100];
            long p99 = a[(a.length - 1) * 99 / 100];
            System.out.println("serverTick: " + a.length + " " + p50 + " " + p99 + " " + a[a.length - 1]);
        }
        windowN = 0;
        windowStartMs = now;
    }

    /** Flush and close; safe to call twice and from a shutdown hook. */
    public static void stop() {
        LogSink s = log;
        log = null;
        on = false;
        luaGuard.on = false;
        running = false;
        if (s == null) return;
        try { s.close(); } catch (Throwable t) { fail(t); }
    }

    static void flush() {
        LogSink s = log;
        if (s == null) return;
        try { s.flushBuf(); } catch (Throwable t) { fail(t); }
    }

    /** Test seam: forget every tick statistic and close nothing (the caller closes). */
    static void resetForTest() {
        tickNo = 0;
        tickStartNs = 0;
        prevStartNs = 0;
        netNs = -1;
        luaNs = 0;
        luaDepth = 0;
        running = false;
        windowN = 0;
        windowStartMs = 0;
    }

    /** Test seam: open a log at a chosen path (LogSink form) and switch the writer on. */
    static void openLog(File f) {
        try {
            LogSink old = log;
            log = new LogSink(f, logLimit);
            if (old != null) try { old.close(); } catch (Throwable ignored) {}
            on = true;
            failures = 0;
        } catch (Throwable t) {
            log = null;
            on = false;
            fail(t);
        }
    }

    private static long cpu() {
        try { return CPU ? MX.getCurrentThreadCpuTime() : 0; } catch (Throwable t) { return 0; }
    }

    private static ThreadMXBean mx() {
        try {
            ThreadMXBean m = ManagementFactory.getThreadMXBean();
            if (m != null && m.isCurrentThreadCpuTimeSupported() && !m.isThreadCpuTimeEnabled()) m.setThreadCpuTimeEnabled(true);
            return m;
        } catch (Throwable t) { return null; }
    }

    private static void fail(Throwable t) {
        if (++failures >= 5) { on = false; log = null; }
        guard.fail(t);
    }
}
