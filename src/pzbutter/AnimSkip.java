package pzbutter;

/** zombieAnimDistantSkip: far zombies refill bone matrices on one frame in N, staggered. */
public final class AnimSkip {
    private AnimSkip() {}

    public static final Guard distGuard = new Guard("zombieAnimDistantSkip");
    public static final Guard offGuard = new Guard("zombieBoneSkipOffscreen");

    /** Game-thread flag: set by the outer OnEnter, read by the matrix-fill OnEnter holders, cleared on exit. */
    public static volatile boolean skipping;

    /** Counts since start (or last reset). */
    public static volatile long skipped, rebuilt;

    /** Cadence default / parsed values. */
    public static volatile int distTiles = 50;
    public static volatile int distEvery = 4;
    public static volatile int offEvery = 8;

    public static void init() {
        distGuard.on = Settings.flag("zombieAnimDistantSkip");
        distTiles = Settings.num("zombieAnimDistantTiles", 50, 1, 10000);
        distEvery = Settings.num("zombieAnimDistantEvery", 4, 1, 1000);
        offGuard.on = Settings.flag("zombieBoneSkipOffscreen");
        offEvery = Settings.num("zombieBoneSkipOffscreenEvery", 8, 1, 1000);
    }

    /** True when this frame skips the matrix fill. Never true when every < 2. */
    public static boolean cadenceSkip(int id, long frameNo, int every) {
        if (every < 2) return false;
        return (frameNo + id) % every != 0;
    }

    /** A zombie with no first-frame need, no parent player and not busy may skip. */
    public static boolean eligible(boolean isZombie, boolean needFirstFrame, boolean hasParent, boolean busy) {
        return isZombie && !needFirstFrame && !hasParent && !busy;
    }

    /** Squared distance beyond tiles*tiles skips. Exactly tiles does not skip. */
    public static boolean distant(float dx, float dy, int tiles) {
        return dx * dx + dy * dy > (float) tiles * tiles;
    }
}
