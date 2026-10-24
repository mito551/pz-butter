package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

/**
 * gpuStats (ours, a diagnostic): once a minute, one {@code [PZButter] gpu:} line with what the Plan 9 keys did in that minute, to the
 * console and, with frameLog, appended to Zomboid/pzbutter/gpu.out. A key that is off (and never ran) prints "-" in place of its numbers.
 * Render thread, at each swap; the clock is the caller's, so the cadence is testable.
 */
final class GpuStats {
    private GpuStats() {}

    static final Guard guard = new Guard("gpuStats");
    static final long MINUTE_MS = 60_000L;

    /** Where the line goes (tests replace them). */
    static java.util.function.Consumer<String> out = System.out::println;
    static java.util.function.Consumer<String> file = GpuStats::appendToFile;

    private static final int SAVED = 0, WAITS = 1, STALLS = 2, UPLOADS = 3, BYTES = 4, REALLOCS = 5, PASSED = 6, RELEASED = 7, SKIPPED = 8,
        NAMES = 9, FBO = 10, UNIFORM = 11, SHADER = 12, MISMATCH = 13, SAMPLES = 14, N = 15;

    private static long[] last = new long[N];
    private static double lastSumMs;
    private static long nextMs;
    private static boolean started;

    static void init() {
        guard.on = Settings.flag("gpuStats");
        started = false;
        last = new long[N];
        lastSumMs = 0;
    }

    private static long[] snapshot() {
        long[] s = new long[N];
        s[SAVED] = PersistentVbo.saved; s[WAITS] = PersistentVbo.waits; s[STALLS] = PersistentVbo.stalls;
        s[UPLOADS] = UploadSizer.uploads; s[BYTES] = UploadSizer.bytes; s[REALLOCS] = UploadSizer.reallocs; s[PASSED] = UploadSizer.passed;
        s[RELEASED] = SmartRelease.released; s[SKIPPED] = SmartRelease.skipped;
        s[NAMES] = NoSync.namesPooled; s[FBO] = NoSync.fboAnswered; s[UNIFORM] = NoSync.uniformsAnswered; s[SHADER] = NoSync.programAnswered;
        s[MISMATCH] = NoSync.checker.mismatches;
        s[SAMPLES] = GpuTimer.samples;
        return s;
    }

    /** The line for the minute that ends at {@code nowMs}: the counters' totals minus the previous line's. */
    static String line(long nowMs) {
        long[] cur = snapshot();
        long[] d = new long[N];
        for (int i = 0; i < N; i++) d[i] = Math.max(0, cur[i] - last[i]);
        double ms = GpuTimer.sumMs - lastSumMs;
        last = cur;
        lastSumMs = GpuTimer.sumMs;
        boolean vbo = PersistentVbo.guard.on || cur[SAVED] != 0 || cur[WAITS] != 0;
        boolean up = UploadSizer.guard.on || cur[UPLOADS] != 0;
        boolean rel = SmartRelease.guard.on || cur[RELEASED] != 0 || cur[SKIPPED] != 0;
        boolean ns = NoSync.guard.on || cur[NAMES] + cur[FBO] + cur[UNIFORM] + cur[SHADER] != 0;
        boolean gpu = GpuTimer.guard.on || cur[SAMPLES] != 0;
        StringBuilder b = new StringBuilder("[PZButter] gpu: maps ");
        if (vbo) b.append(d[SAVED]).append(" saved, ").append(d[WAITS]).append(" waits, ").append(d[STALLS]).append(" stalls, slots ").append(PersistentVbo.slotCount);
        else b.append('-');
        b.append("; uploads ");
        if (up) b.append(d[UPLOADS]).append(" (").append(String.format(Locale.ROOT, "%.1f", d[BYTES] / 1048576.0)).append(" MiB, ")
            .append(d[REALLOCS]).append(" storage changes, ").append(d[PASSED]).append(" left to stock)");
        else b.append('-');
        b.append("; textures returned ");
        if (rel) b.append(d[RELEASED]).append(" (").append(d[SKIPPED]).append(" already pooled)");
        else b.append('-');
        b.append("; names pooled ");
        if (ns) b.append(d[NAMES]);
        else b.append('-');
        b.append("; answers ");
        if (ns) b.append("fbo ").append(d[FBO]).append(" uniform ").append(d[UNIFORM]).append(" shader ").append(d[SHADER]).append(" (").append(d[MISMATCH]).append(" mismatches)");
        else b.append('-');
        b.append("; gpu ");
        if (gpu && d[SAMPLES] > 0) b.append(String.format(Locale.ROOT, "%.1f", ms / d[SAMPLES])).append(" ms");
        else b.append('-');
        return b.toString();
    }

    /** Each swap: every 60 s from the first call, prints the line and writes it to gpu.out. */
    static void tick() { tick(System.nanoTime() / 1_000_000L); }

    static void tick(long nowMs) {
        if (!started) {
            started = true;
            nextMs = nowMs + MINUTE_MS;
            last = snapshot();
            lastSumMs = GpuTimer.sumMs;
            return;
        }
        if (nowMs < nextMs) return;
        nextMs = nowMs + MINUTE_MS;
        String l = line(nowMs);
        out.accept(l);
        if (Settings.frameLog) file.accept(l);
    }

    private static void appendToFile(String l) {
        File f = new File(System.getProperty("user.home"), "Zomboid/pzbutter/gpu.out");
        f.getParentFile().mkdirs();
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(System.currentTimeMillis() + " " + l + "\n");
        } catch (Throwable t) { guard.fail(t); }
    }

    static void resetForTest() {
        guard.on = false;
        started = false;
        last = new long[N];
        lastSumMs = 0;
        nextMs = 0;
        out = System.out::println;
        file = GpuStats::appendToFile;
    }
}
