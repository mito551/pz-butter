package pzbutter;

/**
 * Plan 4 sound: the soundTickHz gate (one shared gate per frame for ambience and zombie sound parameters) and the
 * emitterIdleSkip decision. Main thread only.
 */
final class SoundTick {
    static volatile boolean on;     // soundTickHz > 0 with the master switch
    static volatile boolean idle;   // emitterIdleSkip with the master switch

    private static int hz;
    private static long lastFrame = -1, lastOpenMs = Long.MIN_VALUE / 2;
    private static boolean lastOpen = true;

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
        lastFrame = -1;
        lastOpenMs = Long.MIN_VALUE / 2;
        lastOpen = true;
    }

    /**
     * Should the shared gate open this frame? Decided once per frame: later calls in the same frame repeat the first
     * answer. Open when at least 1000/hz - 1 ms passed since it last opened (the tolerance keeps 60 fps from skipping).
     */
    static boolean gateOpen(long frameNo, long nowMs) {
        if (hz <= 0) return true;
        if (frameNo == lastFrame) return lastOpen;
        lastFrame = frameNo;
        if (nowMs - lastOpenMs >= 1000 / hz - 1) {
            lastOpenMs = nowMs;
            lastOpen = true;
        } else {
            lastOpen = false;
        }
        return lastOpen;
    }

    /** Skip a character's sound work this update? Only silent zombies: a queued or playing sound takes the full path. */
    static boolean skipEmitter(boolean isZombie, boolean emitterClear) {
        return isZombie && emitterClear;
    }
}
