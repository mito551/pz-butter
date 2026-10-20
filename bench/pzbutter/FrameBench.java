package pzbutter;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Per-frame cost of FrameClock.enter() + exit(), as the patch calls them: ns and allocated bytes per frame,
 * with the frame log off and on. Each case: one warm-up run, then RUNS timed runs; the median is reported.
 */
public final class FrameBench {
    static final int FRAMES = 500_000, RUNS = 7;
    static final com.sun.management.ThreadMXBean MX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("pzbutter-bench").toFile();
        Settings.enabled = true;
        Settings.frameLog = false;
        FrameClock.init();
        report("log off", null);
        report("log on ", dir);
        for (File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    static void report(String name, File logDir) {
        double[] ns = new double[RUNS], bytes = new double[RUNS];
        for (int r = -1; r < RUNS; r++) {
            if (logDir != null) FrameClock.openLog(new File(logDir, "frames" + r + ".out"));
            long n0 = FrameClock.frameNo;
            long a0 = MX.getCurrentThreadAllocatedBytes(), t0 = System.nanoTime();
            for (int i = 0; i < FRAMES; i++) { FrameClock.enter(); FrameClock.exit(); }
            long t1 = System.nanoTime(), a1 = MX.getCurrentThreadAllocatedBytes();
            if (!FrameClock.on || FrameClock.frameNo != n0 + FRAMES)
                throw new IllegalStateException("clock off or frames lost: " + (FrameClock.frameNo - n0));
            if (r >= 0) { ns[r] = (t1 - t0) / (double) FRAMES; bytes[r] = (a1 - a0) / (double) FRAMES; }
        }
        if (logDir != null) FrameClock.flush();
        Arrays.sort(ns); Arrays.sort(bytes);
        System.out.printf("%s: %7.1f ns/frame (min %.1f, max %.1f), %5.1f B/frame%n",
            name, ns[RUNS / 2], ns[0], ns[RUNS - 1], bytes[RUNS / 2]);
    }
}
