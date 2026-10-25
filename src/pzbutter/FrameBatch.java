package pzbutter;

import java.util.function.IntConsumer;

/**
 * The frame worker pool (frameThreads): runs indexed tasks on PZ Butter's {@link LuaGate.Worker} threads (made with
 * {@link LuaGate#worker}) and the calling thread. Contract:
 * <ul>
 * <li>{@link #run}: {@code task.accept(i)} exactly once for each i in [0, n), on the workers and the calling thread; returns
 * when every index has ended; then rethrows the first Throwable a task threw (RuntimeExceptions and Errors pass through as
 * is, other Throwables are wrapped in a RuntimeException), the others counted in {@link #suppressed}.</li>
 * <li>The caller waits only through {@code LuaGate.awaitYielding(() -> done)} (a worker that reaches Lua gets the token);
 * the worker that ends the last index calls {@code LuaGate.wakeOwner()}.</li>
 * <li>{@code n < 2}, no pool, or a call while a batch runs (nested, or from another thread) runs inline on the calling thread.</li>
 * <li>{@link #start}: {@code width - 1} daemon worker threads named {@code "PZButter-frame-" + i}, idle between batches; a
 * start failure leaves no pool and calls {@code guard.fail}.</li>
 * <li>{@link #width}: 1 without a pool. {@link #stopForTest}: stops and joins the threads, no pool afterwards.</li>
 * </ul>
 */
final class FrameBatch {
    private FrameBatch() {}

    /** On while the pool exists. */
    static final Guard guard = new Guard("frameThreads");

    /** Task Throwables after the first one, counted. */
    static long suppressed;

    private static int pending;                                 // width to start at the first frame after the claim

    static void init() {
        int w = Settings.enabled ? Settings.num("frameThreads", 0, 0, 32) : 0;
        pending = w > 1 && Preload.active() && LuaGate.guard.on ? w : 0;
        guard.on = false;
    }

    /** Every frame (game thread) until the pool runs: the gate is claimed by the first frameYield, then the pool starts. */
    static void frameTick() {
        if (pending == 0 || !LuaGate.available()) return;
        int w = pending;
        pending = 0;
        start(w);
    }

    // ---- pool (variant A: packed-long ranges with stealing, semaphore wake, summed per-participant counts) ----

    private static final int STRIDE = 8;                        // longs per slot: [range lo<<32|hi, done count, padding]

    private static final class Batch {
        final int n, slots;
        final IntConsumer task;
        final java.util.concurrent.atomic.AtomicLongArray a;
        final java.util.concurrent.atomic.AtomicInteger ticket = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicLong supp = new java.util.concurrent.atomic.AtomicLong();

        Batch(int n, int width, IntConsumer task) {
            this.n = n; this.slots = width; this.task = task;
            a = new java.util.concurrent.atomic.AtomicLongArray(width * STRIDE);
            int w = width - 1, per = n / w, rem = n % w, lo = 0;
            for (int s = 1; s <= w; s++) {                      // slot 0 is the caller: it owns no range
                int hi = lo + per + (s <= rem ? 1 : 0);
                a.set(s * STRIDE, (long) lo << 32 | hi);
                lo = hi;
            }
        }

        boolean done() {
            long sum = 0;
            for (int s = 0; s < slots; s++) sum += a.get(s * STRIDE + 1);
            return sum == n;
        }

        /** Own front, one index; -1 when the own range is empty. */
        int takeOwn(int slot) {
            int at = slot * STRIDE;
            for (;;) {
                long r = a.get(at);
                int lo = (int) (r >>> 32), hi = (int) r;
                if (lo >= hi) return -1;
                if (a.compareAndSet(at, r, (long) (lo + 1) << 32 | hi)) return lo;
            }
        }

