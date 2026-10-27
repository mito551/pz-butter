package pzbutter;

/**
 * Runtime features (Plan 12 Task 1: luaGcNoop). Pure cores and guards; the holders live in
 * {@link RuntimePatches}.
 */
public final class RuntimeFixes {
    private RuntimeFixes() {}

    /** luaGcNoop: mods' collectgarbage no longer forces a full collection. */
    public static final Guard gcGuard = new Guard("luaGcNoop");

    /** Forced collections skipped since start (or the last resetForTest). */
    public static volatile long gcSkipped;

    /** Minute cadence bookkeeping. */
    private static volatile long minuteStartNs;
    private static volatile boolean minuteStarted;
    private static volatile long minuteBaseline;

    static void init() {
        gcGuard.on = Settings.flag("luaGcNoop");
        gcSkipped = 0;
        minuteStarted = false;
        minuteBaseline = 0;
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

    public static void resetForTest() {
        gcGuard.on = false;
        gcSkipped = 0;
        minuteStarted = false;
        minuteStartNs = 0;
        minuteBaseline = 0;
    }
}
