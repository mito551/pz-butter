package pzbutter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Timing of each GameWindow.frameStep, and the optional frame log. frameStep runs once per frame; the frame
 * limiter's wait is outside it, so the interval (start to start) is the frame time and the step is the work.
 * Touched from the main thread only; enter/exit return at once on any other thread.
 */
public final class FrameClock {
    public static volatile boolean on;
    public static volatile Thread mainThread;
    public static long frameNo;
    public static double intervalMs, stepMs, cpuMs;   // last finished frame; interval 0 for the first

    static long logLimit = 50L << 20;
    static BufferedWriter log;
    private static File logFile;
    private static long logBytes, lastFlushMs;
    private static final StringBuilder line = new StringBuilder(64);

    private static ThreadMXBean cpuBean;
    private static long startWall, startCpu, prevStart, pendingInterval;
    private static int failures;

    private FrameClock() {}

    static void init() {
        try {
            ThreadMXBean b = ManagementFactory.getThreadMXBean();
            if (b.isCurrentThreadCpuTimeSupported()) cpuBean = b;
        } catch (Throwable t) { cpuBean = null; }
        on = Settings.enabled;
        if (on && Settings.frameLog) openLog(new File(System.getProperty("user.home"), "Zomboid/pzbutter/frames.out"));
    }

    private static long cpuNow() {
        try { return cpuBean != null ? cpuBean.getCurrentThreadCpuTime() : System.nanoTime(); }
        catch (Throwable t) { return System.nanoTime(); }
    }

    public static void enter() {
        if (!on) return;
        try {
            if (mainThread == null) mainThread = Thread.currentThread();
            else if (Thread.currentThread() != mainThread) return;
            long now = System.nanoTime();
            pendingInterval = prevStart == 0 ? 0 : now - prevStart;
            prevStart = startWall = now;
            startCpu = cpuNow();
        } catch (Throwable t) { fail(t); }
    }

    public static void exit() {
        if (!on || Thread.currentThread() != mainThread || startWall == 0) return;
        long step = System.nanoTime() - startWall, cpu = cpuNow() - startCpu;
        startWall = 0;
        end(pendingInterval, step, cpu);
    }

    /** One finished frame: time since the previous frame started, time inside frameStep, its CPU time. */
    static void end(long intervalNs, long stepNs, long cpuNs) {
        if (!on) return;
        try {
            frameNo++;
            intervalMs = intervalNs / 1e6;
            stepMs = stepNs / 1e6;
            cpuMs = cpuNs / 1e6;
            if (log != null) {
                long now = System.currentTimeMillis();
                line.setLength(0);
                line.append(now).append(' ').append(frameNo).append(' ');
                ms(line, intervalMs); line.append(' '); ms(line, stepMs); line.append(' ');
                ms(line, cpuMs); line.append('\n');
                log.append(line);
                logBytes += line.length();
                if (logBytes > logLimit) rotate();
                else if (now - lastFlushMs >= 1000) { log.flush(); lastFlushMs = now; }
            }
        } catch (Throwable t) { fail(t); }
    }

    private static void ms(StringBuilder sb, double ms) {
        long u = Math.round(ms * 1000);
        sb.append(u / 1000).append('.');
        long f = u % 1000;
        if (f < 100) sb.append('0');
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    static void openLog(File f) {
        closeLog();
        try {
            f.getParentFile().mkdirs();
            log = new BufferedWriter(new FileWriter(f));
            logFile = f;
            logBytes = 0;
        } catch (Throwable t) {
            log = null;
            System.out.println("[PZButter] frame log off, can't open " + f + ": " + t);
        }
    }

    private static void rotate() throws java.io.IOException {
        log.close();
        File old = new File(logFile.getPath() + ".1");
        old.delete();
        logFile.renameTo(old);
        log = new BufferedWriter(new FileWriter(logFile));
        logBytes = 0;
    }

    static void flush() {
        try { if (log != null) log.flush(); } catch (Throwable t) { fail(t); }
    }

    private static void closeLog() {
        try { if (log != null) log.close(); } catch (Throwable ignored) {}
        log = null;
    }

    private static void fail(Throwable t) {
        if (++failures >= 5) {
            on = false;
            closeLog();
            System.out.println("[PZButter] frame clock off after errors: " + t);
        }
    }
}
