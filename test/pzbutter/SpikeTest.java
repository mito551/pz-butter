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
        SpikeContext.resetForTest();
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
