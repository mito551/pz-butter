package pzbutter;

/**
 * Runtime features (Plan 12 Task 1: luaGcNoop). Pure cores and guards; the holders live in
 * {@link RuntimePatches}.
 */
public final class RuntimeFixes {
    private RuntimeFixes() {}

    /** luaGcNoop: mods' collectgarbage no longer forces a full collection. */
    public static final Guard gcGuard = new Guard("luaGcNoop");

    /** lightingSyncPark: the lighting thread parks to its next update instead of yield-spinning. */
    public static final Guard lightGuard = new Guard("lightingSyncPark");

    /** Stored deadline of the lighting sync (nextFrame). Reset on init/resetForTest. */
    static volatile long lightDeadlineNs;
    static volatile boolean lightDeadlineStarted;

    /** Forced collections skipped since start (or the last resetForTest). */
    public static volatile long gcSkipped;

    /** Minute cadence bookkeeping. */
    private static volatile long minuteStartNs;
    private static volatile boolean minuteStarted;
    private static volatile long minuteBaseline;

    static void init() {
        gcGuard.on = Settings.flag("luaGcNoop");
        lightGuard.on = Settings.flag("lightingSyncPark");
        gcSkipped = 0;
        minuteStarted = false;
        minuteBaseline = 0;
        lightDeadlineNs = 0;
        lightDeadlineStarted = false;
    }

    /** True for the options stock answers with {@code System.gc()} (null, "step", "collect"). */
    public static boolean skipCollect(Object option) {
        if (!gcGuard.on) return false;
        boolean skip;
        if (option == null) skip = true;
        else if (option instanceof String s) skip = s.equals("step") || s.equals("collect");
        else skip = false;
        if (skip) gcSkipped++;
        return skip;
    }

    /** Called each frame; prints the minute line (only when the count rose) once a minute. Returns the delta printed (0 if none). */
    public static long tick(long nowNs) {
        if (!minuteStarted) { minuteStarted = true; minuteStartNs = nowNs; minuteBaseline = gcSkipped; return 0; }
        if (nowNs - minuteStartNs < 60_000_000_000L) return 0;
        minuteStartNs = nowNs;
        long delta = gcSkipped - minuteBaseline;
        minuteBaseline = gcSkipped;
        if (delta > 0) System.out.printf("[PZButter] luaGcNoop: %d forced collections skipped%n", delta);
        return delta > 0 ? delta : 0;
    }

    /** Stock Sync.sync's catch-up rule: advance by 1e9/fps, never behind now. */
    public static long nextDeadline(long prevDeadline, long now, int fps) {
        if (fps <= 0) return prevDeadline > now ? prevDeadline : now;
        long step = 1_000_000_000L / fps;
        long cand = prevDeadline + step;
        return cand > now ? cand : now;
    }

    /** Parks (re-parking on spurious wakeups) until the deadline (nanoseconds, System.nanoTime scale). */
    public static void parkUntil(long deadlineNs) {
        while (System.nanoTime() < deadlineNs) {
            long rem = deadlineNs - System.nanoTime();
            if (rem <= 0) break;
            java.util.concurrent.locks.LockSupport.parkNanos(rem);
        }
    }

    public static void resetForTest() {
        gcGuard.on = false;
        lightGuard.on = false;
        gcSkipped = 0;
        minuteStarted = false;
        minuteStartNs = 0;
        minuteBaseline = 0;
        lightDeadlineNs = 0;
        lightDeadlineStarted = false;
    }
}