        /** Out of own work: a worker takes the back half of a victim's range, the caller (slot 0) a single index; -1 when nothing is left. */
        int steal(int slot) {
            for (int k = 1; k < slots; k++) {
                int v = (slot + k) % slots, at = v * STRIDE;
                for (;;) {
                    long r = a.get(at);
                    int lo = (int) (r >>> 32), hi = (int) r;
                    if (lo >= hi) break;
                    int mid = slot == 0 ? hi - 1 : (lo + hi) >>> 1;
                    if (!a.compareAndSet(at, r, (long) lo << 32 | mid)) continue;
                    if (mid + 1 < hi) a.set(slot * STRIDE, (long) (mid + 1) << 32 | hi);
                    return mid;
                }
            }
            return -1;
        }

        void work(int slot) {
            long cnt = 0;
            try {
                for (;;) {
                    int i = takeOwn(slot);
                    if (i < 0 && (i = steal(slot)) < 0) break;
                    try { task.accept(i); }
                    catch (Throwable t) { if (!err.compareAndSet(null, t)) supp.incrementAndGet(); }
                    cnt++;
                }
            } finally { a.set(slot * STRIDE + 1, cnt); }
            if (done()) LuaGate.wakeOwner();
        }
    }

    private static final java.util.concurrent.Semaphore wake = new java.util.concurrent.Semaphore(0);
    private static final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    private static Thread[] pool;
    private static volatile boolean stopping;
    private static Batch cur;                                   // plain: ordered by the semaphore's release/acquire

    private static void workerLoop() {
        while (!stopping) {
            try { wake.acquire(); } catch (InterruptedException e) { continue; }
            Batch b = cur;
            if (b == null) continue;
            int slot = 1 + b.ticket.getAndIncrement();
            if (slot < b.slots) b.work(slot);
        }
    }

    /** Starts width - 1 workers; sets guard.on when the pool runs. */
    static void start(int width) {
        if (width < 2 || pool != null) return;
        Thread[] ts = new Thread[width - 1];
        try {
            stopping = false;
            wake.drainPermits();
            for (int i = 0; i < ts.length; i++) {
                ts[i] = LuaGate.worker(FrameBatch::workerLoop, "PZButter-frame-" + i, i + 1);
                ts[i].start();
            }
        } catch (Throwable t) {
            stopping = true;
            wake.release(ts.length);
            for (Thread x : ts) if (x != null) x.interrupt();
            pool = null;
            guard.fail(t);
            return;
        }
        pool = ts;
        guard.on = true;
    }

    static int width() { Thread[] p = pool; return p == null ? 1 : p.length + 1; }

    static void run(int n, IntConsumer task) {
        Thread[] p = pool;
        if (n < 2 || p == null || !busy.compareAndSet(false, true)) { inline(n, task); return; }
        Throwable first;
        try {
            Batch b = new Batch(n, p.length + 1, task);
            cur = b;
            wake.release(p.length);
            b.work(0);
            if (!b.done()) LuaGate.awaitYielding(b::done);
            cur = null;
            first = b.err.get();
            suppressed += b.supp.get();
        } finally { busy.set(false); }
        if (first != null) rethrow(first);
    }

    private static void inline(int n, IntConsumer task) {
        Throwable first = null;
        for (int i = 0; i < n; i++) {
            try { task.accept(i); }
            catch (Throwable t) { if (first == null) first = t; else suppressed++; }
        }
        if (first != null) rethrow(first);
    }

    private static void rethrow(Throwable t) {
        if (t instanceof Error e) throw e;
        if (t instanceof RuntimeException e) throw e;
        throw new RuntimeException(t);
    }

    static void stopForTest() throws InterruptedException {
        Thread[] p = pool;
        pool = null;
        guard.on = false;
        if (p == null) return;
        stopping = true;
        wake.release(p.length);
        for (Thread t : p) t.interrupt();
        for (Thread t : p) t.join();
        stopping = false;
        wake.drainPermits();
        cur = null;
        for (Thread t : p) if (t instanceof LuaGate.Worker w) { Latch.onWorkerStop(w); }
    }
}
