package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import se.krka.kahlua.vm.KahluaThread;

/**
 * luaWorkerGate: one reentrant Lua token. The game thread (the thread that called {@link #claim()}) holds it and frees
 * it only at yield points; a frame worker made by {@link #worker} that reaches Lua takes it, runs the stock body on its
 * own thread with the Lua owner fields and GameWindow.gameThread pointing at itself, and restores them on the
 * outermost exit. Every other thread passes through (stock). Waiters queue in arrival order.
 */
final class LuaGate {
    private LuaGate() {}

    /** Next slot for a worker created outside FrameBatch (tests may set slot directly instead). */
    private static final AtomicInteger NEXT_SLOT = new AtomicInteger();

    static final Guard guard = new Guard("luaWorkerGate");

    /** A static Thread field to impersonate (GameWindow.gameThread; tests inject their own). */
    static VarHandle gameThreadSlot;
    /** The Kahlua threads whose debugOwnerThread is swapped (LuaManager.thread, LuaManager.debugthread); null elements are skipped. */
    static Supplier<KahluaThread[]> luaThreads;

    /** A frame worker: the only kind of thread the gate gates. The swap bookkeeping is its own (one writer). */
    static final class Worker extends Thread {
        int holds;
        Thread savedGame;
        boolean slotSwapped;
        KahluaThread[] swapped = new KahluaThread[4];
        Thread[] saved = new Thread[4];
        int swappedCount;
        /** Scratch copies of the listed static fields, by k (see {@link Scratch}); touched only by this thread. */
        Object[] scratch;
        /** Latch buffer (see {@link Latch}); touched only by this thread during a batch and by the game thread in applyAll. */
        Latch.Buf latch;
        /** Fixed slot of this worker (FrameBatch pool slot, or the next slot for a worker created outside a batch). Set before start(). */
        int slot;
        boolean warned;

        Worker(Runnable r, String name) { this(r, name, NEXT_SLOT.getAndIncrement()); }
        Worker(Runnable r, String name, int slot) { super(r, name); setDaemon(true); this.slot = slot; }

        /** However the body ends, a hold it still has is given back: the game thread must never wait for a dead owner. */
        @Override public void run() {
            try { super.run(); }
            finally { abandon(this); }
        }
    }

    // ---- the token: guarded by M; owner == null means free; UNCLAIMED means nobody holds it yet (workers wait) ----

    private static final Object M = new Object();
    private static final Thread UNCLAIMED = new Thread("PZButter-luagate-unclaimed");   // never started
    private static Thread owner = UNCLAIMED;
    private static final ArrayDeque<Thread> queue = new ArrayDeque<>();
    private static volatile Thread holder;
    private static volatile int nWaiting;                       // workers in the queue (the holder's own place is not counted)

    /** The wait after which a thread logs once (SPEC constant; a test shortens it). */
    static long warnNs = 2_000_000_000L;
    private static boolean holderWarned;                    // the game thread's 2 s line: once, like Worker.warned
    static final long PARK_SLICE_NS = 100_000L;
    /** The stat line period (a test shortens it). */
    static long statNs = 60_000_000_000L;

    // counters
    static long gatedCalls, waitNs, maxWaitNs, strays;
    private static final ArrayList<String> strayNames = new ArrayList<>();
    private static long lastStatNs, lastCalls, lastWaitNs, lastStrays;

    static void init() {
        guard.on = Settings.flag("luaWorkerGate");
        if (!guard.on) return;
        gameThreadSlot = Game.staticField(zombie.GameWindow.class, "gameThread", Thread.class);
        luaThreads = () -> new KahluaThread[] {zombie.Lua.LuaManager.thread, zombie.Lua.LuaManager.debugthread};
        if (gameThreadSlot == null) {
            guard.on = false;
            System.out.println("[PZButter] luaWorkerGate: no GameWindow.gameThread, off");
        }
    }

    // ---- worker side ----

    static Thread worker(Runnable r, String name) { return new Worker(r, name); }
    static Thread worker(Runnable r, String name, int slot) { return new Worker(r, name, slot); }

    static boolean enter(KahluaThread kt) {
        if (!guard.on) return false;
        Thread t = Thread.currentThread();
        if (!(t instanceof Worker w)) {
            if (kt != null && kt.debugOwnerThread != t) stray(t);
            return false;
        }
        if (w.holds > 0) {                                      // nested: the token and the first swap are ours already
            if (kt != null) swap(w, kt);
            w.holds++;
            return true;
        }
        take(w);
        try {
            w.holds = 1;
            Supplier<KahluaThread[]> s = luaThreads;
            if (s != null) for (KahluaThread k : s.get()) if (k != null) swap(w, k);
            if (kt != null) swap(w, kt);
            VarHandle slot = gameThreadSlot;
            if (slot != null) {
                w.savedGame = (Thread) slot.get();
                w.slotSwapped = true;
                slot.set(w);
            }
        } catch (Throwable x) {
            w.holds = 0;
            try { restore(w); } finally { release(w); }
            throw x;
        }
        gatedCalls++;
        return true;
    }

