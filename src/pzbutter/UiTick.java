package pzbutter;

import java.util.WeakHashMap;

/**
 * uiTickStagger (SPEC 3.9): the game runs every window's Lua update in the same frame, once per 100 ms
 * (UIManager.doTick), which makes a periodic spike. Here each top-level window gets its own phase within the
 * 100 ms (the n-th window seen: n * 37 mod 100 ms) and its update runs when its own slot comes round, so the
 * windows' updates spread over the frames. Each window still updates once per 100 ms. Main thread only.
 */
final class UiTick {
    static final long PERIOD_MS = 100;
    static volatile boolean on;

    private static final WeakHashMap<Object, long[]> windows = new WeakHashMap<>();   // {phase, last slot}
    private static int seen;

    private UiTick() {}

    static void init() { on = Settings.flag("uiTickStagger"); }

    /** Should this window run its 10 Hz update now? */
    static boolean decide(Object window, long nowMs) {
        long[] w = windows.get(window);
        if (w == null) {   // a new window starts in its current slot and first ticks when the next one comes, as stock
            long phase = (seen++ * 37L) % PERIOD_MS;
            windows.put(window, new long[] {phase, Math.floorDiv(nowMs - phase, PERIOD_MS)});
            return false;
        }
        long slot = Math.floorDiv(nowMs - w[0], PERIOD_MS);
        if (slot == w[1]) return false;
        w[1] = slot;
        return true;
    }

    static int size() { return windows.size(); }

    static void reset() { windows.clear(); seen = 0; }
}
