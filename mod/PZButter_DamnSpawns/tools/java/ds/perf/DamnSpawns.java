package ds.perf;

import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.iso.IsoGridSquare;

/**
 * Native target-set fast path for DAMN forced vehicle spawns.
 *
 * LoadGridsquare fires for every streamed square, so the per-square check must be one
 * cheap call: dsSpawnsIsTargetSquare(square) answers "is this a spawn square?" without
 * Lua touching getX/getY or building a key. Only target squares reach the Lua logic.
 *
 * The set is an immutable open-addressing table of packed (x,y) longs, replaced
 * wholesale on change: reads are lock-free and allocation-free, writes are rare.
 */
public final class DamnSpawns {
    private static final Object LOCK = new Object();
    private static volatile long[] table = new long[16]; // 0 = empty; packed keys are never 0 (x > 0)
    private static volatile int size;

    private DamnSpawns() {}

    private static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static int slot(long key, int mask) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
    }

    private static boolean contains(long key) {
        long[] t = table;
        int mask = t.length - 1;
        for (int i = slot(key, mask); ; i = (i + 1) & mask) {
            long v = t[i];
            if (v == 0) return false;
            if (v == key) return true;
        }
    }

    private static void insert(long[] t, long key) {
        int mask = t.length - 1;
        int i = slot(key, mask);
        while (t[i] != 0) i = (i + 1) & mask;
        t[i] = key;
    }

    @LuaMethod(name = "dsSpawnsRegister", global = true)
    public static void register(double x, double y) {
        long key = pack((int) Math.floor(x), (int) Math.floor(y));
        synchronized (LOCK) {
            if (contains(key)) return;
            long[] old = table;
            int cap = old.length;
            while ((size + 1) * 2 > cap) cap <<= 1; // keep load factor <= 0.5 so probes terminate
            long[] next = new long[cap];
            for (long v : old) if (v != 0) insert(next, v);
            insert(next, key);
            table = next;
            size++;
        }
    }

    @LuaMethod(name = "dsSpawnsIsTargetSquare", global = true)
    public static boolean isTargetSquare(IsoGridSquare square) {
        return square != null && contains(pack(square.getX(), square.getY()));
    }

    @LuaMethod(name = "dsSpawnsIsTarget", global = true)
    public static boolean isTarget(double x, double y) {
        return contains(pack((int) Math.floor(x), (int) Math.floor(y)));
    }

    @LuaMethod(name = "dsSpawnsCount", global = true)
    public static int count() {
        return size;
    }

    @LuaMethod(name = "dsSpawnsClear", global = true)
    public static void clear() {
        synchronized (LOCK) {
            table = new long[16];
            size = 0;
        }
    }
}
