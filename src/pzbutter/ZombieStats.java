package pzbutter;

import java.util.function.Consumer;

/**
 * The Plan 8 counters in the log (ours): once a minute, one {@code [PZButter] zombies:} line with the per-minute counts of what the
 * 3D cap, the model budget, the sort, the sight pass and the facing fix did, so the in-game check can see them work. Printed only when
 * something happened in the minute (a key that is off, or a horde that is not there, adds nothing). The minute starts at the first
 * frame end after a key is on; the clock is the caller's nanoTime, so the cadence is testable.
 */
final class ZombieStats {
    private ZombieStats() {}

    static final long MINUTE_NS = 60_000_000_000L;

    /** On when any key that keeps one of these counters is on (set by init); the frame end does nothing otherwise. */
    static final Guard guard = new Guard("zombies log line");

    /** Where the line goes (tests replace it). */
    static Consumer<String> out = System.out::println;

    private static final int CULLS = 0, GRANTS = 1, CAPPED = 2, DEFERRED = 3, SORTS = 4, MISSES = 5, SWAPS = 6, REBUILDS = 7, KEPT = 8, N = 9;

    private static long[] last = new long[N];
    private static long nextNs;
    private static boolean started;

    static void init() {
        guard.on = FacingFix.guard.on || ZombieLod.blendGuard.on || ZombieLod.capGuard.on || ZombieLod.budgetGuard.on
            || ZombiePatches.losGuard.on || CullSort.guard.on;
        started = false;
        last = new long[N];
    }

    /** The counters as they stand (cumulative since start). */
    static long[] snapshot() {
        long[] s = new long[N];
        s[CULLS] = ZombieLod.culls;
        s[GRANTS] = ZombieLod.grantsTotal;
        s[CAPPED] = ZombieLod.capped;
        s[DEFERRED] = ZombieLod.deferred;
        s[SORTS] = CullSort.sorts;
        s[MISSES] = CullSort.misses;
        s[SWAPS] = ZombiePatches.losSwaps;
        s[REBUILDS] = SpottedStack.rebuilds;
        s[KEPT] = FacingFix.kept;
        return s;
    }

    /** The log line for a minute's counts (differences, in snapshot order). */
    static String line(long[] d) {
        return "[PZButter] zombies: culls " + d[CULLS] + ", grants " + d[GRANTS] + ", capped " + d[CAPPED] + ", deferred " + d[DEFERRED]
            + ", sorts " + d[SORTS] + "/misses " + d[MISSES] + ", los swaps " + d[SWAPS] + "/rebuilds " + d[REBUILDS]
            + ", facing kept " + d[KEPT];
    }

    /** Frame end: every 60 s from the first call, the line for the minute's counts when any of them is not zero. */
    static void tick(long nowNs) {
        long[] cur = snapshot();
        if (!started) {
            started = true;
            nextNs = nowNs + MINUTE_NS;
            last = cur;
            return;
        }
        if (nowNs < nextNs) return;
        nextNs = nowNs + MINUTE_NS;
        long[] d = new long[N];
        boolean any = false;
        for (int i = 0; i < N; i++) {
            d[i] = Math.max(0, cur[i] - last[i]);
            any |= d[i] != 0;
        }
        last = cur;
        if (any) out.accept(line(d));
    }

    static void resetForTest() {
        guard.on = false;
        ZombiePatches.losSwaps = 0;
        out = System.out::println;
        started = false;
        last = new long[N];
        nextNs = 0;
    }
}
