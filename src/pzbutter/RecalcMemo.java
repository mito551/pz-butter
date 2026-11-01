package pzbutter;

import java.util.IdentityHashMap;

public final class RecalcMemo {
    private RecalcMemo() {}

    public static final Guard guard = new Guard("handoffRecalcFast");

    public static volatile long recalcRun;
    public static volatile long recalcSkipped;

    /** Active only inside loadInMainThread on the thread that entered; a nested call keeps the window, another thread's call is ignored. */
    private static volatile boolean active;
    private static volatile Thread owner;
    private static int depth;   // owner thread only

    private static final IdentityHashMap<Object, Long> done = new IdentityHashMap<>();

    static void init() {
        try {
            guard.on = Settings.enabled && Settings.flag("handoffRecalcFast");
        } catch (Throwable t) {
            guard.fail(t);
            guard.on = false;
        }
    }

    public static void enter() {
        try {
            Thread cur = Thread.currentThread();
            if (active) {
                if (cur == owner) depth++;   // nested: keep the window
                return;                      // another thread: not its window
            }
            owner = cur;
            depth = 1;
            active = true;
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void exit() {
        try {
            if (!active || Thread.currentThread() != owner) return;
            if (--depth > 0) return;
            active = false;
            owner = null;
            done.clear();
        } catch (Throwable t) { guard.fail(t); }
    }

    public static boolean skip(Object square, long roomId) {
        if (!guard.on) return false;
        if (!active) return false;
        if (Thread.currentThread() != owner) return false;
        try {
            Long prev = done.get(square);
            if (prev != null && prev.longValue() == roomId) {
                recalcSkipped++;
                return true;
            }
            done.put(square, roomId);
            recalcRun++;
            return false;
        } catch (Throwable t) {
            guard.fail(t);
            return false;
        }
    }

    static void resetForTest() {
        guard.on = false;
        active = false;
        owner = null;
        depth = 0;
        done.clear();
        recalcRun = 0;
        recalcSkipped = 0;
    }
}
