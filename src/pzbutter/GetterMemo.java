package pzbutter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * G2 — frame-stamped getter memo (low probability). A getter is pure within one frame; the first
 * caller computes, every later caller for the same key within the same frame reads the stored value.
 * Nothing is memoized across frame boundaries, so mutation inside a frame is still observed.
 */
public final class GetterMemo {
    private GetterMemo() {}

    public static boolean on = true;

    public static final class Entry { public volatile long frame; public volatile Object value; public volatile boolean have; }

    private static final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> T get(String key, long frame, java.util.function.Supplier<T> compute) {
        if (!on || key == null || compute == null) return compute.get();
        Entry e = entries.computeIfAbsent(key, k -> new Entry());
        synchronized (e) {
            if (e.have && e.frame == frame) return (T) e.value;
            T v = compute.get();
            e.value = v;
            e.frame = frame;
            e.have = true;
            return v;
        }
    }

    public static void clear(String key) { entries.remove(key); }
    public static void clearAll() { entries.clear(); }

    static void init() {
        try { on = Settings.enabled; } catch (Throwable t) { on = false; }
    }

    public static void run() {
        GetterMemo.on = true;
        long f = 1;
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Object a = GetterMemo.get("k", f, () -> { calls.incrementAndGet(); return "v"; });
        Object b = GetterMemo.get("k", f, () -> { calls.incrementAndGet(); return "v"; });
        assert "v".equals(a) && "v".equals(b);
        assert calls.get() == 1;
        Object c = GetterMemo.get("k", f + 1, () -> "u");
        assert "u".equals(c);
        GetterMemo.clearAll();
    }
}
