package pzbutter;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Frame clock: times each GameWindow.frameStep. frameStep runs once per frame; the frame limiter's wait is
 * outside it, so the interval (start to start) is the frame time and the step is the work. The optional log
 * goes through a LogSink. Only the first thread that calls enter() is measured.
 */
public final class FrameClock {
    public static volatile boolean on;
    public static volatile Thread mainThread;
    public static long frameNo;
    public static long frameStartNs;   // nanoTime at the start of the current (or last) frame
    public static double intervalMs, stepMs, cpuMs;

    static long logLimit = 50L << 20;
    static LogSink log;   // nullable; close() declares IOException

    private static final ThreadMXBean MX = mx();
    private static final boolean CPU = MX != null && MX.isCurrentThreadCpuTimeSupported() && MX.isThreadCpuTimeEnabled();

    private static long startNs, startCpu, prevStartNs;
    private static boolean armed, havePrev;
    private static int failures;

    private FrameClock() {}

    private static ThreadMXBean mx() {
        try { return ManagementFactory.getThreadMXBean(); } catch (Throwable t) { return null; }
    }

    static void init() {
        try {
            on = Settings.enabled;
            if (on && Settings.frameLog) openLog(new File(System.getProperty("user.home"), "Zomboid/pzbutter/frames.out"));
        } catch (Throwable t) { fail(t); }
    }

    public static void enter() {
        if (Cores.guard.on) Cores.pinOnce();
        if (!on) return;
        try {
            Thread t = Thread.currentThread();
            Thread m = mainThread;
            if (m == null) mainThread = m = t;
            if (m != t) return;
            armed = true;
            frameStartNs = startNs = System.nanoTime();
            if (CPU) startCpu = MX.getCurrentThreadCpuTime();
        } catch (Throwable t) { fail(t); }
    }

    public static void exit() {
        if (!on) return;
        try {
            if (!armed || Thread.currentThread() != mainThread) return;
            armed = false;
            long step = System.nanoTime() - startNs;
            long cpu = CPU ? MX.getCurrentThreadCpuTime() - startCpu : step;
            long interval = havePrev ? startNs - prevStartNs : 0;
            prevStartNs = startNs;
            havePrev = true;
            end(interval, step, cpu);
        } catch (Throwable t) { fail(t); }
    }

    static void end(long intervalNs, long stepNs, long cpuNs) {
        if (!on) return;
        try {
            frameNo++;
            intervalMs = intervalNs / 1e6;
            stepMs = stepNs / 1e6;
            cpuMs = cpuNs / 1e6;
            LogSink s = log;
            if (s != null) {
                long now = System.currentTimeMillis();
                s.begin(now).num(frameNo).ms(intervalNs).ms(stepNs).ms(cpuNs).end(now);
            }
        } catch (Throwable t) { fail(t); }
    }

    static void openLog(File f) {
        try {
            closeLog();
            log = new LogSink(f, logLimit);
        } catch (Throwable t) {
            log = null;
            System.out.println("[PZButter] frame log not opened: " + t);
        }
    }

    static void flush() {
        try {
            LogSink s = log;
            if (s != null) s.flushBuf();
        } catch (Throwable t) { fail(t); }
    }

    private static void closeLog() {
        LogSink s = log;
        log = null;
        if (s != null) try { s.close(); } catch (Throwable ignored) { }
    }

    private static void fail(Throwable t) {
        try {
            if (++failures >= 5) {
                on = false;
                closeLog();
                System.out.println("[PZButter] frame clock off after repeated failures: " + t);
            }
        } catch (Throwable ignored) { }
    }
}
