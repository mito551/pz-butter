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
        vehicleAnimSkipsThirdIdentical();
        vehicleAnimResumesOnChange();
        vehicleAnimNewTrackResets();
        vehicleAnimReaps();
        masterSwitchOffDisablesStreamKeys();
        vehicleAnimOnlyInPart();
        vehicleAnimOtherPlayerNeverSkips();
        vehicleAnimOtherThreadNeverSkips();
        vehicleAnimDefaultsWritten();
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
        zombie.util.CappedConcurrentQueue<Object> q1 = new zombie.util.CappedConcurrentQueue<>(10);
        for (int i = 0; i < 10; i++) q1.add(new Object());
        Object q2 = new Object();
        ChunkPacer.guard.on = true;
        ChunkPacer.queueRef = q1;
        ChunkPacer.inUpdate = true;
        ChunkPacer.enterThread = Thread.currentThread();
        ChunkPacer.divisor = 1;
        ChunkPacer.finishedThisFrame = 2;
        ChunkPacer.meanJoinNs = 10_000_000L;
        ChunkPacer.budgetNs = 1L;
        FrameClock.frameStartNs = System.nanoTime();
        assert ChunkPacer.onPoll(q1);
        assert !ChunkPacer.onPoll(q2);
        ChunkPacer.exit();
        assert !ChunkPacer.onPoll(q1);
        ChunkPacer.resetForTest();
    }

    static void pacingFlagClearedOnThrow() throws Exception {
        ChunkPacer.resetForTest();
        zombie.util.CappedConcurrentQueue<Object> q1 = new zombie.util.CappedConcurrentQueue<>(10);
        for (int i = 0; i < 10; i++) q1.add(new Object());
        ChunkPacer.guard.on = true;
        ChunkPacer.queueRef = q1;
        ChunkPacer.divisor = 1;
        ChunkPacer.meanJoinNs = 10_000_000L;
        ChunkPacer.budgetNs = 1L;
        FrameClock.frameStartNs = System.nanoTime();
        ChunkPacer.enter();
        ChunkPacer.finishedThisFrame = 2;
        assert ChunkPacer.onPoll(q1);
        ChunkPacer.exit();
        assert !ChunkPacer.onPoll(q1);
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

    static void vehicleAnimSkipsThirdIdentical() {
        VehicleFixes.resetForTest();
        Object player = new Object(), track = new Object(), skin = new Object();
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 1);
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 2);
        assert VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 3);
        VehicleFixes.resetForTest();
    }

    static void vehicleAnimResumesOnChange() {
        VehicleFixes.resetForTest();
        Object player = new Object(), track = new Object(), skin = new Object();
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 1);
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 2);
        assert VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 3);
        assert !VehicleFixes.decide(player, track, skin, 9f, 2f, 3f, 4);
        assert !VehicleFixes.decide(player, track, skin, 9f, 2f, 3f, 5);
        assert VehicleFixes.decide(player, track, skin, 9f, 2f, 3f, 6);
        VehicleFixes.resetForTest();
    }

    static void vehicleAnimNewTrackResets() {
        VehicleFixes.resetForTest();
        Object player = new Object(), track = new Object(), skin = new Object(), track2 = new Object();
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 1);
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 2);
        assert VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 3);
        assert !VehicleFixes.decide(player, track2, skin, 1f, 2f, 3f, 4);
        VehicleFixes.resetForTest();
    }

    static void vehicleAnimReaps() {
        VehicleFixes.resetForTest();
        Object player = new Object(), track = new Object(), skin = new Object();
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 0);
        Object other = new Object();
        VehicleFixes.decide(other, track, skin, 1f, 2f, 3f, 1900);
        assert !VehicleFixes.animStates.containsKey(player);
        assert !VehicleFixes.decide(player, track, skin, 1f, 2f, 3f, 1900);
        VehicleFixes.resetForTest();
    }

    static void vehicleAnimOnlyInPart() {
        VehicleFixes.resetForTest();
        VehicleFixes.animGuard.on = true;
        assert !VehiclePatches.onAnimUpdate(new Object());
        VehicleFixes.resetForTest();
    }

    static void vehicleAnimOtherPlayerNeverSkips() {
        VehicleFixes.resetForTest();
        Object p1 = new Object(), p2 = new Object();
        VehicleFixes.animGuard.on = true;
        VehicleFixes.currentPartPlayer = p1;
        VehicleFixes.currentPartThread = Thread.currentThread();
        assert !VehiclePatches.onAnimUpdate(p2);
        VehicleFixes.resetForTest();
    }

    static void vehicleAnimOtherThreadNeverSkips() throws Exception {
        VehicleFixes.resetForTest();
        Object p = new Object();
        VehicleFixes.animGuard.on = true;
        VehicleFixes.currentPartPlayer = p;
        VehicleFixes.currentPartThread = new Thread();
        assert !VehiclePatches.onAnimUpdate(p);
        VehicleFixes.resetForTest();
    }

    static void vehicleAnimDefaultsWritten() {
        java.io.File f = SelfTest.tmp("veh.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nvehicleAnimIdleSkip=false\n") : t;
    }

    static void masterSwitchOffDisablesStreamKeys() throws Exception {
        java.io.File f = SelfTest.tmp("masterstream.properties");
        f.delete();
        java.nio.file.Files.writeString(f.toPath(),
            "enabled=false\nchunkHandoffDivisor=5\nchunkHandoffFrameBudgetUs=100\nhotsaveIntervalSec=30\n");
        Settings.load(f);
        ChunkPacer.resetForTest();
        VehicleFixes.resetForTest();
        StreamPatches.init();
        assert !ChunkPacer.guard.on;
        assert !StreamPatches.hotsaveGuard.on;
        ChunkPacer.resetForTest();
        VehicleFixes.resetForTest();
    }
}