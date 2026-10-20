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
    static volatile boolean queue, pacing, gpu;
    static Config config = new Config(260, 260, 100, 60, 1000, 25, 20);
    static final Learner learner = new Learner(config);
    static final long SPIN_NS = 200_000L;
    private static final Parker parker = new Parker();

    private SwapHold() {}

    record Config(int minPct, int maxPct, int fixedMinFps, int lowMinPct, int calmMs, int decayPct, int marginPct) {}

    static void init() {
        queue = Settings.flag("frameQueue");
        String p = Settings.mode("presentPacing");
        pacing = p.equals("on") || (p.equals("auto") && Boolean.TRUE.equals(Vrr.active()));
        gpu = Settings.flag("frameQueueGpu");
        config = new Config(Settings.num("frameQueueMinPct", 260, 10, 1000), Settings.num("frameQueueMaxPct", 260, 10, 1000),
            Settings.num("frameQueueFixedMinFps", 100, 1, 1000), Settings.num("frameQueueLowCapMinPct", 60, 0, 1000),
            Settings.num("frameQueueLowCapCalmMs", 1000, 0, 60_000), Settings.num("frameQueueLowCapDecayPct", 25, 0, 1000),
            Settings.num("frameQueueLowCapMarginPct", 20, 0, 1000));
        learner.config = config;
        guard.on = queue || pacing;
        if (guard.on) { FrameStamps.guard.on = true; PresentLog.guard.on = true; }
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

    /** Park, then spin the last 0.2 ms, until ns have passed. */
    static void waitPrecise(long ns) {
        long deadline = System.nanoTime() + Math.min(ns, Parker.MAX_NS);
        if (ns > SPIN_NS) parker.park(ns - SPIN_NS);
        while (System.nanoTime() < deadline) Thread.onSpinWait();
    }

    /** Learns how long after its start a frame is ready to swap. Render thread only. */
    static final class Learner {
        Config config;
        private final long[] window = new long[512], scratch = new long[512];
        private int count;
        private long hold = -1, lastLateNs, lastUpdateNs;

        Learner(Config c) { config = c; }

        long holdNs() { return Math.max(0, hold); }

        void observe(long readyNs, long nowNs, long periodNs) {
            if (periodNs <= 0) return;
            Config c = config;
            long min = periodNs * c.lowMinPct() / 100, max = periodNs * c.maxPct() / 100, margin = periodNs * c.marginPct() / 100;
            if (hold < 0) { hold = min; lastLateNs = lastUpdateNs = nowNs; }
            if (count < window.length) window[count++] = readyNs;
            if (readyNs > hold) { hold = Math.min(max, readyNs + margin); lastLateNs = nowNs; }
            long elapsed = nowNs - lastUpdateNs;
            if (elapsed >= 1_000_000_000L) {
                long target = p95() + margin;
                if (target > hold) hold = target;
                else if (nowNs - lastLateNs >= c.calmMs() * 1_000_000L)
                    hold = Math.max(target, hold - periodNs * c.decayPct() / 100 * elapsed / 1_000_000_000L);
                lastUpdateNs = nowNs;
                count = 0;
            }
            hold = Math.max(min, Math.min(max, hold));
        }

        private long p95() {
            if (count == 0) return 0;
            System.arraycopy(window, 0, scratch, 0, count);
            java.util.Arrays.sort(scratch, 0, count);
            return scratch[Math.min(count - 1, count * 95 / 100)];
        }
    }
}
