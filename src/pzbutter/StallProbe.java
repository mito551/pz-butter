package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * stallProbe (Tempo 4.9): a 2 ms sleeper outside the game threads. A sleep that returns 50 ms or more late means the
 * whole process was frozen; the line in Zomboid/pzbutter/stalls.out says how late, the safepoint and GC deltas since
 * the last stall, the major-fault delta and MemAvailable. Missing sources print n/a.
 */
final class StallProbe {
    private StallProbe() {}

    static final Guard guard = new Guard("stallProbe");
    static final long SLEEP_MS = 2, LATE_MS = 50;

    static volatile Path stallsPath;   // test seam / chosen file

    static final class Sample {
        long safepoints = -1, safepointTime = -1, safepointSyncTime = -1, majorFaults = -1;
        Map<String, Long> gc = Map.of();
    }

    static void init() {
        guard.on = Settings.flag("stallProbe");
        if (!guard.on) return;
        Thread t = new Thread(StallProbe::loop, "PZButter stall");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        Sample prev = new Sample();
        long prevNs = System.nanoTime();
        while (guard.on) {
            try { Thread.sleep(SLEEP_MS); } catch (InterruptedException e) { return; }
            try {
                long now = System.nanoTime();
                long late = (now - prevNs) / 1_000_000 - SLEEP_MS;
                prevNs = now;
                if (late < LATE_MS) continue;
                Sample next = sample();
                write(line(System.currentTimeMillis(), late, prev, next, memAvailableKb()));
                prev = next;
            } catch (Throwable t) { guard.fail(t); }
        }
    }

    private static Sample sample() {
        Sample s = new Sample();
        try {
            Map<String, Long> hs = HsPerf.read(ByteBuffer.wrap(Files.readAllBytes(hsPerfFile())));
            s.safepoints = hs.getOrDefault(HsPerf.SAFEPOINTS, -1L);
            s.safepointTime = hs.getOrDefault(HsPerf.SAFEPOINT_TIME, -1L);
            s.safepointSyncTime = hs.getOrDefault(HsPerf.SAFEPOINT_SYNC, -1L);
        } catch (Throwable ignored) { /* n/a */ }
        try {
            Map<String, Long> gc = new LinkedHashMap<>();
            for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) gc.put(b.getName(), b.getCollectionCount());
            s.gc = gc;
        } catch (Throwable ignored) { /* n/a */ }
        try {
            String stat = Files.readString(Paths.get("/proc/self/stat"));
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).trim().split("\\s+");
            s.majorFaults = Long.parseLong(f[9]);   // field 12, after pid and the parenthesised comm
        } catch (Throwable ignored) { /* n/a */ }
        return s;
    }

    static Path hsPerfFile() {
        return Paths.get("/tmp", "hsperfdata_" + System.getProperty("user.name", "?"), Long.toString(ProcessHandle.current().pid()));
    }

    static String line(long epochMs, long lateMs, Sample prev, Sample now, long memAvailableKb) {
        StringBuilder sb = new StringBuilder();
        sb.append(epochMs).append(' ').append(lateMs).append(" ms late; safepoints ").append(delta(prev.safepoints, now.safepoints))
            .append("; safepointTime ").append(delta(prev.safepointTime, now.safepointTime)).append(" ms")
            .append("; safepointSync ").append(delta(prev.safepointSyncTime, now.safepointSyncTime)).append(" ms")
            .append("; gc ").append(gcDelta(prev.gc, now.gc))
            .append("; majorFaults ").append(delta(prev.majorFaults, now.majorFaults))
            .append("; MemAvailable ").append(memAvailableKb < 0 ? "n/a" : memAvailableKb + " kB");
        return sb.toString();
    }

    private static String delta(long prev, long now) {
        return prev < 0 || now < 0 ? "n/a" : String.format(Locale.ROOT, "%+d", now - prev);
    }

    private static String gcDelta(Map<String, Long> prev, Map<String, Long> now) {
        if (now.isEmpty()) return "n/a";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            Long p = prev.get(e.getKey());
            sb.append(e.getKey()).append(' ')
                .append(p == null || e.getValue() == null ? "n/a" : String.format(Locale.ROOT, "%+d", e.getValue() - p));
        }
        return sb.toString();
    }

    private static long memAvailableKb() {
        try {
            for (String l : Files.readAllLines(Paths.get("/proc/meminfo"))) {
                if (l.startsWith("MemAvailable:")) return Long.parseLong(l.replaceAll("\\D", ""));
            }
        } catch (Throwable ignored) { /* n/a */ }
        return -1;
    }

    private static void write(String line) {
        try {
            Path f = stallsPath;
            if (f == null) f = new File(System.getProperty("user.home"), "Zomboid/pzbutter/stalls.out").toPath();
            Files.createDirectories(f.getParent());
            try (FileWriter w = new FileWriter(f.toFile(), true)) { w.write(line + "\n"); }
        } catch (Throwable t) { guard.fail(t); }
    }
}
