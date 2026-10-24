package pzbutter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * logDedupe: a line printed again within a 3 s window is dropped and counted; the window's end yields one summary per repeated
 * message (SPEC 6.1). The first sight of a message always prints; so does anything with format arguments, anything that is not a
 * String, our own summaries, a message that arrives when the window already holds {@link #MAX_KEYS} others, and a repeat from
 * another stream or severity than the first.
 *
 * Any thread, no game classes. The path of a repeated line takes no lock: one table read and one atomic add. Counts are exact: the
 * roll closes each record with a sentinel that later adds see, so a line counted is in exactly one summary, and a line that loses
 * the race against the roll is printed instead of lost.
 */
final class LogDedupe {
    private LogDedupe() {}

    static final Guard guard = new Guard("logDedupe");
    static final long WINDOW_NS = 3_000_000_000L;
    static final int MAX_KEYS = 4096;
    static final String PREFIX = "[PZButter] logDedupe:";
    private static final long CLOSED = Long.MIN_VALUE / 2;
    private static final List<Object[]> EMPTY = List.of();

    /** One message of a window: where it was first printed and how often it repeated since. */
    private static final class Rec {
        final Object stream;
        final int severity, seq;
        final AtomicLong repeats = new AtomicLong();
        Rec(Object stream, int severity, int seq) { this.stream = stream; this.severity = severity; this.seq = seq; }
    }

    /** The lines of one window. {@code size} counts the reserved slots (the next record's {@code seq}). */
    static final class Window {
        volatile long start;
        final ConcurrentHashMap<String, Rec> map = new ConcurrentHashMap<>();
        final AtomicInteger size = new AtomicInteger();
        /** Set by the roll before it reads the table; first sights in flight (they hold {@code inserting}) are waited for. */
        volatile boolean closed;
        final AtomicInteger inserting = new AtomicInteger();
        Window(long start) { this.start = start; }
    }

    private static final AtomicReference<Window> cur = new AtomicReference<>();

    static void init() { guard.on = Settings.flag("logDedupe"); }

    /** {@code true}: skip the line. */
    static boolean drop(Object stream, int severity, Object msg, Object[] args, long nowNs) {
        if (!guard.on || args != null && args.length > 0 || !(msg instanceof String s) || s.startsWith(PREFIX)) return false;
        Window w = cur.get();
        if (w == null) {
            w = new Window(nowNs);
            if (!cur.compareAndSet(null, w)) w = cur.get();
        }
        return dropIn(w, stream, severity, s);
    }

    /** The count against a given window (a test reaches the race with a roll through this). */
    static boolean dropIn(Window w, Object stream, int severity, String s) {
        ConcurrentHashMap<String, Rec> map = w.map;
        Rec r = map.get(s);
        if (r == null) {
            if (w.size.get() >= MAX_KEYS) return false;        // full: the cheap exit for a flood of unique lines
            // a first sight: either the roll sees the record (it waits for us) or we see the roll and print the line unrecorded
            w.inserting.incrementAndGet();
            try {
                if (w.closed) return false;
                int seq = w.size.getAndIncrement();
                if (seq >= MAX_KEYS) { w.size.decrementAndGet(); return false; }
                r = map.putIfAbsent(s, new Rec(stream, severity, seq));
                if (r == null) return false;                   // first sight: printed
                w.size.decrementAndGet();                      // another thread recorded it first
            } finally { w.inserting.decrementAndGet(); }
        }
        if (r.stream != stream || r.severity != severity) return false;
        return r.repeats.incrementAndGet() > 0;                // negative: the window was summarised meanwhile, print the line
    }

    static boolean due(long nowNs) {
        Window w = cur.get();
        return w != null && nowNs - w.start >= WINDOW_NS;
    }

    private record Sum(Rec rec, String msg, long count) {}

    /** The window ended: the one caller that wins gets {stream, summary, severity ordinal as Integer} for every repeated message, in first-seen order. */
    static List<Object[]> roll(long nowNs) {
        Window w = cur.get();
        if (w == null || nowNs - w.start < WINDOW_NS || !cur.compareAndSet(w, new Window(nowNs))) return EMPTY;
        w.closed = true;
        while (w.inserting.get() != 0) Thread.onSpinWait();
        ArrayList<Sum> sums = null;
        for (Map.Entry<String, Rec> e : w.map.entrySet()) {
            long c = e.getValue().repeats.getAndSet(CLOSED);
            if (c <= 0) continue;
            if (sums == null) sums = new ArrayList<>();
            sums.add(new Sum(e.getValue(), e.getKey(), c));
        }
        if (sums == null) return EMPTY;
        sums.sort((a, b) -> Integer.compare(a.rec.seq, b.rec.seq));
        ArrayList<Object[]> out = new ArrayList<>(sums.size());
        for (Sum m : sums) out.add(new Object[] {m.rec.stream, summary(m.msg, m.count), m.rec.severity});
        return out;
    }

    static String summary(String msg, long repeats) {
        return PREFIX + " " + repeats + " more in 3 s: " + (msg.length() > 200 ? msg.substring(0, 200) : msg);
    }

    // ---- tests ----

    static Window window() { return cur.get(); }

    static void resetForTest() { cur.set(null); guard.on = false; }

    static void resetWindowStartForTest(long start) { Window w = cur.get(); if (w != null) w.start = start; }
}
