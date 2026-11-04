package pzbutter;

import java.io.File;
import java.util.concurrent.atomic.LongAdder;

import zombie.network.PacketTypes.PacketType;

/**
 * Per-packet-type counts for one side of a multiplayer run (D10).
 *
 * Lines go to Zomboid/pzbutter/net.out (client) or net-server.out (server), one
 * line per type per second:
 * {@code epoch side type packets_in packets_out bytes_in bytes_out}.
 * The server uses its own file because a local hosted server shares user.home
 * with the client and two LogSinks on one path would truncate each other.
 *
 * Counters are cumulative; a delta is computed once a second. Bytes for sent
 * packets are the writer position at endPacket, bytes for received packets the
 * packet's wire length, so the totals should sit close to the OS UDP counters.
 */
public final class NetStats {
    private NetStats() {}

    static final Guard guard = new Guard("netStats");
    public static volatile boolean on;
    static LogSink log;
    static long logLimit = 50L << 20;
    static String side = "client";
    static LongAdder[] inPackets, outPackets, inBytes, outBytes;
    static long[] prevInPackets, prevOutPackets, prevInBytes, prevOutBytes;
    static Thread thread;

    public static void init() {
        on = Settings.flag("netStats");
        if (!on) return;
        try {
            side = Side.server() ? "server" : "client";
            int n = PacketType.values().length;
            inPackets = adders(n);
            outPackets = adders(n);
            inBytes = adders(n);
            outBytes = adders(n);
            prevInPackets = new long[n];
            prevOutPackets = new long[n];
            prevInBytes = new long[n];
            prevOutBytes = new long[n];
            log = new LogSink(new File(new File(System.getProperty("user.home"), "Zomboid/pzbutter"),
                side.equals("server") ? "net-server.out" : "net.out"), logLimit);
            Thread t = new Thread(NetStats::loop, "PZButter net");
            t.setDaemon(true);
            thread = t;
            t.start();
        } catch (Throwable e) {
            fail(e);
        }
    }

    private static LongAdder[] adders(int n) {
        LongAdder[] a = new LongAdder[n];
        for (int i = 0; i < n; i++) a[i] = new LongAdder();
        return a;
    }

    /** Received packet of this type, with its wire length. */
    public static void in(PacketType type, int bytes) {
        if (!on || type == null || inPackets == null) return;
        int i = type.ordinal();
        if (i < 0 || i >= inPackets.length) return;
        inPackets[i].increment();
        if (bytes > 0) inBytes[i].add(bytes);
    }

    /** Sent packet of this type, with its wire length (0 when unknown). */
    public static void out(PacketType type, int bytes) {
        if (!on || type == null || outPackets == null) return;
        int i = type.ordinal();
        if (i < 0 || i >= outPackets.length) return;
        outPackets[i].increment();
        if (bytes > 0) outBytes[i].add(bytes);
    }

    /**
     * Per type: the change from prev to cur. A value below prev is a reset
     * (the counter wrapped or the file restarted), so that type's delta is cur.
     */
    public static long[] delta(long[] prev, long[] cur) {
        if (cur == null) return new long[0];
        long[] d = new long[cur.length];
        for (int i = 0; i < cur.length; i++) {
            long c = cur[i];
            d[i] = prev != null && i < prev.length && c >= prev[i] ? c - prev[i] : c;
        }
        return d;
    }

    static void loop() {
        while (on) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            if (!on) return;
            try { writeOnce(System.currentTimeMillis()); } catch (Throwable e) { fail(e); }
        }
    }

    /** One second of deltas; test seam and the loop's body. */
    public static void writeOnce(long epochMs) {
        if (!on || log == null) return;
        int n = PacketType.values().length;
        long[] cin = sums(inPackets), cout = sums(outPackets), cib = sums(inBytes), cob = sums(outBytes);
        long[] din = delta(prevInPackets, cin), dout = delta(prevOutPackets, cout);
        long[] dib = delta(prevInBytes, cib), dob = delta(prevOutBytes, cob);
        prevInPackets = cin; prevOutPackets = cout; prevInBytes = cib; prevOutBytes = cob;
        PacketType[] types = PacketType.values();
        try {
            for (int i = 0; i < n; i++) {
                if (din[i] == 0 && dout[i] == 0 && dib[i] == 0 && dob[i] == 0) continue;
                log.begin(epochMs).text(side).text(types[i].name())
                    .num(din[i]).num(dout[i]).num(dib[i]).num(dob[i]).end(epochMs);
            }
            log.flushBuf();
        } catch (java.io.IOException e) {
            fail(e);
        }
    }

    private static long[] sums(LongAdder[] a) {
        long[] s = new long[a.length];
        for (int i = 0; i < a.length; i++) s[i] = a[i].sum();
        return s;
    }

    public static void flush() {
        try { if (log != null) log.flushBuf(); } catch (Throwable e) { fail(e); }
    }

    public static void stop() {
        on = false;
        thread = null;
        LogSink l = log;
        log = null;
        if (l != null) try { l.close(); } catch (Throwable e) { guard.fail(e); }
        inPackets = outPackets = inBytes = outBytes = null;
    }

    static void fail(Throwable t) {
        guard.fail(t);
        stop();
    }

    static void resetForTest() {
        stop();
        prevInPackets = prevOutPackets = prevInBytes = prevOutBytes = null;
        side = "client";
    }
}
