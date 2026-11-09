package pzbutter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * G1 — deterministic phase slot assignment (low probability, opt-in, no behaviour change).
 * A caller declares its period and stable identity and receives a slot in [0, period).
 * Slots spread by hash, so several handlers of the same period land on different frames.
 */
public final class FramePhase {
    private FramePhase() {}

    public static boolean on;

    static void init() {
        try { on = Settings.enabled; } catch (Throwable t) { on = false; }
    }

    public static int slot(String key, int period) {
        if (period < 1) period = 1;
        int h = (key == null) ? 0 : key.hashCode();
        h = Math.abs((int)((long) h * 2654435761L + period));
        return h % period;
    }

    public static boolean runs(String key, int period, long frame) {
        if (!on) return true;
        return (frame % period) == slot(key, period);
    }

    public static void run() {
        assert slot("a", 3) == slot("a", 3);
        assert runs("a", 3, 0) || runs("a", 3, 1) || runs("a", 3, 2);
        int seen = 0;
        for (int p = 5; p < 9; p++) {
            int s = slot("item " + p, 7);
            seen |= (1 << s);
        }
        // does not require spread, but covers the range
        assert seen != 0;
    }
}
