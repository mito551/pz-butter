package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Comparator;

/**
 * zombieCullSortFast: {@code IsoWorld.sceneCullZombies} sorts its zombies with {@code List.sort(compScoreToPlayer)} (42.22; 42.21 used the
 * game's own TimSort), whose compare works out both zombies' relevance scores every time (about n log n compares, two scores each).
 * Here {@code CompScoreToPlayer.compare} answers from scores stored by element identity: each score is worked out the first time the
 * sort asks for it, in the order the stock compare asks (first argument, then second), and kept until the cull ends. The comparison
 * sequence, the answers (NaN gives 0) and so the result are the game's. Game thread only, and only inside a cull (the shims in
 * ZombiePatches pass every other call through).
 */
final class CullSort {
    private CullSort() {}

    static final Guard guard = new Guard("zombieCullSortFast");

    /** A relevance score; may throw whatever the game's does. */
    interface Score { float of(Object z) throws Throwable; }

    /** The game's package-private comparator class and its getScore, found at init (loading it does not start IsoWorld). */
    static Class<?> scoreClass;
    static MethodHandle getScore;
    /** The game's comparator instance and its score function, bound at the first sort that passes it (IsoWorld's static initialiser builds it). */
    static Comparator<?> stock;
    static Score score;

    /** Scores worked out (one per distinct zombie and cull). */
    static long misses;

    /** Culls whose sort ran on stored scores (the zombies line). */
    static long sorts;

    static void init() {
        guard.on = false;
        if (!Settings.enabled || !Settings.flag("zombieCullSortFast")) return;
        try {
            Class<?> k = Class.forName("zombie.iso.IsoWorld$CompScoreToPlayer");
            MethodHandle h = Game.method(k, "getScore", MethodType.methodType(float.class, zombie.characters.IsoZombie.class));
            if (h != null) {
                scoreClass = k;
                getScore = h.asType(MethodType.methodType(float.class, Object.class, Object.class));
                guard.on = true;
                return;
            }
        } catch (Throwable ignored) { }
        System.out.println("[PZButter] zombieCullSortFast: no IsoWorld score comparator, off");
    }

    /** Binds the score function to the game's comparator instance (first sight of it). */
    static void bind(Comparator<?> c) throws Throwable {
        final MethodHandle bound = getScore.bindTo(c);
        score = z -> (float) bound.invokeExact(z);
        stock = c;
    }

    /** The game's compare arithmetic on two scores: {@code sa < sb} is 1, {@code sa > sb} is -1, anything else (NaN too) is 0. */
    static int compareScores(float sa, float sb) {
        if (sa < sb) return 1;
        if (sa > sb) return -1;
        return 0;
    }

    /** Stored scores of one cull, by element identity (open addressing, load at most one half), filled as the sort asks. */
    static final class Memo implements Comparator<Object> {
        /** Tables larger than this (slots) are dropped after their cull instead of kept. */
        static final int MAX_KEPT = 1 << 16;
        Object[] keys = new Object[64];
        float[] vals = new float[64];
        int mask = 63, count;
        Score s;
        boolean used;

        private static int slot(Object z, int m) {
            int h = System.identityHashCode(z);
            return (h ^ (h >>> 15)) & m;
        }

        float get(Object z) throws Throwable {
            if (z == null) return s.of(z);   // the game's score decides what null does
            Object[] k = keys;
            int m = mask, i = slot(z, m);
            for (Object e; (e = k[i]) != null; i = (i + 1) & m) if (e == z) return vals[i];
            float v = s.of(z);               // may throw, as the game's does: nothing stored then
            misses++;
            if ((count + 1) * 2 > keys.length) grow();
            put(z, v);
            return v;
        }

        private void put(Object z, float v) {
            Object[] k = keys;
            int m = mask, i = slot(z, m);
            while (k[i] != null) i = (i + 1) & m;
            k[i] = z;
            vals[i] = v;
            count++;
        }

        private void grow() {
            Object[] ok = keys;
            float[] ov = vals;
            keys = new Object[ok.length * 2];
            vals = new float[ok.length * 2];
            mask = keys.length - 1;
            count = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != null) put(ok[i], ov[i]);
        }

        @Override
        public int compare(Object x, Object y) {
            try { return compareScores(get(x), get(y)); }
            catch (Throwable t) { throw CullSort.<RuntimeException>sneaky(t); }
        }

        void clear() {
            if (keys.length > MAX_KEPT) { keys = new Object[64]; vals = new float[64]; mask = 63; }
            else java.util.Arrays.fill(keys, null);
            count = 0;
            s = null;
            used = false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T { throw (T) t; }

    /** The one table reused by every cull. */
    static final Memo memo = new Memo();
    /** A cull this thread opened is running (set by begin, cleared by end). */
    static volatile boolean active;

    /** IsoWorld.sceneCullZombies entry (game thread, key on): opens the table. */
    static void begin() {
        if (!guard.on || Thread.currentThread() != FrameClock.mainThread) return;
        memo.clear();
        active = true;
    }

    /** IsoWorld.sceneCullZombies exit, also on a throw: counts the cull if the sort used the table, and empties it. */
    static void end() {
        if (!active) return;
        active = false;
        if (memo.used) sorts++;
        memo.clear();
    }

    /**
     * CompScoreToPlayer.compare: the stock answer from stored scores, or {@code NO_ANSWER} when the game's own compare should run
     * (not in a cull, not the game thread, not the game's comparator). The first call binds the score function to the game's instance.
     */
    static final int NO_ANSWER = 2;

    static int compare(Object self, Object a, Object b) throws Throwable {
        if (!active || !guard.on || self == null || self.getClass() != scoreClass) return NO_ANSWER;
        if (Thread.currentThread() != FrameClock.mainThread) return NO_ANSWER;
        if (stock == null) {
            bind((Comparator<?>) self);
            System.out.println("[PZButter] zombieCullSortFast: bound to the game's cull comparator");
        }
        Memo m = memo;
        m.s = score;
        int r = m.compare(a, b);
        m.used = true;
        return r;
    }

    static void resetForTest() {
        guard.on = false;
        scoreClass = null;
        getScore = null;
        stock = null;
        score = null;
        misses = sorts = 0;
        active = false;
        memo.clear();
    }
}
