package pzbutter;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Frame clock. Log lines are formatted digit by digit straight into a byte[] (no String, no Formatter),
 * and the file is a FileChannel written from that array. The flush deadline rides on the epoch_ms
 * already read for the line, so the hot path takes no extra clock reading.
 */
public final class FrameClock {
    public static volatile boolean on;
    public static volatile Thread mainThread;
    public static long frameNo;
    public static double intervalMs, stepMs, cpuMs;

    static long logLimit = 50L << 20;
    static Sink log;   // nullable; close() declares IOException

    private static final ThreadMXBean MX = mx();
    private static final boolean CPU = MX != null && MX.isCurrentThreadCpuTimeSupported() && MX.isThreadCpuTimeEnabled();

    private static long startNs, startCpu, prevStartNs;
    private static boolean armed, havePrev;
    private static int failures;

    private FrameClock() {}

    private static ThreadMXBean mx() {
        try { return ManagementFactory.getThreadMXBean(); } catch (Throwable t) { return null; }
    }

    /** Buffered channel sink. Throws once closed so a dead log is counted as a failure. */
    static final class Sink implements Closeable {
        final File file;
        FileChannel ch;
        final byte[] b = new byte[1 << 16];
        final ByteBuffer bb = ByteBuffer.wrap(b);
        int n;
        long written, lastFlushMs;

        Sink(File f) throws IOException {
            file = f;
            ch = FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        void flushBuf() throws IOException {
            if (ch == null) throw new IOException("closed");
            if (n == 0) return;
            bb.limit(n).position(0);
            while (bb.hasRemaining()) ch.write(bb);
            written += n;
            n = 0;
        }

        void rotate() throws IOException {
            flushBuf();
            ch.close();
            ch = null;
            Files.move(file.toPath(), new File(file.getPath() + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING);
            ch = FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            written = 0;
        }

        void line(long epochMs, long frame, long iNs, long sNs, long cNs) throws IOException {
            if (ch == null) throw new IOException("closed");
            if (n > b.length - 128) flushBuf();
            byte[] a = b;
            int p = n;
            p = num(a, p, epochMs); a[p++] = ' ';
            p = num(a, p, frame); a[p++] = ' ';
            p = ms(a, p, iNs); a[p++] = ' ';
            p = ms(a, p, sNs); a[p++] = ' ';
            p = ms(a, p, cNs); a[p++] = '\n';
            n = p;
            if (written + p > logLimit) rotate();
            else if (epochMs - lastFlushMs >= 1000 || epochMs < lastFlushMs) { lastFlushMs = epochMs; flushBuf(); }
        }

        @Override public void close() throws IOException {
            FileChannel c = ch;
            if (c == null) return;
            try { flushBuf(); } finally { ch = null; c.close(); }
        }
    }

    // non-negative decimal, returns new position
    private static int num(byte[] a, int p, long v) {
        if (v < 0) { a[p++] = '-'; v = -v; }
        int len = 1;
        for (long t = v; t >= 10; t /= 10) len++;
        for (int i = p + len - 1; i >= p; i--) { a[i] = (byte) ('0' + v % 10); v /= 10; }
        return p + len;
    }

    // ns as milliseconds with 3 decimals, rounded half up on microseconds
    private static int ms(byte[] a, int p, long ns) {
        long us = Math.floorDiv(ns + 500, 1000L);
        if (us < 0) { a[p++] = '-'; us = -us; }
        p = num(a, p, us / 1000);
        a[p++] = '.';
        int f = (int) (us % 1000);
        a[p++] = (byte) ('0' + f / 100);
        a[p++] = (byte) ('0' + f / 10 % 10);
        a[p++] = (byte) ('0' + f % 10);
        return p;
    }

    static void init() {
        try {
            on = Settings.enabled;
            if (on && Settings.frameLog) openLog(new File(System.getProperty("user.home"), "Zomboid/pzbutter/frames.out"));
        } catch (Throwable t) { fail(t); }
    }

    public static void enter() {
        if (!on) return;
        try {
            Thread t = Thread.currentThread();
            Thread m = mainThread;
            if (m == null) mainThread = m = t;
            if (m != t) return;
            armed = true;
            startNs = System.nanoTime();
            if (CPU) startCpu = MX.getCurrentThreadCpuTime();
        } catch (Throwable t) { fail(t); }
    }

    public static void exit() {
        if (!on) return;
        try {
            if (!armed || Thread.currentThread() != mainThread) return;
            armed = false;
            long step = System.nanoTime() - startNs;
            long cpu = CPU ? MX.getCurrentThreadCpuTime() - startCpu : step;
            long interval = havePrev ? startNs - prevStartNs : 0;
            prevStartNs = startNs;
            havePrev = true;
            end(interval, step, cpu);
        } catch (Throwable t) { fail(t); }
    }

    static void end(long intervalNs, long stepNs, long cpuNs) {
        if (!on) return;
        try {
            frameNo++;
            intervalMs = intervalNs / 1e6;
            stepMs = stepNs / 1e6;
            cpuMs = cpuNs / 1e6;
            Sink s = log;
            if (s != null) s.line(System.currentTimeMillis(), frameNo, intervalNs, stepNs, cpuNs);
        } catch (Throwable t) { fail(t); }
    }

    static void openLog(File f) {
        try {
            closeLog();
            File p = f.getAbsoluteFile().getParentFile();
            if (p != null) p.mkdirs();
            log = new Sink(f);
        } catch (Throwable t) {
            log = null;
            System.out.println("[PZButter] frame log not opened: " + t);
        }
    }

    static void flush() {
        try {
            Sink s = log;
            if (s != null) s.flushBuf();
        } catch (Throwable t) { fail(t); }
    }

    private static void closeLog() {
        Sink s = log;
        log = null;
        if (s != null) try { s.close(); } catch (Throwable ignored) { }
    }

    private static void fail(Throwable t) {
        try {
            if (++failures >= 5) {
                on = false;
                closeLog();
                System.out.println("[PZButter] frame clock off after repeated failures: " + t);
            }
        } catch (Throwable ignored) { }
    }
}
