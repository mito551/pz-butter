package pzbutter;

/**
 * Plan 4 sound: the soundTickHz gate (one shared gate per frame for ambience and zombie sound parameters) and the
 * emitterIdleSkip decision. Main thread only.
 */
final class SoundTick {
    static volatile boolean on;     // soundTickHz > 0 with the master switch
    static volatile boolean idle;   // emitterIdleSkip with the master switch

    private static int hz;
    private static final java.time.Clock CLOCK = java.time.Clock.systemUTC();
    private static final long NEVER = Long.MIN_VALUE;
    /** [0]: (frameNo << 1) | open for the last decided frame. [1]: last opening time in ms. */
    private static final long[] G = {NEVER, NEVER};

    private SoundTick() {}

    static void init() {
        hz = Settings.num("soundTickHz", 0, 0, 240);
        on = Settings.enabled && hz > 0;
        idle = Settings.flag("emitterIdleSkip");
        SoundPatches.tickGuard.on = on;
        SoundPatches.idleGuard.on = idle;
    }

    /** Test hook: a fresh gate at this rate. */
    static void resetForTest(int rate) {
        hz = rate;
        G[0] = NEVER;
        G[1] = NEVER;
    }

    static long nowMs() { return CLOCK.millis(); }

    /** -1 if the frame is undecided, else 1 open / 0 closed (checked before the clock is read: low-probability variant a of 2026-10-22). */
    static int memo(long frameNo) {
        long p = G[0];
        return (p >> 1) == frameNo ? (int) (p & 1) : -1;
    }

    /**
     * Should the shared gate open this frame? Decided once per frame: later calls in the same frame repeat the first
     * answer. Open when at least 1000/hz - 1 ms (integer division) passed since it last opened, so 60 fps never skips.
     */
    static boolean gateOpen(long frameNo, long nowMs) {
        int m = memo(frameNo);
        if (m >= 0) return m != 0;
        boolean open = hz <= 0 || G[1] == NEVER || nowMs < G[1] || nowMs - G[1] >= 1000 / hz - 1;   // a clock step back reopens
        if (open) G[1] = nowMs;
        G[0] = (frameNo << 1) | (open ? 1L : 0L);
        return open;
    }

    /** The thread inside a forced parameter update (I1): its own calls to the tick gate pass, other threads still skip. */
    private static volatile Thread forcer;

    /** A zombie with a sound queued takes its parameter update for real when the tick gate is on. */
    static boolean forceParams(boolean tickOn, boolean isZombie, boolean hasSoundsToStart) {
        return tickOn && isZombie && hasSoundsToStart;
    }

    static void forceBegin() { forcer = Thread.currentThread(); }

    static void forceEnd() { forcer = null; }

    /** True inside this thread's forced update (one volatile read when no update is forced). */
    static boolean forcing() {
        Thread f = forcer;
        return f != null && f == Thread.currentThread();
    }

    /** Skip a character's sound work this update? Only silent zombies: a queued or playing sound takes the full path. */
    static boolean skipEmitter(boolean isZombie, boolean emitterClear) {
        return isZombie && emitterClear;
    }
}
