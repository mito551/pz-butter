package pzbutter;

import java.util.IdentityHashMap;

public final class RecalcMemo {
    private RecalcMemo() {}

    public static final Guard guard = new Guard("handoffRecalcFast");

    public static volatile long recalcRun;
    public static volatile long recalcSkipped;

    /** Active only inside loadInMainThread on the thread that entered; not re-entrant. */
    private static volatile boolean active;
    private static volatile Thread owner;

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
            if (active && cur == owner) return;   // nested: leave as is
            owner = cur;
            active = true;
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void exit() {
        try {
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
        done.clear();
        recalcRun = 0;
        recalcSkipped = 0;
    }
}