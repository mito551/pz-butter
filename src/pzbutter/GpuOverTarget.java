package pzbutter;

import java.util.concurrent.atomic.AtomicReference;

/** WS-2: long-term GPU frame time over target; consumed by PZButter_WindSway for tree-detail auto-tuning. */
final class GpuOverTarget {
    private GpuOverTarget() {}

    static final class State {
        public volatile double lastGpuMs = -1;
        public volatile boolean overTarget;
    }

    static final State S = new State();

    /** Java object handed to Lua. Field reads stay live through the same instance. */
    static final State luaHolder = S;

    static boolean on;

    static final Guard guard = new Guard("gpuOverTarget");

    private static final long TARGET_MS = 16;
    private static final long OVER_NANOS = 5_000_000_000L;
    private static final long RESTORE_NANOS = 10_000_000_000L;

    private static long overSince = -1;
    private static long underSince = -1;

    static void init() {
        try {
            on = Settings.enabled && Settings.flag("windSwayAutoTreeDetail");
            // WS-2 reads GpuTimer.lastGpuMs; make sure that timer runs even when
            // the build-time GPU bake planner (bakeBudgetGpu) is off.
            if (on) GpuTimer.guard.on = true;
        } catch (Throwable t) { guard.fail(t); }
    }

    static void tick() {
        if (!on) return;
        double sample = GpuTimer.lastGpuMs;
        S.lastGpuMs = sample;
        if (sample < 0) return;

        long now = System.nanoTime();
        if (sample > TARGET_MS) {
            if (overSince < 0) overSince = now;
            underSince = -1;
            if (now - overSince >= OVER_NANOS) S.overTarget = true;
            return;
        }
        if (underSince < 0) underSince = now;
        overSince = -1;
        if (now - underSince >= RESTORE_NANOS) S.overTarget = false;
    }
}