    static void exit() {
        if (!(Thread.currentThread() instanceof Worker w) || w.holds == 0) return;
        if (--w.holds > 0) return;
        try { restore(w); } finally { release(w); }
    }

    /** A worker's thread ended (or died) still holding: restore what it swapped and free the token. */
    private static void abandon(Worker w) {
        if (w.holds == 0) return;
        w.holds = 0;
        try { restore(w); } finally { release(w); }
    }

    /** Remembers k's owner once per outermost hold and makes the worker its owner. */
    private static void swap(Worker w, KahluaThread k) {
        for (int i = 0; i < w.swappedCount; i++) if (w.swapped[i] == k) return;
        if (w.swappedCount == w.swapped.length) {
            w.swapped = java.util.Arrays.copyOf(w.swapped, w.swappedCount * 2);
            w.saved = java.util.Arrays.copyOf(w.saved, w.swappedCount * 2);
        }
        w.swapped[w.swappedCount] = k;
        w.saved[w.swappedCount] = k.debugOwnerThread;
        w.swappedCount++;
        k.debugOwnerThread = w;
    }

    /** Puts back every value the worker changed, in reverse order. */
    private static void restore(Worker w) {
        try {
            if (w.slotSwapped) {
                VarHandle slot = gameThreadSlot;
                Thread g = w.savedGame;
                w.slotSwapped = false;
                w.savedGame = null;
                if (slot != null) slot.set(g);
            }
        } finally {                                                 // the Lua owner fields go back even when the slot would not
            for (int i = w.swappedCount - 1; i >= 0; i--) {
                w.swapped[i].debugOwnerThread = w.saved[i];
                w.swapped[i] = null;
                w.saved[i] = null;
            }
            w.swappedCount = 0;
        }
    }

    private static void release(Thread t) {
        synchronized (M) {
            if (owner != t) return;
            owner = null;
            M.notifyAll();
        }
    }

    private static void stray(Thread t) {
        synchronized (M) {
            strays++;
            String n = t.getName();
            if (strayNames.size() < 4 && !strayNames.contains(n)) strayNames.add(n);
        }
    }

    /** Caller holds M: takes the token at once when it is free and nobody queues (true), else joins the queue (false). */
    private static boolean arriveLocked(Thread self) {
        if (owner == null && queue.isEmpty()) { owner = self; return true; }
        queue.addLast(self);
        if (self instanceof Worker) nWaiting++;
        return false;
    }

    /** Arrival and wait for the token in arrival order (caller does not hold M). */
    private static void take(Thread self) {
        boolean got;
        synchronized (M) { got = arriveLocked(self); }
        if (!got) awaitTurn(self);
    }

    /**
     * Waits (queued by {@link #arriveLocked}) until the token is free and self is first, then owns it. A thread that waited 2 s logs
     * once, outside the monitor. An interrupt does not end the wait; the flag is kept for the caller.
     */
    private static void awaitTurn(Thread self) {
        Worker w = self instanceof Worker x ? x : null;
        long t0 = System.nanoTime();
        boolean interrupted = false, taken = false, warned = w != null ? w.warned : holderWarned, warnNow = false;
        try {
            while (true) {
                synchronized (M) {
                    if (owner == null && queue.peekFirst() == self) {
                        queue.pollFirst();
                        if (w != null) nWaiting--;
                        owner = self;
                        taken = true;
                        if (w != null) {
                            long waited = System.nanoTime() - t0;
                            waitNs += waited;
                            if (waited > maxWaitNs) maxWaitNs = waited;
                        }
                        break;
                    }
                    long ms = 0;                                    // 0: until notified
                    if (!warned) {
                        long rem = warnNs - (System.nanoTime() - t0);
                        if (rem <= 0) warned = warnNow = true;
                        else ms = Math.max(1, rem / 1_000_000L);
                    }
                    if (!warnNow) try { M.wait(ms); } catch (InterruptedException e) { interrupted = true; }
                }
                if (warnNow) {
                    warnNow = false;
                    if (w != null) w.warned = true; else holderWarned = true;
                    System.out.println("[PZButter] luaWorkerGate: " + self.getName() + (w != null ? " waited 2 s for the game thread" : " waited 2 s for a frame worker"));
                }
            }
        } finally {
            if (!taken) {                                           // an Error out of the wait: leave the queue
                synchronized (M) {
                    queue.remove(self);
                    if (w != null) nWaiting--;
                    M.notifyAll();
                }
            }
            if (interrupted) self.interrupt();
        }
    }

