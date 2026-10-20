package pzbutter;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Swap timing on the render thread and the delivery logs:
 * present.out, one line per swap: <epoch ms> <frame no> <ms since the previous swap ended>
 *   <ms from that frame's start to this swap's end> <ms in the swap> <input age ms>;
 * threads.out, one line per second: <epoch ms> <game thread CPU %> <render thread CPU %>.
 */
final class PresentLog {
    static final Guard guard = new Guard("present log");
    static volatile Thread renderThread;
    static volatile long lastSwapEndNs, lastSwapNs;   // render thread writes, others read
    static volatile long lastPresentedFrameNo = -1;    // the game frame the last swap showed

    static LogSink present, threads;
    private static long swapStartNs, lastTickNs, lastGameCpu, lastRenderCpu;
    private static final ThreadMXBean MX = mx();

    private PresentLog() {}

    private static ThreadMXBean mx() {
        try { return ManagementFactory.getThreadMXBean(); } catch (Throwable t) { return null; }
    }

    static void init() {
        if (Settings.enabled && Settings.frameLog) openLogs(new File(System.getProperty("user.home"), "Zomboid/pzbutter"));
        if (Settings.flag("frameStartGate")) { guard.on = true; FrameStamps.guard.on = true; }   // swap timing without the log
    }

    static void openLogs(File dir) {
        try {
            close();
            present = new LogSink(new File(dir, "present.out"), FrameClock.logLimit);
            threads = new LogSink(new File(dir, "threads.out"), FrameClock.logLimit);
            guard.on = true;
            FrameStamps.guard.on = true;
        } catch (Throwable t) {
            close();
            System.out.println("[PZButter] delivery logs not opened: " + t);
        }
    }

    /** Swap hooks: only the first thread that swaps (the render thread) is measured. */
    static void swapStart() {
        if (!isRenderThread()) return;
        swapStartNs = System.nanoTime();
    }

    static void swapEnd() {
        if (!isRenderThread() || swapStartNs == 0) return;
        present(swapStartNs, System.nanoTime(), System.currentTimeMillis());
        swapStartNs = 0;
    }

    private static boolean isRenderThread() {
        Thread t = Thread.currentThread(), r = renderThread;
        if (r == null) renderThread = r = t;
        return r == t;
    }

    /** One finished swap. */
    static void present(long startNs, long endNs, long epochMs) {
        long prev = lastSwapEndNs;
        lastSwapNs = endNs - startNs;
        lastSwapEndNs = endNs;
        if (FrameStamps.renderFrameNo >= 0) lastPresentedFrameNo = FrameStamps.renderFrameNo;
        InputLatch.waitShown.wake();
        LogSink s = present;
        if (s == null || !guard.on) return;
        try {
            long no = FrameStamps.renderFrameNo;
            s.begin(epochMs).num(no).ms(prev == 0 ? 0 : endNs - prev).ms(no < 0 ? 0 : endNs - FrameStamps.renderFrameStartNs)
                .ms(endNs - startNs).ms(no < 0 ? 0 : FrameStamps.renderInputAgeNs).end(epochMs);
        } catch (Throwable t) { guard.fail(t); }
    }

    /** Game thread, once per frame: a threads.out line once a second has passed. */
    static void tick() {
        if (threads == null || !guard.on || MX == null) return;
        try {
            long now = System.nanoTime();
            if (lastTickNs != 0 && now - lastTickNs < 1_000_000_000L) return;
            Thread g = FrameClock.mainThread, r = renderThread;
            long gameCpu = g == null ? 0 : MX.getThreadCpuTime(g.threadId());
            long renderCpu = r == null ? 0 : MX.getThreadCpuTime(r.threadId());
            if (lastTickNs != 0) threadsLine(System.currentTimeMillis(), gameCpu - lastGameCpu, renderCpu - lastRenderCpu, now - lastTickNs);
            lastTickNs = now;
            lastGameCpu = gameCpu;
            lastRenderCpu = renderCpu;
        } catch (Throwable t) { guard.fail(t); }
    }

    static void threadsLine(long epochMs, long gameCpuNs, long renderCpuNs, long elapsedNs) {
        LogSink s = threads;
        if (s == null) return;
        try {
            s.begin(epochMs).fixed3(pct1000(gameCpuNs, elapsedNs)).fixed3(pct1000(renderCpuNs, elapsedNs)).end(epochMs);
        } catch (Throwable t) { guard.fail(t); }
    }

    private static long pct1000(long cpuNs, long elapsedNs) {
        return elapsedNs <= 0 ? 0 : Math.round(cpuNs * 100_000.0 / elapsedNs);
    }

    static void flush() {
        try {
            if (present != null) present.flushBuf();
            if (threads != null) threads.flushBuf();
        } catch (Throwable t) { guard.fail(t); }
    }

    private static void close() {
        for (LogSink s : new LogSink[] {present, threads}) if (s != null) try { s.close(); } catch (Throwable ignored) { }
        present = threads = null;
    }
}
