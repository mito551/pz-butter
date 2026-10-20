package pzbutter;

/**
 * presentPacing (SPEC 3.12, low-lag form): each frame's swap is held until a steady time after the game moment
 * it shows (its frameStep start), so frames reach the screen at an even offset. The hold is the 95th percentile of
 * the last second's readiness (+ presentPacingMarginPct of a frame), at most presentPacingMaxPct of a frame: only
 * frames that come unusually early wait, a late frame is never delayed, and one late frame doesn't raise the hold.
 * (SPEC's frameQueue, a fixed 2.6-frame hold, is left out: it trades input lag for smoothness.)
 */
final class SwapHold {
    static final Guard guard = new Guard("presentPacing");
    static volatile String pacing = "off";
    static Config config = new Config(100, 0);
    static final Learner learner = new Learner(config);
    static final long SPIN_NS = 200_000L;
    private static final Parker parker = new Parker();

    private SwapHold() {}

    record Config(int maxPct, int marginPct) {}

    static void init() {
        pacing = Settings.mode("presentPacing");
        config = new Config(Settings.num("presentPacingMaxPct", 100, 0, 100), Settings.num("presentPacingMarginPct", 0, 0, 100));
        learner.config = config;
        guard.on = !pacing.equals("off");
        if (guard.on) { FrameStamps.guard.on = true; PresentLog.guard.on = true; }
    }

    /** Hold this frame? presentPacing=auto follows VRR, which is only known once the window is up. */
    static boolean holding() {
        return pacing.equals("on") || pacing.equals("auto") && Boolean.TRUE.equals(Vrr.active());
    }

    /** The hold for a frame: none uncapped, else the learned value up to maxPct of a frame. */
    static long holdNs(long periodNs, int capFps, long learnedNs, Config c) {
        if (capFps <= 0 || periodNs <= 0) return 0;
        return Math.min(periodNs * c.maxPct() / 100, learnedNs);
    }

    /** ns to wait before swapping; 0 when the frame is already past its hold. */
    static long waitNs(long now, long frameStartNs, long holdNs) { return Math.max(0, frameStartNs + holdNs - now); }

    /** The wait for this frame's swap, from the hold as it stood before this frame is learned from. */
    static long holdWaitNs(long now, long start, long periodNs, int cap, Learner l, Config c) {
        long hold = holdNs(periodNs, cap, l.holdNs(), c);
        l.observe(now - start, now, periodNs);
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

    /**
     * Learns how long after its start a frame is ready to swap: once a second the hold becomes the exact 95th
     * percentile of that second's readiness plus the margin, capped at maxPct of a frame. The window is kept
     * sorted (binary insert), so the percentile is one read. Render thread only.
     */
    static final class Learner {
        Config config;
        private long hold, lastUpdate = -1;
        private final long[] win = new long[512];
        private int n;

        Learner(Config c) { config = c; }

        long holdNs() { return hold; }

        void observe(long readyNs, long nowNs, long periodNs) {
            if (periodNs <= 0) return;
            if (lastUpdate < 0) lastUpdate = nowNs;
            if (n < win.length) {
                int lo = 0, hi = n;
                while (lo < hi) { int m = (lo + hi) >>> 1; if (win[m] <= readyNs) lo = m + 1; else hi = m; }
                System.arraycopy(win, lo, win, lo + 1, n - lo);
                win[lo] = readyNs;
                n++;
            }
            if (nowNs - lastUpdate >= 1_000_000_000L) {
                Config c = config;
                long p95 = win[Math.min(n - 1, n * 95 / 100)];
                hold = Math.max(0, Math.min(periodNs * c.maxPct() / 100, p95 + periodNs * c.marginPct() / 100));
                lastUpdate = nowNs;
                n = 0;
            }
        }
    }
}
