package pzbutter;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/**
 * entityTiming (ours, a diagnostic): the time per frame of the zombie update phases, so the 8b decision (the agent jar) rests on the
 * user's own numbers. Phases: the scheduler's classify ({@code startFrame}), update and postupdate, the model-picking cull, and the
 * players' sight pass. Only the outermost begin/end pair of a phase counts (updateLOS runs once per local player, so the sight total
 * sums them). Frames in which no phase ran (menus) are not sampled. Once a minute the percentiles go to the console; with frameLog
 * every sampled frame is a line in entity.out. Game thread only.
 */
final class EntityTiming {
    private EntityTiming() {}

    static final Guard guard = new Guard("entityTiming");

    static final int CLASSIFY = 0, UPDATE = 1, POSTUPDATE = 2, CULL = 3, SIGHT = 4, PHASES = 5;
    static final String[] NAMES = {"classify", "update", "postupdate", "cull", "sight"};

    /** Samples kept per minute (one per frame); past it frames are dropped and counted. */
    static final int CAPACITY = 1 << 16;
    static final long MINUTE_NS = 60_000_000_000L;

    static LogSink log;   // nullable

    private static final int[] depth = new int[PHASES];
    private static final long[] begun = new long[PHASES], total = new long[PHASES];
    private static boolean seen;
    private static int frameZombies;

    private static long[][] smp;
    private static int count, zombiesMax;
    private static long zombiesSum, dropped;
    private static long minuteStartNs;
    private static boolean started;

    static void init() {
        guard.on = Settings.flag("entityTiming");
        closeLog();
        if (guard.on && Settings.frameLog) {
            try {
                log = new LogSink(new File(System.getProperty("user.home"), "Zomboid/pzbutter/entity.out"), FrameClock.logLimit);
            } catch (Throwable t) {
                log = null;
                System.out.println("[PZButter] entity.out not opened: " + t);
            }
        }
    }

    /** A phase starts (nanoTime); nested starts of the same phase are part of the outer one. */
    static void begin(int phase, long now) {
        seen = true;
        if (depth[phase]++ == 0) begun[phase] = now;
    }

    /** A phase ends; an end without its begin (the key went on inside the call) is ignored. */
    static void end(int phase, long now) {
        if (depth[phase] <= 0) return;
        if (--depth[phase] == 0) total[phase] += now - begun[phase];
    }

    /** The frame's zombie count (the cell's list, read when the classify phase ends). */
    static void zombies(int n) { frameZombies = n; }

    /**
     * Frame end: stores the frame's phase totals as one sample and writes its entity.out line, then clears the frame. Every 60 s from the
     * first frame the minute line goes to the console and the samples are cleared. A frame with no phase seen stores nothing.
     */
    static void frameEnd(long nowNs, long epochMs, long frameNo) {
        if (!started) { started = true; minuteStartNs = nowNs; }
        if (seen) {
            try {
                if (count < CAPACITY) {
                    long[][] s = smp;
                    if (s == null) smp = s = new long[PHASES][CAPACITY];
                    for (int p = 0; p < PHASES; p++) s[p][count] = total[p];
                    count++;
                    zombiesSum += frameZombies;
                    if (frameZombies > zombiesMax) zombiesMax = frameZombies;
                } else dropped++;
                LogSink l = log;
                if (l != null) {
                    l.begin(epochMs).num(frameNo).num(frameZombies);
                    for (int p = 0; p < PHASES; p++) l.ms(total[p]);
                    l.end(epochMs);
                }
            } catch (Throwable t) { guard.fail(t); }
            seen = false;
            frameZombies = 0;
            Arrays.fill(depth, 0);
            Arrays.fill(total, 0);
        }
        if (nowNs - minuteStartNs >= MINUTE_NS) {
            minuteStartNs = nowNs;
            try {
                if (count > 0) {
                    System.out.println(minuteLine(count, zombiesSum / (double) count, zombiesMax, smp, count));
                    if (dropped > 0) System.out.println("[PZButter] entityTiming: " + dropped + " frames past " + CAPACITY + " a minute not sampled");
                }
            } catch (Throwable t) { guard.fail(t); }
            count = 0;
            zombiesSum = 0;
            zombiesMax = 0;
            dropped = 0;
        }
    }

    /**
     * The console line: ms p50/p95/max per phase over the first {@code count} entries of {@code samples[phase]} (ns), nearest rank
     * on a sorted copy (p95 is element ceil(0.95 n) - 1).
     */
    static String minuteLine(int frames, double zombiesAvg, int zombiesMax, long[][] samples, int count) {
        StringBuilder b = new StringBuilder(256);
        b.append("[PZButter] entityTiming: ").append(frames).append(" frames, zombies ").append(String.format(Locale.ROOT, "%.1f", zombiesAvg))
            .append('/').append(zombiesMax).append("; ms p50/p95/max:");
        for (int p = 0; p < PHASES; p++) {
            long[] a = Arrays.copyOf(samples[p], count);
            Arrays.sort(a);
            b.append(p == 0 ? " " : ", ").append(NAMES[p]);
            if (count == 0) { b.append(" 0.000/0.000/0.000"); continue; }
            b.append(' ').append(ms(a[rank(50, count)])).append('/').append(ms(a[rank(95, count)])).append('/').append(ms(a[count - 1]));
        }
        return b.toString();
    }

    /** Index of the nearest-rank percentile: ceil(pct * n / 100) - 1. */
    private static int rank(int pct, int n) { return (pct * n + 99) / 100 - 1; }

    private static String ms(long ns) { return String.format(Locale.ROOT, "%.3f", ns / 1e6); }

    /** The frame's sum so far for a phase (test accessor). */
    static long frameTotal(int phase) { return total[phase]; }

    /** Frames stored this minute (test accessor). */
    static int samples() { return count; }

    private static void closeLog() {
        LogSink l = log;
        log = null;
        if (l != null) try { l.close(); } catch (Throwable ignored) { }
    }

    static void resetForTest() {
        guard.on = false;
        closeLog();
        Arrays.fill(depth, 0);
        Arrays.fill(begun, 0);
        Arrays.fill(total, 0);
        seen = false;
        frameZombies = 0;
        smp = null;
        count = zombiesMax = 0;
        zombiesSum = dropped = 0;
        minuteStartNs = 0;
        started = false;
    }
}
