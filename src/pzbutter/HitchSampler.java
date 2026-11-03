package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * hitchSampler (Tempo 4.9): a watchdog outside the frame samples the game and render threads while a frame runs far
 * past the recent median, and writes one line per hitch to Zomboid/pzbutter/hitches.out. The frame step only stores
 * frameStartNs (already there); the watchdog reads it through a VarHandle so its loop is not hoisted.
 */
final class HitchSampler {
    private HitchSampler() {}

    static final Guard guard = new Guard("hitchSampler");

    static final int WINDOW = 120, MAIN_FRAMES = 3, RENDER_FRAMES = 4;
    static final long SAMPLE_MS = 3;

    static volatile Path hitchesPath;   // test seam / chosen file

    private static final VarHandle FRAME_START;
    static {
        try { FRAME_START = MethodHandles.lookup().findStaticVarHandle(FrameClock.class, "frameStartNs", long.class); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    private static final double[] recent = new double[WINDOW];   // shift register, oldest first
    private static int recentN;
    private static volatile double medianMs;

    static void init() {
        guard.on = Settings.flag("hitchSampler");
        if (!guard.on) return;
        Thread t = new Thread(HitchSampler::loop, "PZButter hitch");
        t.setDaemon(true);
        t.start();
    }

    /** running >= median + 10 and >= 25: a frame far past its recent median, and long enough to matter. */
    static boolean shouldSample(long runningMs, double medianMs) {
        return runningMs >= 25 && runningMs >= medianMs + 10;
    }

    /** The deepest three frames that are not plumbing, as Class.method:line. */
    static String collapse(StackTraceElement[] st) {
        return collapse(st, MAIN_FRAMES);
    }

    static String collapse(StackTraceElement[] st, int n) {
        if (st == null) return "";
        StringBuilder sb = new StringBuilder();
        int seen = 0;
        for (StackTraceElement e : st) {
            if (isPlumbing(e)) continue;
            if (seen > 0) sb.append(' ');
            sb.append(e.getClassName()).append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (++seen == n) break;
        }
        return sb.toString();
    }

    private static boolean isPlumbing(StackTraceElement e) {
        String c = e.getClassName(), m = e.getMethodName();
        return c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("jdk.") || c.startsWith("sun.")
            || c.equals("pzbutter.Guard")
            || (c.equals("se.krka.kahlua.vm.KahluaThread") && m.equals("luaMainloop"));
    }

    private static void loop() {
        long lastFrame = -1;
        boolean sampling = false, pendingPush = false;
        long nextSampleNs = 0;
        double peakMs = 0;
        String mainFrames = "", renderFrames = "";
        while (guard.on) {
            try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            try {
                if (!FrameClock.on) { sampling = false; lastFrame = -1; continue; }
                long frame = FrameClock.frameNo;
                if (frame != lastFrame) {
                    if (sampling) write(frame, peakMs, mainFrames, renderFrames);
                    sampling = false;
                    lastFrame = frame;
                    pendingPush = true;
                }
                if (pendingPush) { push(FrameClock.intervalMs); pendingPush = false; }   // one tick after the end, so intervalMs is the finished frame's
                long start = (long) FRAME_START.getOpaque();
                if (start == 0) continue;
                long now = System.nanoTime();
                double runningMs = (now - start) / 1e6;
                if (!sampling) {
                    if (shouldSample((long) runningMs, medianMs)) {
                        sampling = true;
                        peakMs = 0;
                        nextSampleNs = 0;
                    }
                }
                if (sampling) {
                    if (runningMs > peakMs) peakMs = runningMs;
                    if (now >= nextSampleNs) {
                        nextSampleNs = now + SAMPLE_MS * 1_000_000;
                        mainFrames = collapse(stack(FrameClock.mainThread));
                        renderFrames = collapse(stack(PresentLog.renderThread), RENDER_FRAMES);
                    }
                }
            } catch (Throwable t) { guard.fail(t); }
        }
    }

    private static StackTraceElement[] stack(Thread t) {
        try { return t == null ? null : t.getStackTrace(); } catch (Throwable x) { guard.fail(x); return null; }
    }

    /** One small copy per frame on the watchdog; the frame's own samples are the ones that matter. */
    private static void push(double ms) {
        if (recentN < WINDOW) recentN++;
        System.arraycopy(recent, 1, recent, 0, WINDOW - 1);
        recent[WINDOW - 1] = ms;
        double[] sorted = Arrays.copyOf(recent, recentN);
        Arrays.sort(sorted);
        medianMs = sorted[recentN / 2];
    }

    private static void write(long frameNo, double frameMs, String mainFrames, String renderFrames) {
        try {
            String line = System.currentTimeMillis() + " " + frameNo + " " + Math.round(frameMs) + "ms"
                + " lua=" + LuaTiming.currentFunction()
                + " waitRender=" + waitsForRender()
                + " main=" + mainFrames
                + " render=" + renderFrames;
            Path f = hitchesPath;
            if (f == null) f = new File(System.getProperty("user.home"), "Zomboid/pzbutter/hitches.out").toPath();
            Files.createDirectories(f.getParent());
            try (FileWriter w = new FileWriter(f.toFile(), true)) { w.write(line + "\n"); }
        } catch (Throwable t) { guard.fail(t); }
    }

    /** The game's own answer for the game thread waiting on a render state (SpriteRenderer). */
    private static boolean waitsForRender() {
        try { return zombie.core.SpriteRenderer.instance.isWaitingForRenderState(); }
        catch (Throwable t) { guard.fail(t); return false; }
    }
}
