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
        recalcTests();
        cameraTests();
        wakeTests();
    }

    static void cameraTests() {
        publishStockTruncates();
        publishScreenPixelsZoomedIn();
        publishSmoothFractional();
        frameFactorIsStockAtLockRate();
        scaleKeepsShareOfStep();
        cameraOffIsStock();
        cameraFieldsBind();
        cameraDefaultsWritten();
        driveTraceLineFormat();
        driveTraceDefaultsWritten();
    }

    static void publishStockTruncates() {
        assert CameraPixels.publish(10.7f, 1f, false, false) == 10f;
        assert CameraPixels.publish(-10.7f, 1f, false, false) == -10f;
        assert CameraPixels.publish(10.7f, 0.5f, false, false) == 10f;   // cameraScreenPixels off: stock at any zoom
    }

    static void publishScreenPixelsZoomedIn() {
        assert CameraPixels.publish(10.7f, 0.5f, false, true) == 10.5f;
        assert CameraPixels.publish(10.7f, 2f, false, true) == 10f;      // zoomed out: stock
        assert CameraPixels.publish(10.7f, 1f, false, true) == 10f;
    }

    static void publishSmoothFractional() {
        assert CameraPixels.publish(10.7f, 0.5f, true, true) == 10.7f;
        assert CameraPixels.publish(10.7f, 1f, true, false) == 10.7f;
    }

    static void frameFactorIsStockAtLockRate() {
        assert Math.abs(CameraPixels.frameFactor(1_000_000_000L / 60, 60) - 1.0) < 1e-6;
        assert Math.abs(CameraPixels.frameFactor(1_000_000_000L / 120, 60) - 0.5) < 1e-6;   // 120 fps at a 60 lock: half a step
        assert CameraPixels.frameFactor(3 * (1_000_000_000L / 60), 60) == 1.0;              // slow frame: never more than stock
        assert CameraPixels.frameFactor(0, 60) == 1.0;                                       // no previous call
        assert CameraPixels.frameFactor(5_000_000L, 0) == 1.0;
    }

    static void scaleKeepsShareOfStep() {
        assert CameraPixels.scale(0f, 10f, 0.5) == 5f;
        assert CameraPixels.scale(4f, 10f, 1.0) == 10f;
        assert CameraPixels.scale(4f, 10f, 0.0) == 4f;
        assert CameraPixels.scale(10f, 4f, 0.25) == 8.5f;
    }

    static void cameraOffIsStock() {
        CameraPatches.resetForTest();
        Object notACamera = new Object();
        CameraPatches.onEnter(notACamera);
        CameraPatches.onExit(notACamera);
        assert !CameraPatches.bound;   // guards off: no field binding, nothing read or written
        CameraPatches.resetForTest();
    }

    static void cameraFieldsBind() {
        assert Game.field(zombie.iso.PlayerCamera.class, "rightClickXf", float.class) != null;
        assert Game.field(zombie.iso.PlayerCamera.class, "rightClickYf", float.class) != null;
    }

    static void cameraDefaultsWritten() {
        java.io.File f = SelfTest.tmp("camera.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\ndriveLookSmooth=false\n") : t;
        assert t.contains("\ncameraScreenPixels=false\n") : t;
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

    static void recalcTests() throws Throwable {
        memoSkipsSecondRecalc();
        memoMissesOnRoomChange();
        memoOutsideWindowNeverSkips();
        memoClearedOnExit();
        memoClearedOnThrow();
        memoOtherThreadNeverSkips();
        memoNestedKeepsWindow();
        memoOtherThreadCannotTakeWindow();
        memoOffIsStock();
        memoDefaultsWritten();
    }

    static void memoSkipsSecondRecalc() {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        assert RecalcMemo.skip(a, 5);
        RecalcMemo.exit();
        RecalcMemo.resetForTest();
    }

    static void memoMissesOnRoomChange() {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        assert !RecalcMemo.skip(a, 6);
        RecalcMemo.exit();
        RecalcMemo.resetForTest();
    }

    static void memoOutsideWindowNeverSkips() {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        assert !RecalcMemo.skip(a, 5);
        assert !RecalcMemo.skip(a, 5);
        RecalcMemo.resetForTest();
    }

    static void memoClearedOnExit() {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        RecalcMemo.exit();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        RecalcMemo.exit();
        RecalcMemo.resetForTest();
    }

    static void memoClearedOnThrow() throws Exception {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        // call exit() the way the OnExit does
        RecalcMemo.exit();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        RecalcMemo.exit();
        me.zed_0xff.zombie_buddy.Patch.OnExit x = StreamPatches.RecalcMemoWindow.class.getMethod("exit").getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
        assert x != null && x.onThrowable() == Throwable.class;
        RecalcMemo.resetForTest();
    }

    static void memoNestedKeepsWindow() {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        RecalcMemo.enter();
        RecalcMemo.exit();
        assert RecalcMemo.skip(a, 5);   // the nested exit did not end the window
        RecalcMemo.exit();
        assert !RecalcMemo.skip(a, 5);  // the outer exit did
        RecalcMemo.resetForTest();
    }

    static void memoOtherThreadCannotTakeWindow() throws Exception {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        Thread t = new Thread(() -> { RecalcMemo.enter(); RecalcMemo.exit(); });
        t.start();
        t.join();
        assert RecalcMemo.skip(a, 5);   // still this thread's window, memo intact
        RecalcMemo.exit();
        RecalcMemo.resetForTest();
    }

    static void memoOtherThreadNeverSkips() throws Exception {
        RecalcMemo.resetForTest();
        RecalcMemo.guard.on = true;
        Object a = new Object();
        RecalcMemo.enter();
        assert !RecalcMemo.skip(a, 5);
        boolean[] got = new boolean[2];
        Thread t = new Thread(() -> {
            got[0] = RecalcMemo.skip(a, 5);
            got[1] = RecalcMemo.skip(a, 5);
        });
        t.start();
        t.join();
        assert !got[0] && !got[1];
        RecalcMemo.exit();
        RecalcMemo.resetForTest();
    }

    static void memoOffIsStock() {
        RecalcMemo.resetForTest();
        Object a = new Object();
        RecalcMemo.enter();
        // guard off
        RecalcMemo.guard.on = false;
        assert !RecalcMemo.skip(a, 5);
        assert !RecalcMemo.skip(a, 5);
        // nothing recorded: done cleared anyway on exit; check count zero
        assert RecalcMemo.recalcRun == 0 && RecalcMemo.recalcSkipped == 0;
        RecalcMemo.exit();
        RecalcMemo.resetForTest();
    }

    static void memoDefaultsWritten() {
        java.io.File f = SelfTest.tmp("recalc.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nhandoffRecalcFast=false\n") : t;
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
        assert t.contains("\nhandoffRecalcFast=false\n") : t;
        assert t.contains("\ndriveLookSmooth=false\n") : t;
        assert t.contains("\ncameraScreenPixels=false\n") : t;
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

    static void wakeTests() throws Exception {
        wakeParkBounded();
        wakeReturnsOnJob();
        wakeJobBeforeSleep();
        wakeOffIsThreadSleep();
        wakeRewritesOnlySleeps();
        wakeTransformIgnoresOtherClasses();
        streamTimingKeepsOldestAdd();
        wakeDefaultsWritten();
    }

    static void wakeParkBounded() {
        StreamWake.resetForTest();
        StreamWake.guard.on = true;
        long t0 = System.nanoTime();
        try { StreamWake.sleep(30); } catch (InterruptedException e) { throw new RuntimeException(e); }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assert ms >= 25 && ms <= 60 : ms;
        StreamWake.resetForTest();
    }

    static void wakeReturnsOnJob() {
        StreamWake.resetForTest();
        StreamWake.guard.on = true;
        final long[] ret = new long[1];
        Thread th = new Thread(() -> {
            long t0 = System.nanoTime();
            try { StreamWake.sleep(140); } catch (InterruptedException e) { throw new RuntimeException(e); }
            ret[0] = (System.nanoTime() - t0) / 1_000_000L;
        });
        th.start();
        try { Thread.sleep(5); } catch (InterruptedException e) {}
        StreamWake.jobAdded();
        try { th.join(200); } catch (InterruptedException e) {}
        assert ret[0] <= 30 : ret[0];
        StreamWake.resetForTest();
    }

    static void wakeJobBeforeSleep() {
        StreamWake.resetForTest();
        StreamWake.guard.on = true;
        StreamWake.jobAdded();
        long t0 = System.nanoTime();
        try { StreamWake.sleep(140); } catch (InterruptedException e) { throw new RuntimeException(e); }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assert ms <= 5 : ms;
        StreamWake.resetForTest();
    }

    static void wakeOffIsThreadSleep() {
        StreamWake.resetForTest();
        StreamWake.guard.on = false;
        long t0 = System.nanoTime();
        try { StreamWake.sleep(20); } catch (InterruptedException e) { throw new RuntimeException(e); }
        long ms1 = (System.nanoTime() - t0) / 1_000_000L;
        assert ms1 >= 20 : ms1;
        StreamWake.resetForTest();
    }

    static void wakeRewritesOnlySleeps() {
        try {
            java.io.InputStream in = ClassLoader.getSystemResourceAsStream("zombie/iso/WorldStreamer.class");
            byte[] orig = in.readAllBytes(); in.close();
            byte[] tr = WakeTransform.transform(orig);
            assert tr != null;
            var cf = java.lang.classfile.ClassFile.of();
            var a = cf.parse(orig); var b = cf.parse(tr);
            assert a.methods().size() == b.methods().size();
            for (int k = 0; k < a.methods().size(); k++) {
                var ma = a.methods().get(k); var mb = b.methods().get(k);
                String name = ma.methodName().stringValue() + ma.methodType().stringValue();
                assert name.equals(mb.methodName().stringValue() + mb.methodType().stringValue()) : name;
                java.util.List<String> ia = invokes(ma), ib = invokes(mb);
                if (name.equals("threadLoop()V")) {
                    assert java.util.Collections.frequency(ia, "java/lang/Thread.sleep(J)V") == 4 : ia;
                    assert !ib.contains("java/lang/Thread.sleep(J)V") : ib;
                    assert java.util.Collections.frequency(ib, "pzbutter/StreamWake.sleep(J)V") == 4 : ib;
                    ia.removeIf(x -> x.equals("java/lang/Thread.sleep(J)V"));
                    ib.removeIf(x -> x.equals("pzbutter/StreamWake.sleep(J)V"));
                    assert ia.equals(ib) : name;
                } else {
                    assert codeText(ma).equals(codeText(mb)) : name;   // every other method unchanged
                }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static java.util.List<String> invokes(java.lang.classfile.MethodModel m) {
        java.util.List<String> r = new java.util.ArrayList<>();
        m.code().ifPresent(c -> { for (var e : c) if (e instanceof java.lang.classfile.instruction.InvokeInstruction i)
            r.add(i.owner().asInternalName() + "." + i.name().stringValue() + i.type().stringValue()); });
        return r;
    }

    static String codeText(java.lang.classfile.MethodModel m) {
        StringBuilder sb = new StringBuilder();
        m.code().ifPresent(c -> { for (var e : c) if (e instanceof java.lang.classfile.Instruction i) sb.append(i).append('\n'); });
        return sb.toString();
    }

    static void streamTimingKeepsOldestAdd() throws Exception {
        StreamWake.resetForTest();
        StreamWake.timing = true;
        StreamWake.jobAdded();
        Thread.sleep(20);
        StreamWake.jobAdded();   // a later add must not move the stamp
        StreamWake.pickup();
        assert StreamWake.latencyCount == 1 && StreamWake.latencyMaxNs >= 19_000_000L : StreamWake.latencyMaxNs;
        StreamWake.pickup();     // nothing waiting: no sample
        assert StreamWake.latencyCount == 1;
        StreamWake.resetForTest();
        StreamWake.jobAdded();   // timing off: nothing stamped
        StreamWake.pickup();
        assert StreamWake.latencyCount == 0;
        StreamWake.resetForTest();
    }

    static void wakeTransformIgnoresOtherClasses() {
        try {
            java.io.InputStream in = ClassLoader.getSystemResourceAsStream("java/lang/Object.class");
            byte[] orig = in.readAllBytes(); in.close();
            assert WakeTransform.transform(orig) == null;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static void wakeDefaultsWritten() {
        java.io.File f = SelfTest.tmp("wake.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nwake=false\n") : t;
        assert t.contains("\nstreamTiming=false\n") : t;
    }

    static void driveTraceLineFormat() {
        assert "1,2,3.000,4.000,5.000,6.000,7.000,8.000,9.000,10".equals(
            DriveTrace.line(1, 2, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10));
    }

    static void driveTraceDefaultsWritten() {
        java.io.File f = SelfTest.tmp("drivetrace.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\ndriveTrace=false\n") : t;
    }
}
