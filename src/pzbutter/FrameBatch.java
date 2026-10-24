package pzbutter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * The frame worker pool (frameThreads): runs indexed tasks on PZ Butter's {@link LuaGate.Worker} threads and the calling thread,
 * one hand-off per batch. The caller waits through the Lua gate, so a worker that reaches Lua gets the token. A call while a batch
 * runs (nested, or from another thread) runs inline.
 */
final class FrameBatch {
    private FrameBatch() {}

    /** On while the pool exists. */
    static final Guard guard = new Guard("frameThreads");

    /** One batch: workers and the caller take indices from {@code next}; {@code done} reaches n when the last task ended. */
    private static final class Batch {
        final int n;
        final IntConsumer task;
        final long gen;
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger(), more = new AtomicInteger();
        final AtomicReference<Throwable> first = new AtomicReference<>();

        Batch(int n, IntConsumer task, long gen) { this.n = n; this.task = task; this.gen = gen; }

        void work() {
            int i;
            while ((i = next.getAndIncrement()) < n) {
                try { task.accept(i); }
                catch (Throwable t) { if (!first.compareAndSet(null, t)) more.incrementAndGet(); }
                finally { if (done.incrementAndGet() == n) LuaGate.wakeOwner(); }
            }
        }
    }

    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile Batch cur;
    private static volatile boolean stop;
    private static Thread[] threads;
    private static int pending;                                 // width to start at the first frame after the claim
    private static long gen;                                    // written by the thread that holds busy
    /** Task failures after the first one of a batch (the first is rethrown). */
    static long suppressed;

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

    static void start(int width) {
        if (threads != null || width < 2) return;
        stop = false;
        Thread[] t = new Thread[width - 1];
        try {
            for (int i = 0; i < t.length; i++) {
                t[i] = LuaGate.worker(FrameBatch::loop, "PZButter-frame-" + i);
                t[i].start();
            }
        } catch (Throwable x) {
            stop = true;
            for (Thread w : t) if (w != null) LockSupport.unpark(w);
            guard.fail(x);
            return;
        }
        threads = t;
        guard.on = true;
    }

    private static void loop() {
        long seen = 0;
        while (!stop) {
            Batch b = cur;
            if (b != null && b.gen != seen) { seen = b.gen; b.work(); }
            else LockSupport.park();
        }
    }

    static int width() { Thread[] t = threads; return t == null ? 1 : t.length + 1; }

    /** task.accept(i) once for each i in [0, n); returns when all ended, then rethrows the first Throwable a task threw. */
    static void run(int n, IntConsumer task) {
        Thread[] t = threads;
        if (n < 2 || t == null || !busy.compareAndSet(false, true)) {
            for (int i = 0; i < n; i++) task.accept(i);
            return;
        }
        Batch b;
        try {
            b = new Batch(n, task, ++gen);
            cur = b;
            for (Thread w : t) LockSupport.unpark(w);
            b.work();
            if (b.done.get() < n) LuaGate.awaitYielding(() -> b.done.get() >= n);
        } finally { busy.set(false); }
        Throwable x = b.first.get();
        if (x == null) return;
        suppressed += b.more.get();
        if (x instanceof RuntimeException e) throw e;
        if (x instanceof Error e) throw e;
        throw new RuntimeException(x);
    }

    static void stopForTest() throws InterruptedException {
        Thread[] t = threads;
        threads = null;
        pending = 0;
        guard.on = false;
        stop = true;
        cur = null;
        if (t != null) for (Thread w : t) { LockSupport.unpark(w); w.join(5000); }
        suppressed = 0;
    }
}