    // ---- game-thread side ----

    static boolean claim() {
        Thread cur = Thread.currentThread();
        synchronized (M) {
            if (holder == cur) return true;
            if (holder != null) return false;
            holder = cur;
            owner = cur;
            lastStatNs = System.nanoTime();
            return true;
        }
    }

    /**
     * The holder waits for {@code done} with the token free. Not while another thread owns Lua (a world load): the token stays,
     * so no worker can swap the owner fields away from the loader. The loop looks again on every poll: once the game thread owns
     * Lua again (the load ended) the token is freed then, so a worker that queued meanwhile runs and {@code done} can come true.
     */
    static void awaitYielding(BooleanSupplier done) {
        Thread cur = Thread.currentThread();
        boolean mine;
        synchronized (M) {
            mine = holder == cur && owner == cur && ownsLua(cur);
            if (mine) { owner = null; M.notifyAll(); }
        }
        boolean interrupted = Thread.interrupted();                 // a set flag would end every park at once: keep it aside
        try {
            while (!done.getAsBoolean()) {
                if (!mine && holder == cur) {
                    synchronized (M) {
                        if (owner == cur && ownsLua(cur)) { owner = null; M.notifyAll(); mine = true; }
                    }
                }
                java.util.concurrent.locks.LockSupport.parkNanos(PARK_SLICE_NS);
                if (Thread.interrupted()) interrupted = true;
            }
        } finally {
            if (mine) take(cur);
            if (interrupted) cur.interrupt();
        }
    }

    static void wakeOwner() {
        Thread h = holder;
        if (h != null) java.util.concurrent.locks.LockSupport.unpark(h);
    }

    static int yieldPoint() {
        if (nWaiting == 0) return 0;
        Thread cur = Thread.currentThread();
        int n;
        boolean got;
        synchronized (M) {
            if (owner != cur || holder != cur || !ownsLua(cur)) return 0;
            n = nWaiting;
            if (n == 0) return 0;
            owner = null;
            M.notifyAll();
            got = arriveLocked(cur);                                // queues behind the n workers, in the same step as the release
        }
        if (!got) awaitTurn(cur);
        return n;
    }

    static int waiting() { return nWaiting; }

    static boolean ownsLua(Thread t) {
        Supplier<KahluaThread[]> s = luaThreads;
        if (s == null) return false;
        for (KahluaThread k : s.get()) if (k != null && k.debugOwnerThread != t) return false;
        return true;
    }

    /** frameStep entry (game thread). */
    static void frameYield() {
        if (!guard.on) return;
        Thread cur = Thread.currentThread();
        if (holder == null) claim();
        else if (cur == holder && nWaiting > 0 && ownsLua(cur)) yieldPoint();
        if (cur != holder) return;
        long now = System.nanoTime();
        if (now - lastStatNs >= statNs) {
            long elapsed = now - lastStatNs;
            lastStatNs = now;
            if (gatedCalls != lastCalls || strays != lastStrays) System.out.println(statLine(elapsed));
        }
    }

    static boolean available() { return guard.on && gameThreadSlot != null && holder != null; }

    /** What happened since the previous line (the minute), not since the start. */
    static String statLine(long elapsedNs) {
        String names;
        synchronized (M) { names = String.join(", ", strayNames); strayNames.clear(); }
        long calls = gatedCalls - lastCalls, wait = waitNs - lastWaitNs, st = strays - lastStrays, max = maxWaitNs;
        lastCalls = gatedCalls; lastWaitNs = waitNs; lastStrays = strays; maxWaitNs = 0;
        return String.format(Locale.ROOT, "[PZButter] luaWorkerGate: in %.0f s: %d gated calls, %.3f ms waited (max %.3f), %d stray off-thread Lua calls [%s]",
            elapsedNs / 1e9, calls, wait / 1e6, max / 1e6, st, names);
    }

    static void resetForTest() {
        synchronized (M) {
            holder = null;
            owner = UNCLAIMED;
            queue.clear();
            nWaiting = 0;
            gatedCalls = waitNs = maxWaitNs = strays = 0;
            strayNames.clear();
            lastStatNs = lastCalls = lastWaitNs = lastStrays = 0;
            gameThreadSlot = null;
            luaThreads = null;
            warnNs = 2_000_000_000L;
            holderWarned = false;
            statNs = 60_000_000_000L;
            guard.on = false;
            M.notifyAll();
        }
    }
}
