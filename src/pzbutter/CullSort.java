package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Comparator;

/**
 * zombieCullSortFast: {@code IsoWorld.sceneCullZombies} sorts its zombies with {@code IsoWorld.compScoreToPlayer}, whose compare works
 * out both zombies' relevance scores every time (about n log n compares, two scores each). Ours works each score out once, stores it
 * in an identity table, and hands the game's own {@code TimSort} a comparator with the stock compare arithmetic over the stored
 * floats. The comparison sequence, the answers (NaN gives 0) and so the result are the game's. Game thread only (the shims in
 * ZombiePatches pass every other thread through).
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

    /** Scores computed outside the stored range during a sort (an element the table does not hold). */
    static long misses;

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

    /** The comparator of one sort: scores stored by element identity (open addressing, load at most one half). */
    static final class Prepared implements Comparator<Object> {
        /** Tables larger than this (slots) are dropped after their sort instead of kept. */
        static final int MAX_KEPT = 1 << 16;
        Object[] keys = new Object[0];
        float[] vals = new float[0];
        int mask;
        boolean inUse;
        Score s;

        void load(Object[] a, int lo, int hi, Score score) throws Throwable {
            int n = hi - lo;
            int cap = Integer.highestOneBit(Math.max(2, n * 2 - 1)) << 1;
            if (keys.length < cap) { keys = new Object[cap]; vals = new float[cap]; }
            mask = cap - 1;
            s = score;
            for (int i = lo; i < hi; i++) {
                Object z = a[i];
                float v = score.of(z);
                if (z != null) put(z, v);
            }
        }

        private void put(Object z, float v) {
            Object[] k = keys;
            int m = mask, i = slot(z, m);
            for (Object e; (e = k[i]) != null; i = (i + 1) & m) if (e == z) { vals[i] = v; return; }
            k[i] = z;
            vals[i] = v;
        }

        private static int slot(Object z, int m) {
            int h = System.identityHashCode(z);
            return (h ^ (h >>> 15)) & m;
        }

        float get(Object z) {
            if (z != null) {
                Object[] k = keys;
                int m = mask;
                for (int i = slot(z, m); ; i = (i + 1) & m) {
                    Object e = k[i];
                    if (e == z) return vals[i];
                    if (e == null) break;
                }
            }
            misses++;
            try { return s.of(z); }
            catch (Throwable t) { throw CullSort.<RuntimeException>sneaky(t); }
        }

        @Override
        public int compare(Object x, Object y) {
            return compareScores(get(x), get(y));
        }

        void release() {
            if (keys.length > MAX_KEPT) { keys = new Object[0]; vals = new float[0]; }
            else java.util.Arrays.fill(keys, 0, Math.min(keys.length, mask + 1), null);
            s = null;
            inUse = false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T { throw (T) t; }

    /** The one table reused by every sort; a sort that starts while it is in use gets a table of its own. */
    private static final Prepared shared = new Prepared();

    /**
     * A comparator for sorting {@code a[lo..hi)} that calls {@code s.of} exactly once for each element of the range and answers
     * {@code compare(x, y)} as {@link #compareScores} of their stored scores. Pass it to {@link #done} after the sort.
     */
    static Comparator<Object> prepare(Object[] a, int lo, int hi, Score s) throws Throwable {
        Prepared p = shared.inUse ? new Prepared() : shared;
        p.inUse = true;
        try {
            p.load(a, lo, hi, s);
        } catch (Throwable t) {
            p.release();
            throw t;
        }
        return p;
    }

    /** Releases the stored scores and the element references (the arrays stay for the next sort). */
    static void done(Comparator<?> c) {
        if (c instanceof Prepared p) p.release();
    }

    static void resetForTest() {
        guard.on = false;
        scoreClass = null;
        getScore = null;
        stock = null;
        score = null;
        misses = 0;
        shared.release();
    }
}
