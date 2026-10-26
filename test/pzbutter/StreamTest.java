package pzbutter;

/** Plan 10a checks (streaming and vehicles). Run from SelfTest.main. */
final class StreamTest {
    private StreamTest() {}

    static void run() throws Exception {
        try { runAll(); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new Exception(t); }
    }

    static void runAll() throws Throwable {
        gloveBoxSkipsOnlyNullContainer();
        gloveBoxOffIsStock();
        streamDefaultsWritten();
        pacingTests();
        hotsaveMsClamps();
        hotsaveDefaultsWritten();
    }

    static void pacingTests() throws Throwable {
        pacingFirstJoinAlwaysRuns();
        pacingDivisorCaps();
        pacingBudgetPredictsCost();
        pacingNeverHoldsLongFrames();
        pacingBacklogDrains();
        pacingIgnoresOtherQueues();
        pacingIgnoresForeignJoins();
        pacingFlagClearedOnThrow();
        pacingOffIsStock();
        pacingDefaultsWritten();
    }

    static void pacingFirstJoinAlwaysRuns() {
        assert !ChunkPacer.hold(0, 50, 19, 5_000_000_000L, 9_000_000L, 8, 1_500_000L);
    }

    static void pacingDivisorCaps() {
        assert !ChunkPacer.hold(5, 40, 19, 0, 0, 8, 0);
        assert ChunkPacer.hold(6, 40, 40, 0, 0, 8, 0);
    }

    static void pacingBudgetPredictsCost() {
        assert ChunkPacer.hold(1, 10, 19, 1_000_000L, 600_000L, 0, 1_500_000L);
        assert !ChunkPacer.hold(1, 10, 19, 1_000_000L, 400_000L, 0, 1_500_000L);
    }

    static void pacingNeverHoldsLongFrames() {
        assert !ChunkPacer.hold(1, 10, 19, 1_000_000_001L, 600_000L, 0, 1_500_000L);
    }

    static void pacingBacklogDrains() {
        assert !ChunkPacer.hold(6, 17, 13, 0, 0, 8, 0);
        assert ChunkPacer.hold(6, 16, 19, 0, 0, 8, 0);
    }

    static void pacingIgnoresForeignJoins() throws Exception {
        ChunkPacer.resetForTest();
        ChunkPacer.guard.on = true;
        ChunkPacer.inUpdate = true;
        ChunkPacer.enterThread = Thread.currentThread();
        FrameClock.frameStartNs = 1;
        long meanBefore = ChunkPacer.meanJoinNs;
        long joinsBefore = ChunkPacer.joins;
        Thread t = new Thread(() -> { ChunkPacer.joinStart(); ChunkPacer.joinEnd(); });
        t.start();
        t.join();
        assert ChunkPacer.meanJoinNs == meanBefore;
        assert ChunkPacer.joins == joinsBefore;
        ChunkPacer.resetForTest();
    }

    static void pacingIgnoresOtherQueues() throws Exception {
        ChunkPacer.resetForTest();
        ChunkPacer.guard.on = true;
        Object q1 = new Object();
        Object q2 = new Object();
        ChunkPacer.queueRef = q1;
        ChunkPacer.inUpdate = true;
        ChunkPacer.enterThread = Thread.currentThread();
        FrameClock.frameStartNs = 1;
        assert !ChunkPacer.onPoll(q2);
        ChunkPacer.resetForTest();
    }

    static void pacingFlagClearedOnThrow() throws Exception {
        ChunkPacer.resetForTest();
        ChunkPacer.guard.on = true;
        ChunkPacer.enter();
        ChunkPacer.exit();
        assert !ChunkPacer.onPoll(ChunkPacer.queueRef == null ? new Object() : ChunkPacer.queueRef);
        me.zed_0xff.zombie_buddy.Patch.OnExit a = StreamPatches.ChunkUpdate.class.getMethod("exit").getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
        assert a != null && a.onThrowable() == Throwable.class;
        ChunkPacer.resetForTest();
    }

    static void pacingOffIsStock() {
        ChunkPacer.resetForTest();
        ChunkPacer.init();
        assert !ChunkPacer.guard.on;
        assert !ChunkPacer.onPoll(new Object());
        ChunkPacer.resetForTest();
    }

    static void pacingDefaultsWritten() {
        java.io.File f = SelfTest.tmp("pacing.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nchunkHandoffDivisor=0\n") : t;
        assert t.contains("\nchunkHandoffFrameBudgetUs=0\n") : t;
    }

    static void gloveBoxSkipsOnlyNullContainer() {
        assert VehicleFixes.skipGloveBox(true, true);
        assert !VehicleFixes.skipGloveBox(true, false);
        assert !VehicleFixes.skipGloveBox(false, true);
        assert !VehicleFixes.skipGloveBox(false, false);
    }

    static void gloveBoxOffIsStock() {
        VehicleFixes.resetForTest();
        assert !VehiclePatches.onGloveBox(new Object());
        assert VehicleFixes.gloveSkipped == 0;
        VehicleFixes.resetForTest();
    }

    static void streamDefaultsWritten() {
        java.io.File f = SelfTest.tmp("stream.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\ngloveBoxFix=false\n") : t;
    }

    static void hotsaveMsClamps() {
        assert StreamPatches.hotsaveMs(0) == 1_000L;
        assert StreamPatches.hotsaveMs(30) == 30_000L;
        assert StreamPatches.hotsaveMs(9999) == 600_000L;
        StreamPatches.hotsaveGuard.on = false;
        StreamPatches.init();
        assert !StreamPatches.hotsaveGuard.on;
    }

    static void hotsaveDefaultsWritten() {
        java.io.File f = SelfTest.tmp("hotsave.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nhotsaveIntervalSec=0\n") : t;
    }
}