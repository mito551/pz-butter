package pzbutter;

import java.util.Arrays;

/** Ordered latch records: workers record, the game thread applies in order. */
final class Latch {
    private Latch() {}

    /** A static, non-capturing apply target. */
    @FunctionalInterface
    static interface Apply {
        void apply(Object a, Object b, int i, float f);
    }

    /** Parallel-array buffer belonging to one worker; grows in place, never shrinks. */
    static final class Buf {
        int[] order = new int[8];
        Apply[] op = new Apply[8];
        Object[] a = new Object[8];
        Object[] b = new Object[8];
        int[] i = new int[8];
        float[] f = new float[8];
        int n;
        /** The worker's fixed slot; set once when its thread is created. */
int slot;
    }

    /** Buffers indexed by worker slot. A worker's buffer lives at its slot from creation. */
    static Buf[] bySlot = new Buf[16];

    /** Called when a worker is created: stores its buffer at its slot. */
    static void register(int slot, Buf buf) {
        if (slot < 0) return;
        if (slot >= bySlot.length) {
            int c = bySlot.length;
            while (c <= slot) c *= 2;
            bySlot = Arrays.copyOf(bySlot, c);
        }
        bySlot[slot] = buf;
    }

    /** Clears the buffer registered for a slot (used on pool stop). */
    static void clearSlot(int slot) {
        if (slot >= 0 && slot < bySlot.length) bySlot[slot] = null;
    }

    /** Test reset: clears the whole registry. */
    static void resetForTest() {
        for (int i = 0; i < bySlot.length; i++) bySlot[i] = null;
        active = 0;
    }

    /** Volatile active batch id; 0 means no batch is running. */
    private static volatile int active;

    /** Cumulative count of buffers that had to be sorted, and of failed applies. */
    public static long unsorted;
    public static long failed;

    static void begin(int batchId) {
        active = batchId;
    }

    public static int applyAll() {
        // Collect only buffers that actually hold records.
        java.util.ArrayList<Buf> nonEmpty = new java.util.ArrayList<>();
        for (Buf b : bySlot) if (b != null && b.n > 0) nonEmpty.add(b);
        Buf[] bs = nonEmpty.toArray(new Buf[0]);
        for (Buf b : bs) {
            if (b.n <= 1) continue;
            boolean sorted = true;
            for (int k = 0; k < b.n - 1; k++) {
                if (b.order[k] > b.order[k + 1]) { sorted = false; break; }
            }
            if (!sorted) { unsorted++; stableSort(b); }
        }
        if (bs.length == 0) { active = 0; return 0; }
        if (bs.length == 1) {
            // Straight through, no merge.
            Buf b = bs[0];
            int applied = 0;
            Throwable first = null;
            for (int idx = 0; idx < b.n; idx++) {
                Apply op = b.op[idx];
                Object a = b.a[idx];
                Object bb = b.b[idx];
                int ii = b.i[idx];
                float ff = b.f[idx];
                try { op.apply(a, bb, ii, ff); }
                catch (Throwable t) { failed++; if (first == null) first = t; }
                finally { b.a[idx] = null; b.b[idx] = null; }
                applied++;
            }
            b.n = 0;
            active = 0;
            if (first != null) rethrow(first);
            return applied;
        }
        int total = 0;
        for (Buf b : bs) total += b.n;

        int[] cur = new int[bs.length];
        int applied = 0;
        Throwable first = null;
        while (applied < total) {
            int chosen = -1;
            for (int w = 0; w < bs.length; w++) {
                if (cur[w] >= bs[w].n) continue;
                if (chosen == -1) chosen = w;
                else {
                    int oc = bs[chosen].order[cur[chosen]];
                    int ow = bs[w].order[cur[w]];
                    int sc = bs[chosen].slot;
                    int sw = bs[w].slot;
                    if (ow < oc || (ow == oc && sw < sc)) chosen = w;
                }
            }
            Buf buf = bs[chosen];
            int idx = cur[chosen];
            Apply op = buf.op[idx];
            Object a = buf.a[idx];
            Object bb = buf.b[idx];
            int ii = buf.i[idx];
            float ff = buf.f[idx];
            try {
                op.apply(a, bb, ii, ff);
            } catch (Throwable t) {
                failed++;
                if (first == null) first = t;
            } finally {
                buf.a[idx] = null;
                buf.b[idx] = null;
            }
            cur[chosen]++;
            applied++;
        }
        for (Buf b : bs) b.n = 0;
        active = 0;
        if (first != null) rethrow(first);
        return applied;
    }

    private static void stableSort(Buf b) {
        for (int k = 1; k < b.n; k++) {
            int ko = b.order[k];
            Apply o = b.op[k];
            Object av = b.a[k];
            Object bv = b.b[k];
            int iv = b.i[k];
            float fv = b.f[k];
            int j = k;
            while (j > 0 && b.order[j - 1] > ko) {
                b.order[j] = b.order[j - 1];
                b.op[j] = b.op[j - 1];
                b.a[j] = b.a[j - 1];
                b.b[j] = b.b[j - 1];
                b.i[j] = b.i[j - 1];
                b.f[j] = b.f[j - 1];
                j--;
            }
            b.order[j] = ko;
            b.op[j] = o;
            b.a[j] = av;
            b.b[j] = bv;
            b.i[j] = iv;
            b.f[j] = fv;
        }
    }

    static void record(int order, Apply op, Object a, Object b, int i, float f) {
        if (active == 0) {
            op.apply(a, b, i, f);
            return;
        }
        Thread t = Thread.currentThread();
        if (!(t instanceof LuaGate.Worker w)) {
            op.apply(a, b, i, f);
            return;
        }
        Buf buf = w.latch;
        if (buf == null) {
            op.apply(a, b, i, f);
            return;
        }
        if (buf.n == buf.order.length) grow(buf);
        int n = buf.n++;
        buf.order[n] = order;
        buf.op[n] = op;
        buf.a[n] = a;
        buf.b[n] = b;
        buf.i[n] = i;
        buf.f[n] = f;
    }

    private static void grow(Buf b) {
        int c = b.order.length * 2;
        b.order = Arrays.copyOf(b.order, c);
        b.op = Arrays.copyOf(b.op, c);
        b.a = Arrays.copyOf(b.a, c);
        b.b = Arrays.copyOf(b.b, c);
        b.i = Arrays.copyOf(b.i, c);
        b.f = Arrays.copyOf(b.f, c);
    }

    private static void rethrow(Throwable t) {
        if (t instanceof Error e) throw e;
        if (t instanceof RuntimeException e) throw e;
        throw new RuntimeException(t);
    }
}
