package pzbutter;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * A line log written digit by digit into a byte[] (no String, no Formatter) and out through a FileChannel.
 * A line is begin(epochMs), then num/ms/fixed3 fields, then end(epochMs). Lines on disk lag at most a second;
 * past the limit the file becomes <name>.1 and a new one starts. Throws once closed, so callers count failures.
 */
final class LogSink implements Closeable {
    final File file;
    final long limit;
    FileChannel ch;
    final byte[] b = new byte[1 << 16];
    final ByteBuffer bb = ByteBuffer.wrap(b);
    int n;
    long written, lastFlushMs;

    LogSink(File f, long limit) throws IOException {
        file = f;
        this.limit = limit;
        File p = f.getAbsoluteFile().getParentFile();
        if (p != null) p.mkdirs();
        ch = open();
    }

    private FileChannel open() throws IOException {
        return FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    LogSink begin(long epochMs) throws IOException {
        if (ch == null) throw new IOException("closed");
        if (n > b.length - 256) flushBuf();   // a line is far shorter than 256 bytes
        n = num(b, n, epochMs);
        return this;
    }

    /** A space and up to 200 chars of s (ASCII; other chars become '?'). */
    LogSink text(String s) {
        b[n++] = ' ';
        for (int i = 0, m = Math.min(s.length(), 200); i < m; i++) { char c = s.charAt(i); b[n++] = (byte) (c >= 32 && c < 127 ? c : '?'); }
        return this;
    }

    LogSink num(long v) { b[n++] = ' '; n = num(b, n, v); return this; }

    /** ns as milliseconds with 3 decimals, rounded half up on microseconds. */
    LogSink ms(long ns) { return fixed3(Math.floorDiv(ns + 500, 1000L)); }

    /** v / 1000 with exactly 3 decimals. */
    LogSink fixed3(long v) {
        byte[] a = b;
        int p = n;
        a[p++] = ' ';
        if (v < 0) { a[p++] = '-'; v = -v; }
        p = num(a, p, v / 1000);
        a[p++] = '.';
        int f = (int) (v % 1000);
        a[p++] = (byte) ('0' + f / 100);
        a[p++] = (byte) ('0' + f / 10 % 10);
        a[p++] = (byte) ('0' + f % 10);
        n = p;
        return this;
    }

    void end(long epochMs) throws IOException {
        b[n++] = '\n';
        if (written + n > limit) rotate();
        else if (epochMs - lastFlushMs >= 1000 || epochMs < lastFlushMs) { lastFlushMs = epochMs; flushBuf(); }
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
        ch = open();
        written = 0;
    }

    @Override public void close() throws IOException {
        FileChannel c = ch;
        if (c == null) return;
        try { flushBuf(); } finally { ch = null; c.close(); }
    }

    // non-negative decimal, returns the new position
    private static int num(byte[] a, int p, long v) {
        if (v < 0) { a[p++] = '-'; v = -v; }
        int len = 1;
        for (long t = v; t >= 10; t /= 10) len++;
        for (int i = p + len - 1; i >= p; i--) { a[i] = (byte) ('0' + v % 10); v /= 10; }
        return p + len;
    }
}
