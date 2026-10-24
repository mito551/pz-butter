package pzbutter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Event replay core (plan 8 calls it). A frame worker captures the events its Lua handlers would receive as
 * (order, call) into its own buffer, with no lock and no shared write after its first capture; once every worker has
 * finished, one thread replays them sorted by the caller's loop index, so the listeners see stock order. An order
 * belongs to one thread, so ties keep that thread's capture order.
 */
final class LuaReplay {
    private LuaReplay() {}

    /** One thread's captures: parallel arrays, grown by doubling, kept across replays. */
    private static final class Buf {
        final Thread owner = Thread.currentThread();
        int[] order = new int[64];
        Object[][] call = new Object[64][];
        int n;
    }

    private static final ThreadLocal<Buf> LOCAL = new ThreadLocal<>();
    private static final ArrayList<Buf> ALL = new ArrayList<>();

    /** Calls whose fire threw. Written by the replaying thread only. */
    static long failures;

    /** call[0] is the event name, the rest its arguments (kept by reference). order is the caller's loop index. */
    static void capture(int order, Object[] call) {
        Buf b = LOCAL.get();
        if (b == null) {
            b = new Buf();
            synchronized (ALL) { ALL.add(b); }
            LOCAL.set(b);
        }
        int n = b.n;
        if (n == b.order.length) {
            b.order = Arrays.copyOf(b.order, n << 1);
            b.call = Arrays.copyOf(b.call, n << 1);
        }
        b.order[n] = order;
        b.call[n] = call;
        b.n = n + 1;
    }

    /**
     * Fires every captured call once, by order then capture order, then empties the buffers (arrays kept). Runs on one
     * thread while no thread captures. A fire that throws is counted in failures and the replay goes on.
     */
    static int replay(Consumer<Object[]> fire) {
        Buf[] bufs;
        synchronized (ALL) { bufs = ALL.toArray(new Buf[0]); }
        int total = 0;
        for (Buf b : bufs) total += b.n;
        if (total == 0) { prune(bufs); return 0; }
        // key = order (signed, high half) | index into the flat call array (low half): sorting keeps one thread's ties in capture order
        long[] key = new long[total];
        Object[][] flat = new Object[total][];
        int j = 0;
        for (Buf b : bufs) {
            int[] o = b.order;
            Object[][] c = b.call;
            for (int i = 0, n = b.n; i < n; i++, j++) {
                key[j] = ((long) o[i] << 32) | j;
                flat[j] = c[i];
            }
        }
        for (Buf b : bufs) { Arrays.fill(b.call, 0, b.n, null); b.n = 0; }
        prune(bufs);
        Arrays.sort(key);
        for (long k : key) {
            try { fire.accept(flat[(int) k]); }
            catch (Throwable t) { failures++; }
        }
        return total;
    }

    /** Buffers of threads that ended are dropped (they are empty here). */
    private static void prune(Buf[] bufs) {
        boolean dead = false;
        for (Buf b : bufs) if (!b.owner.isAlive()) { dead = true; break; }
        if (!dead) return;
        synchronized (ALL) { ALL.removeIf(b -> b.n == 0 && !b.owner.isAlive()); }
    }

    static int pending() {
        Buf[] bufs;
        synchronized (ALL) { bufs = ALL.toArray(new Buf[0]); }
        int total = 0;
        for (Buf b : bufs) total += b.n;
        return total;
    }

    /** Discards every captured call. */
    static void clear() {
        Buf[] bufs;
        synchronized (ALL) { bufs = ALL.toArray(new Buf[0]); }
        for (Buf b : bufs) { Arrays.fill(b.call, 0, b.n, null); b.n = 0; }
        prune(bufs);
    }
}
