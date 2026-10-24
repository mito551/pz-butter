package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
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
        boolean warned;

        Worker(Runnable r, String name) { super(r, name); setDaemon(true); }
    }

    // ---- the token: guarded by M; owner == null means free; UNCLAIMED means nobody holds it yet (workers wait) ----

    private static final Object M = new Object();
    private static final Thread UNCLAIMED = new Thread("PZButter-luagate-unclaimed");   // never started
    private static Thread owner = UNCLAIMED;
    private static final ArrayDeque<Thread> queue = new ArrayDeque<>();
    private static volatile Thread holder;
    private static volatile int nWaiting;                       // workers in the queue (the holder's own place is not counted)

    static final long WARN_NS = 2_000_000_000L;
    static final long PARK_SLICE_NS = 100_000L;
    static final long STAT_NS = 60_000_000_000L;

    // counters
    static long gatedCalls, waitNs, maxWaitNs, strays;
    private static final ArrayList<String> strayNames = new ArrayList<>();
    private static long lastStatNs;

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
        synchronized (M) { takeLocked(w); }
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
            restore(w);
            release(w);
            throw x;
        }
        gatedCalls++;
        return true;
    }

    static void exit() {
        if (!(Thread.currentThread() instanceof Worker w) || w.holds == 0) return;
        if (--w.holds > 0) return;
        restore(w);
        release(w);
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
        VarHandle slot = gameThreadSlot;
        if (w.slotSwapped) {
            if (slot != null) slot.set(w.savedGame);
            w.slotSwapped = false;
            w.savedGame = null;
        }
        for (int i = w.swappedCount - 1; i >= 0; i--) {
            w.swapped[i].debugOwnerThread = w.saved[i];
            w.swapped[i] = null;
            w.saved[i] = null;
        }
        w.swappedCount = 0;
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

    /**
     * Takes the token for {@code self} (caller holds M): at once when it is free and nobody queues, else in arrival
     * order. A worker that waited 2 s logs once.
     */
    private static void takeLocked(Thread self) {
        if (owner == null && queue.isEmpty()) { owner = self; return; }
        Worker w = self instanceof Worker x ? x : null;
        queue.addLast(self);
        if (w != null) nWaiting++;
        long t0 = System.nanoTime();
        boolean interrupted = false, taken = false;
        try {
            while (!(owner == null && queue.peekFirst() == self)) {
                long ms = 0;                                        // 0: until notified
                if (w != null && !w.warned) {
                    long rem = WARN_NS - (System.nanoTime() - t0);
                    if (rem <= 0) {
                        w.warned = true;
                        System.out.println("[PZButter] luaWorkerGate: " + self.getName() + " waited 2 s for the game thread");
                    } else ms = Math.max(1, rem / 1_000_000L);
                }
                try { M.wait(ms); } catch (InterruptedException e) { interrupted = true; }
            }
            queue.pollFirst();
            if (w != null) nWaiting--;
            owner = self;
            taken = true;
            if (w != null) {
                long waited = System.nanoTime() - t0;
                waitNs += waited;
                if (waited > maxWaitNs) maxWaitNs = waited;
            }
        } finally {
            if (!taken) {                                           // an Error out of the wait: leave the queue
                queue.remove(self);
                if (w != null) nWaiting--;
                M.notifyAll();
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

    static void awaitYielding(BooleanSupplier done) {
        Thread cur = Thread.currentThread();
        boolean mine;
        synchronized (M) {
            mine = holder == cur && owner == cur;
            if (mine) { owner = null; M.notifyAll(); }
        }
        try {
            while (!done.getAsBoolean()) java.util.concurrent.locks.LockSupport.parkNanos(PARK_SLICE_NS);
        } finally {
            if (mine) synchronized (M) { takeLocked(cur); }
        }
    }

    static void wakeOwner() {
        Thread h = holder;
        if (h != null) java.util.concurrent.locks.LockSupport.unpark(h);
    }

    static int yieldPoint() {
        if (nWaiting == 0) return 0;
        Thread cur = Thread.currentThread();
        synchronized (M) {
            if (owner != cur || holder != cur) return 0;
            int n = nWaiting;
            if (n == 0) return 0;
            owner = null;
            M.notifyAll();
            takeLocked(cur);                                        // queues behind the n workers
            return n;
        }
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
        if (now - lastStatNs >= STAT_NS) {
            long elapsed = now - lastStatNs;
            lastStatNs = now;
            if (gatedCalls != 0 || strays != 0) System.out.println(statLine(elapsed));
        }
    }

    static boolean available() { return guard.on && gameThreadSlot != null && holder != null; }

    static String statLine(long elapsedNs) {
        String names;
        synchronized (M) { names = String.join(", ", strayNames); }
        return String.format(Locale.ROOT, "[PZButter] luaWorkerGate: %d gated calls, %.3f ms waited (max %.3f), %d stray off-thread Lua calls [%s]",
            gatedCalls, waitNs / 1e6, maxWaitNs / 1e6, strays, names);
    }

    static void resetForTest() {
        synchronized (M) {
            holder = null;
            owner = UNCLAIMED;
            queue.clear();
            nWaiting = 0;
            gatedCalls = waitNs = maxWaitNs = strays = 0;
            strayNames.clear();
            lastStatNs = 0;
            gameThreadSlot = null;
            luaThreads = null;
            guard.on = false;
            M.notifyAll();
        }
    }
}
