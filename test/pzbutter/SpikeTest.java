package pzbutter;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * spikeContext (Tempo 4.9): a frame over the threshold is one spike, classified by what the player was doing;
 * a 25-50 ms frame while moving right after a chunk load is a stream burst. classify() is pure; onFrame() reads
 * the player once per slow frame and touches nothing else.
 */
final class SpikeTest {
    private SpikeTest() {}

    static void run() throws Exception {
        classifyPrecedence();
        streamBurstCounted();
        offIsOneFlagCheck();
        sampleThreshold();
        collapseSkipsPlumbing();
        luaSlowFrameRateLimited();
        hsperfParsesCounters();
        hsperfBadMagic();
        hsperfLiveSelfTest();
        stallLineHasDeltas();
        SpikeContext.resetForTest();
    }

    // a hand-built perfdata file: 32-byte prologue, then entries with a name and a long
    static java.nio.ByteBuffer hsperf(java.util.Map<String, Long> values) {
        int n = values.size();
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1024).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 0xca); b.put(1, (byte) 0xfe); b.put(2, (byte) 0xc0); b.put(3, (byte) 0xc0);   // magic, big-endian bytes
        b.put(4, (byte) 1); b.put(5, (byte) 2); b.put(6, (byte) 0); b.put(7, (byte) 1);               // byte order, version, accessible
        b.putInt(8, 0); b.putInt(12, 0); b.putInt(16, 0); b.putInt(20, 0);
        b.putInt(24, n);
        int pos = 32;
        for (java.util.Map.Entry<String, Long> e : values.entrySet()) {
            byte[] name = e.getKey().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            int dataOff = 20 + name.length + 1;
            int len = ((dataOff + 8) + 7) & ~7;
            b.putInt(pos, len);
            b.putInt(pos + 4, 20);       // name offset
            b.putInt(pos + 8, 1);        // vector length
            b.putInt(pos + 12, 0);
            b.putInt(pos + 16, dataOff); // data offset
            for (int i = 0; i < name.length; i++) b.put(pos + 20 + i, name[i]);
            b.put(pos + 20 + name.length, (byte) 0);
            b.putLong(pos + dataOff, e.getValue());
            pos += len;
        }
        return b;
    }

    static void hsperfParsesCounters() {
        java.util.Map<String, Long> m = HsPerf.read(hsperf(java.util.Map.of("sun.rt.safepoints", 1234L, "sun.rt.safepointTime", 5678L)));
        assert m.get("sun.rt.safepoints") == 1234L && m.get("sun.rt.safepointTime") == 5678L : m;
    }

    static void hsperfBadMagic() {
        java.nio.ByteBuffer b = hsperf(java.util.Map.of("sun.rt.safepoints", 1L));
        b.putInt(0, 0);
        assert HsPerf.read(b).isEmpty();
    }

    // the live JVM's own file has the counter the probe reads
    static void hsperfLiveSelfTest() throws Exception {
        Path p = StallProbe.hsPerfFile();
        assert Files.exists(p) : "no perfdata for this JVM: " + p;
        java.util.Map<String, Long> m = HsPerf.read(java.nio.ByteBuffer.wrap(Files.readAllBytes(p)));
        assert m.containsKey("sun.rt.safepoints") : m.keySet();
    }

    // two snapshots: the line carries the deltas and the memory reading
    static void stallLineHasDeltas() {
        StallProbe.Sample prev = new StallProbe.Sample();
        prev.safepoints = 100; prev.safepointTime = 50; prev.safepointSyncTime = 10; prev.majorFaults = 7;
        prev.gc = java.util.Map.of("G1 Young Generation", 5L);
        StallProbe.Sample now = new StallProbe.Sample();
        now.safepoints = 110; now.safepointTime = 80; now.safepointSyncTime = 14; now.majorFaults = 9;
        now.gc = java.util.Map.of("G1 Young Generation", 8L);
        String line = StallProbe.line(1_000_000L, 52, prev, now, 1234);
        assert line.contains("52 ms late") && line.contains("safepoints +10") && line.contains("safepointTime +30")
            && line.contains("safepointSync +4") && line.contains("G1 Young Generation +3") && line.contains("majorFaults +2")
            && line.contains("1234 kB") : line;
        StallProbe.Sample none = new StallProbe.Sample();
        assert StallProbe.line(0, 60, none, none, -1).contains("n/a");
    }

    // a hitch starts at median + 10 ms and never below 25 ms
    static void sampleThreshold() {
        assert HitchSampler.shouldSample(30, 10);
        assert !HitchSampler.shouldSample(24, 5);
        assert !HitchSampler.shouldSample(19, 10);
    }

    // plumbing frames are skipped; the deepest three game frames come back in order
    static void collapseSkipsPlumbing() {
        StackTraceElement[] st = {
            new StackTraceElement("zombie.iso.IsoCell", "render", "IsoCell.java", 900),
            new StackTraceElement("zombie.iso.IsoGridSquare", "draw", "IsoGridSquare.java", 300),
            new StackTraceElement("zombie.core.Core", "logic", "Core.java", 120),
            new StackTraceElement("se.krka.kahlua.vm.KahluaThread", "luaMainloop", "KahluaThread.java", 55),
            new StackTraceElement("pzbutter.Guard", "fail", "Guard.java", 9),
            new StackTraceElement("java.lang.Thread", "run", "Thread.java", -1),
        };
        assert HitchSampler.collapse(st).equals("zombie.iso.IsoCell.render:900 zombie.iso.IsoGridSquare.draw:300 zombie.core.Core.logic:120")
            : HitchSampler.collapse(st);
    }

    // two 60 ms frames 200 ms apart print once; a 300 ms frame always prints
    static void luaSlowFrameRateLimited() {
        LuaTiming.resetSlowPrintForTest();
        long t = 5_000_000L;
        assert LuaTiming.allowSlowPrint(60, t);
        assert !LuaTiming.allowSlowPrint(60, t + 200);
        assert LuaTiming.allowSlowPrint(300, t + 200);
        LuaTiming.resetSlowPrintForTest();
    }

    // driving beats moving beats chunk beats menu; nothing recent is uncat
    static void classifyPrecedence() {
        assert SpikeContext.classify(true, 30, 100, 100, 750).equals("driving");
        assert SpikeContext.classify(true, 0, 100, 9999, 750).equals("moving");
        assert SpikeContext.classify(false, 0, 100, 9999, 750).equals("chunk");
        assert SpikeContext.classify(false, 0, 9999, 200, 750).equals("menu");
        assert SpikeContext.classify(false, 0, 9999, 9999, 750).equals("uncat");
    }

    // a 30 ms frame moving 300 ms after a chunk load is a burst; 60 ms is a spike, not a burst
    static void streamBurstCounted() throws Exception {
        Path out = Files.createTempDirectory("pzbutter-spike").resolve("spikes.out");
        SpikeContext.resetForTest();
        SpikeContext.guard.on = true;
        SpikeContext.seamOn = true;
        SpikeContext.seamMoving = true;
        SpikeContext.spikesPath = out;
        long t = 1_000_000L;
        SpikeContext.lastChunkMs = t - 300;
        SpikeContext.onFrame(t, 1, 30.0);
        assert SpikeContext.streamBurst == 1 : "30 ms, moving, chunk 300 ms ago: one burst";
        SpikeContext.onFrame(t, 2, 60.0);
        assert SpikeContext.streamBurst == 1 && SpikeContext.moving == 1 : "60 ms is a spike, not a burst";
        SpikeContext.lastChunkMs = t - 1000;                       // outside the 750 ms window
        SpikeContext.onFrame(t, 3, 30.0);
        assert SpikeContext.streamBurst == 1 : "outside the window, no burst";
        assert SpikeContext.stateReads == 3 : "the player is read once per slow frame";
        String line = new String(Files.readAllBytes(out));
        assert line.startsWith("1000000 2 60 moving\n") : "spikes.out: epoch frame ms context, got " + line;
        SpikeContext.resetForTest();
    }

    // key off: the frame step costs one flag check and reads no player state
    static void offIsOneFlagCheck() {
        SpikeContext.resetForTest();
        SpikeContext.seamOn = true;
        SpikeContext.seamMoving = true;
        long reads = SpikeContext.stateReads;
        SpikeContext.onFrame(1_000_000L, 1, 60.0);
        assert SpikeContext.stateReads == reads && SpikeContext.moving == 0 && SpikeContext.streamBurst == 0;
    }
}
