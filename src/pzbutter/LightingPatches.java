package pzbutter;

import java.util.HashMap;
import java.util.IdentityHashMap;
import me.zed_0xff.zombie_buddy.Patch;

/** lightVisionOnce (SPEC 1118): {@code LightingJNI.updateChunk} asks each
 * square {@code testVisionAdjacent} for its 8 neighbours and the diagonal
 * checks re-ask the cardinal and diagonal answers of other squares, so the
 * same (square, direction) question is asked many times where one answer per
 * square exists. Nothing{@code updateChunk} does changes the squares or their
 * special objects, so inside one call the answer is stable: keep each answer
 * in a per-thread memo, keyed by the square object itself (never its value),
 * alive only while that one {@code updateChunk} runs. */
public final class LightingPatches {

    public static final Guard guard = new Guard("lightVisionOnce");

    /** One updateChunk call per thread: while active, the memo answers vision
     * questions; other callers (LosUtil paths, workers) see no memo at all. */
    private static final ThreadLocal<Boolean> ACTIVE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<IdentityHashMap<Object, HashMap<Long, Object>>> MEMO =
            ThreadLocal.withInitial(IdentityHashMap::new);

    static void init() {
        try {
            guard.on = Settings.flag("lightVisionOnce");
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    public static void windowBegin() {
        try {
            if (!guard.on) {
                return;
            }
            MEMO.get().clear();
            ACTIVE.set(Boolean.TRUE);
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    public static void windowEnd() {
        try {
            ACTIVE.set(Boolean.FALSE);
            MEMO.get().clear();
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    static boolean active() {
        return guard.on && ACTIVE.get() == Boolean.TRUE;
    }

    /** 0 = outside the table (never memoized), so a real key is never 0. */
    static long key(int dx, int dy, int dz, boolean edge, boolean diag) {
        if (dx > 10000 || dx < -10000 || dy > 10000 || dy < -10000 || dz > 127 || dz < -128) {
            return 0L;
        }
        return ((long) (dx + 10000) << 40)
                | ((long) (dy + 10000) << 20)
                | ((dz + 128) << 4)
                | (edge ? 2 : 0)
                | (diag ? 1 : 0);
    }

    public static Object memoGet(Object square, int dx, int dy, int dz, boolean edge, boolean diag) {
        if (!active() || square == null) {
            return null;
        }
        long k = key(dx, dy, dz, edge, diag);
        if (k == 0L) {
            return null;
        }
        HashMap<Long, Object> slots = MEMO.get().get(square);
        return slots == null ? null : slots.get(k);
    }

    public static void memoStore(Object square, int dx, int dy, int dz, boolean edge, boolean diag,
            Object answer) {
        if (!active() || square == null || answer == null) {
            return;
        }
        long k = key(dx, dy, dz, edge, diag);
        if (k == 0L) {
            return;
        }
        MEMO.get().computeIfAbsent(square, s -> new HashMap<Long, Object>()).put(k, answer);
    }

    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "testVisionAdjacent")
    public static class VisionMemo {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(
                @Patch.This Object square,
                @Patch.Argument(0) int dx,
                @Patch.Argument(1) int dy,
                @Patch.Argument(2) int dz,
                @Patch.Argument(3) boolean edge,
                @Patch.Argument(4) boolean diag,
                @Patch.Local("v") Object v) {
            if (!guard.on) {
                return false;
            }
            try {
                v = memoGet(square, dx, dy, dz, edge, diag);
                return v != null;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.This Object square,
                @Patch.Argument(0) int dx,
                @Patch.Argument(1) int dy,
                @Patch.Argument(2) int dz,
                @Patch.Argument(3) boolean edge,
                @Patch.Argument(4) boolean diag,
                @Patch.Return(readOnly = false) zombie.iso.LosUtil.TestResults ret,
                @Patch.Local("v") Object v) {
            if (v != null) {
                return;
            }
            try {
                // ret stays null on the thrown path; null is never stored
                memoStore(square, dx, dy, dz, edge, diag, ret);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    @Patch(className = "zombie.iso.LightingJNI", methodName = "updateChunk")
    public static class VisionOnce {

        @Patch.OnEnter
        public static void enter() {
            if (!guard.on) {
                return;
            }
            try {
                windowBegin();
            } catch (Throwable t) {
                guard.fail(t);
            }
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            if (!guard.on) {
                return;
            }
            try {
                windowEnd();
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    private LightingPatches() {
    }
}
