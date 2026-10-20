package pzbutter;

/**
 * Even frame delivery (SPEC 3.12): each frame's swap is held until a time after the game moment it shows
 * (its frameStep start), so frames reach the screen at a steady offset instead of whenever they are drawn.
 * frameQueue: at caps of frameQueueFixedMinFps and up, the hold is frameQueueMinPct..MaxPct of a frame
 * (2.6 frames by default). Below that, or with presentPacing alone, a learned hold is used: it rises at once to
 * cover a late frame, and after frameQueueLowCapCalmMs without one it falls toward the 95th percentile of
 * readiness plus a margin, by frameQueueLowCapDecayPct of a frame per second. A late frame is never delayed.
 */
final class SwapHold {
    static final Guard guard = new Guard("swap hold");
    static volatile boolean queue, gpu;
    static volatile String pacing = "off";
    static Config config = new Config(260, 260, 100, 60, 1000, 25, 20);
    static final Learner learner = new Learner(config);
    static final long SPIN_NS = 200_000L;
    private static final Parker parker = new Parker();

    private SwapHold() {}

    record Config(int minPct, int maxPct, int fixedMinFps, int lowMinPct, int calmMs, int decayPct, int marginPct) {}

    static void init() {
        queue = Settings.flag("frameQueue");
        pacing = Settings.mode("presentPacing");
        gpu = Settings.flag("frameQueueGpu");
        config = new Config(Settings.num("frameQueueMinPct", 260, 10, 1000), Settings.num("frameQueueMaxPct", 260, 10, 1000),
            Settings.num("frameQueueFixedMinFps", 100, 1, 1000), Settings.num("frameQueueLowCapMinPct", 60, 0, 1000),
            Settings.num("frameQueueLowCapCalmMs", 1000, 0, 60_000), Settings.num("frameQueueLowCapDecayPct", 25, 0, 1000),
            Settings.num("frameQueueLowCapMarginPct", 20, 0, 1000));
        learner.config = config;
        guard.on = queue || !pacing.equals("off");
        if (guard.on) { FrameStamps.guard.on = true; PresentLog.guard.on = true; }
    }

    /** Hold this frame? presentPacing=auto follows VRR, which is only known once the window is up. */
    static boolean holding() {
        return queue || pacing.equals("on") || pacing.equals("auto") && Boolean.TRUE.equals(Vrr.active());
    }

    /** The hold for a frame: none uncapped; the fixed bounds at high caps with frameQueue; else the learned value. */
    static long holdNs(long periodNs, int capFps, boolean fixedQueue, long learnedNs, Config c) {
        if (capFps <= 0 || periodNs <= 0) return 0;
        long max = periodNs * c.maxPct() / 100;
        long min = fixedQueue && capFps >= c.fixedMinFps() ? periodNs * c.minPct() / 100 : periodNs * c.lowMinPct() / 100;
        return Math.max(min, Math.min(max, learnedNs));
    }

    /** ns to wait before swapping; 0 when the frame is already past its hold. */
    static long waitNs(long now, long frameStartNs, long holdNs) { return Math.max(0, frameStartNs + holdNs - now); }

    /**
     * The wait for this frame's swap. The hold is taken before this frame is learned from, so a frame that is
     * already late is swapped at once; the raised hold applies from the next frame on.
     */
    static long holdWaitNs(long now, long start, long gpuNs, long periodNs, int cap, Learner l, boolean queue, Config c) {
        long hold = holdNs(periodNs, cap, queue, l.holdNs(), c);
        l.observe(now - start + gpuNs, now, periodNs);
        return waitNs(now, start, hold);
    }

    static volatile Runnable serveDuringWait;   // serves a pending input request (inputLatch) from inside the hold

    /** A thread that asked for a fresh input poll wakes the hold so it can be served at once. */
    static void wakeForRequest() { parker.wake(); }

    /** Park (in a loop, serving input requests), then spin the last 0.2 ms, until ns have passed. */
    static void waitPrecise(long ns) {
        long deadline = System.nanoTime() + Math.min(ns, Parker.MAX_NS);
        for (;;) {
            Runnable r = serveDuringWait;
            if (r != null && InputLatch.pending()) r.run();
            long left = deadline - System.nanoTime();
            if (left <= SPIN_NS) break;
            parker.park(left - SPIN_NS);
        }
        while (System.nanoTime() < deadline) Thread.onSpinWait();
    }

    /** Learns how long after its start a frame is ready to swap; the window is kept sorted. Render thread only. */
    static final class Learner {
        Config config;

        Learner(Config c) { config = c; }

        private long hold, lastLate, lastUpdate;
        private boolean started;
        private final long[] win = new long[512]; // kept ascending
        private int n;

        long holdNs() { return Math.max(0, hold); }

        void observe(long readyNs, long nowNs, long periodNs) {
            if (periodNs <= 0) return;
            Config c = config;
            long min = periodNs * c.lowMinPct() / 100;
            long max = periodNs * c.maxPct() / 100;
            long margin = periodNs * c.marginPct() / 100;
            if (!started) { started = true; hold = min; lastLate = lastUpdate = nowNs; }
            if (n < 512) {
                int lo = 0, hi = n;
                while (lo < hi) { int m = (lo + hi) >>> 1; if (win[m] <= readyNs) lo = m + 1; else hi = m; }
                System.arraycopy(win, lo, win, lo + 1, n - lo);
                win[lo] = readyNs;
                n++;
            }
            if (readyNs > hold) { hold = Math.min(max, readyNs + margin); lastLate = nowNs; }
            long elapsed = nowNs - lastUpdate;
            if (elapsed >= 1_000_000_000L) {
                long target = (n == 0 ? 0 : win[Math.min(n - 1, n * 95 / 100)]) + margin;
                if (target > hold) hold = target;
                else if (nowNs - lastLate >= c.calmMs() * 1_000_000L)
                    hold = Math.max(target, hold - periodNs * c.decayPct() / 100 * elapsed / 1_000_000_000L);
                lastUpdate = nowNs;
                n = 0;
            }
            hold = Math.max(min, Math.min(max, hold));
        }
    }
}
