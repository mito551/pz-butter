package pzbutter;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

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
        int workerIndex;
    }

    /** Registered buffers (one per worker that has ever recorded), in first-use order. */
    private static final CopyOnWriteArrayList<Buf> bufs = new CopyOnWriteArrayList<>();
    private static int nextWorkerIndex;

    /** Volatile active batch id; 0 means no batch is running. */
    private static volatile int active;

    /** Cumulative count of buffers that had to be sorted, and of failed applies. */
    public static long unsorted;
    public static long failed;

    static void begin(int batchId) {
        active = batchId;
    }

    public static int applyAll() {
        Buf[] bs = bufs.toArray(new Buf[0]);
        for (Buf b : bs) {
            if (b.n <= 1) continue;
            boolean sorted = true;
            for (int k = 0; k < b.n - 1; k++) {
                if (b.order[k] > b.order[k + 1]) { sorted = false; break; }
            }
            if (!sorted) { unsorted++; stableSort(b); }
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
                    if (ow < oc) chosen = w;
                }
            }
            Buf buf = bs[chosen];
            int idx = cur[chosen];
            Apply op = buf.op[idx];
            Object a = buf.a[idx];
            Object b = buf.b[idx];
            int ii = buf.i[idx];
            float ff = buf.f[idx];
            try {
                op.apply(a, b, ii, ff);
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
        Integer[] idx = new Integer[b.n];
        for (int k = 0; k < b.n; k++) idx[k] = k;
        final Buf bb = b;
        Arrays.sort(idx, (x, y) -> Integer.compare(bb.order[x], bb.order[y]));
        int[] order = new int[b.n];
        Apply[] op = new Apply[b.n];
        Object[] a = new Object[b.n];
        Object[] bb2 = new Object[b.n];
        int[] ii = new int[b.n];
        float[] ff = new float[b.n];
        for (int k = 0; k < b.n; k++) {
            int pos = idx[k];
            order[k] = b.order[pos];
            op[k] = b.op[pos];
            a[k] = b.a[pos];
            bb2[k] = b.b[pos];
            ii[k] = b.i[pos];
            ff[k] = b.f[pos];
        }
        System.arraycopy(order, 0, b.order, 0, b.n);
        System.arraycopy(op, 0, b.op, 0, b.n);
        System.arraycopy(a, 0, b.a, 0, b.n);
        System.arraycopy(bb2, 0, b.b, 0, b.n);
        System.arraycopy(ii, 0, b.i, 0, b.n);
        System.arraycopy(ff, 0, b.f, 0, b.n);
    }

    static void record(int order, Apply op, Object a, Object b, int i, float f) {
        boolean activeNow = active != 0;
        Thread t = Thread.currentThread();
        boolean isWorker = t instanceof LuaGate.Worker;
        if (activeNow && isWorker) {
            LuaGate.Worker w = (LuaGate.Worker) t;
            Buf buf = w.latch;
                if (buf == null) {
                    buf = new Buf();
                    buf.workerIndex = nextWorkerIndex++;
                    w.latch = buf;
                    bufs.add(buf);
                }
                if (buf.n == buf.order.length) grow(buf);
                int n = buf.n++;
                buf.order[n] = order;
                buf.op[n] = op;
                buf.a[n] = a;
                buf.b[n] = b;
                buf.i[n] = i;
                buf.f[n] = f;
                return;
            }
        // apply at once (stock behaviour)
        try { op.apply(a, b, i, f); }
        catch (Throwable ignored) { }
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
