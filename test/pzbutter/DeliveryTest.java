package pzbutter;

import static pzbutter.SelfTest.write;

/** Plan 2 (frame delivery) checks. Run from SelfTest.main; failures are AssertionErrors. */
final class DeliveryTest {
    private DeliveryTest() {}

    static void run() {
        settingsKeysOffByDefault();
        settingsNumClamped();
        settingsModeMasterOff();
        gameStaticLongMissing();
        stampsRoundTrip();
        stampsUnknownState();
        presentLineFormat();
        threadsLineFormat();
        limiterSleepsToSpin();
        limiterNoSleepInsideSpin();
        limiterOverdue();
        limiterUncapped();
        limiterBounded();
        limiterWakesOnQueue();
        idleWaitTimesOut();
        idleWaitWakes();
        latchTapKept();
        latchWheelSums();
        latchNoRequestNoRepoll();
        latchInputAge();
        gateSwapDone();
        gateBounded();
        holdFixedHighCap();
        holdLowCapUsesLearner();
        holdLateFrameNoWait();
        holdLearnBounded();
        holdDecays();
        holdCapChange();
        holdUncappedNone();
        adviceOnlyPublic();
    }

    static final SwapHold.Config HOLD = new SwapHold.Config(260, 260, 100, 60, 1000, 25, 20);
    static final long P60 = 1_000_000_000L / 60, P240 = 1_000_000_000L / 240;

    // frameQueue at a 240 cap: the fixed 2.6-frame hold, whatever was learned
    static void holdFixedHighCap() {
        long h = SwapHold.holdNs(P240, 240, true, 1_000_000L, HOLD);
        assert Math.abs(h - 10_833_333L) < 1_000 : h;
    }

    // below 100 fps the learned hold is used, within 60 %..260 % of a frame
    static void holdLowCapUsesLearner() {
        assert SwapHold.holdNs(P60, 60, true, 12_000_000L, HOLD) == 12_000_000L;
        assert SwapHold.holdNs(P60, 60, true, 1_000_000L, HOLD) == P60 * 60 / 100;
        assert SwapHold.holdNs(P240, 240, false, 3_000_000L, HOLD) == 3_000_000L;   // presentPacing alone: learner
    }

    static void holdLateFrameNoWait() {
        assert SwapHold.waitNs(50_000_000L, 10_000_000L, 20_000_000L) == 0;
        assert SwapHold.waitNs(25_000_000L, 10_000_000L, 20_000_000L) == 5_000_000L;
    }

    // one 4 s hitch does not push the hold past 260 % of a frame
    static void holdLearnBounded() {
        SwapHold.Learner l = new SwapHold.Learner(HOLD);
        l.observe(4_000_000_000L, 1_000_000_000L, P60);
        assert l.holdNs() <= P60 * 260 / 100 : l.holdNs();
    }

    // a late frame raises the hold at once; it falls only after 1 s of calm, by 25 % of a frame a second,
    // and not below 60 %
    static void holdDecays() {
        SwapHold.Learner l = new SwapHold.Learner(HOLD);
        long t = 1_000_000_000L;
        l.observe(30_000_000L, t, P60);
        long raised = l.holdNs();
        assert Math.abs(raised - (30_000_000L + P60 * 20 / 100)) < 1_000 : raised;
        for (long dt = P60; dt <= 3_000_000_000L; dt += P60) l.observe(5_000_000L, t + dt, P60);
        long at3 = l.holdNs();
        assert at3 > raised - 2 * P60 * 25 / 100 - 1_000_000L && at3 < raised - 2 * P60 * 25 / 100 + 1_000_000L : at3;
        for (long dt = 3_000_000_000L; dt <= 10_000_000_000L; dt += P60) l.observe(5_000_000L, t + dt, P60);
        assert Math.abs(l.holdNs() - P60 * 60 / 100) < 1_000 : l.holdNs();
    }

    // the cap drops from 240 to 60: the next hold uses the 60 cap's frame
    static void holdCapChange() {
        long hi = SwapHold.holdNs(P240, 240, true, 0, HOLD), lo = SwapHold.holdNs(P60, 60, true, 0, HOLD);
        assert Math.abs(hi - P240 * 26 / 10) < 1_000 && lo == P60 * 60 / 100;
    }

    static void holdUncappedNone() {
        assert SwapHold.holdNs(0, 0, true, 12_000_000L, HOLD) == 0;
    }

    // a key seen down by an earlier poll stays down after a re-poll that sees it up, until the swap
    static void latchTapKept() {
        boolean[] held = new boolean[256];
        boolean[] now = new boolean[256];
        now[30] = true;
        InputLatch.mergeDown(held, now);
        assert held[30];
        InputLatch.mergeDown(held, new boolean[256]);
        assert held[30];
        InputLatch.mergeDown(held, null);   // a state without keys yet: no change, no throw
        assert held[30];
    }

    static void latchWheelSums() {
        assert InputLatch.mergeWheel(120, -240) == -120 && InputLatch.mergeWheel(0, 0) == 0;
    }

    // a re-poll happens only when one was asked for and the state was already polled since the swap
    static void latchNoRequestNoRepoll() {
        assert !InputLatch.shouldRepoll(true, false);
        assert !InputLatch.shouldRepoll(false, true);   // the normal first poll does it
        assert InputLatch.shouldRepoll(true, true);
    }

    // input age: frame start minus the time the state in use was polled; unknown state: 0
    static void latchInputAge() {
        Object a = new Object(), b = new Object();
        InputLatch.notePoll(a, 1_000_000L);
        InputLatch.notePoll(b, 9_000_000L);
        assert InputLatch.ageAt(a, 6_500_000L) == 5_500_000L && InputLatch.ageAt(b, 11_000_000L) == 2_000_000L;
        assert InputLatch.ageAt(new Object(), 11_000_000L) == 0;
    }

    // the previous frame (7) is already on screen: no wait
    static void gateSwapDone() {
        assert InputLatch.gateWaitNs(20_000_000L, 7, 7, 10_000_000L, P120) == 0;
        assert InputLatch.gateWaitNs(20_000_000L, 8, 7, 10_000_000L, P120) == 0;
    }

    // frame 7 not shown yet: wait, but never past one period after frame 7 started
    static void gateBounded() {
        assert InputLatch.gateWaitNs(12_000_000L, 6, 7, 10_000_000L, P120) == 10_000_000L + P120 - 12_000_000L;
        assert InputLatch.gateWaitNs(30_000_000L, 6, 7, 10_000_000L, P120) == 0;
    }

    // nobody wakes it: back within the timeout (plus scheduling slack)
    static void idleWaitTimesOut() {
        long t0 = System.nanoTime();
        RenderIdle.park(RenderIdle.WAIT_NS);
        assert System.nanoTime() - t0 < RenderIdle.WAIT_NS + 5_000_000L;
    }

    // a hand-off (the game's notifyRenderStateQueue) wakes it before the timeout
    static void idleWaitWakes() {
        Thread waker = new Thread(() -> { SelfTest.sleep(5); RenderIdle.wake(); });
        waker.start();
        long t0 = System.nanoTime();
        RenderIdle.park(40_000_000L);
        long took = System.nanoTime() - t0;
        try { waker.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        assert took < 25_000_000L : took;
    }

    /**
     * ZombieBuddy inlines advice bytecode into the game class, so an advice method may only touch public members
     * of public classes (anything else is an IllegalAccessError inside the game). Checks every advice method.
     */
    static void adviceOnlyPublic() {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (Class<?> outer : new Class<?>[] {Patches.class, DeliveryPatches.class})
            for (Class<?> c : outer.getDeclaredClasses())
                if (c.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.class)) checkAdvice(c, bad);
        assert bad.isEmpty() : bad;
    }

    static void checkAdvice(Class<?> c, java.util.List<String> bad) {
        java.lang.classfile.ClassModel m;
        try (java.io.InputStream in = c.getResourceAsStream(c.getSimpleName().isEmpty() ? null
                : c.getName().substring(c.getName().lastIndexOf('.') + 1) + ".class")) {
            m = java.lang.classfile.ClassFile.of().parse(in.readAllBytes());
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        for (java.lang.classfile.MethodModel mm : m.methods()) {
            if (mm.methodName().stringValue().equals("<init>")) continue;
            mm.code().ifPresent(code -> code.forEach(e -> {
                String owner = null, name = null; boolean field = false;
                if (e instanceof java.lang.classfile.instruction.InvokeInstruction i) { owner = i.owner().asInternalName(); name = i.name().stringValue(); }
                else if (e instanceof java.lang.classfile.instruction.FieldInstruction f) { owner = f.owner().asInternalName(); name = f.name().stringValue(); field = true; }
                if (owner == null || owner.startsWith("java/")) return;
                if (!isPublic(owner.replace('/', '.'), name, field)) bad.add(c.getSimpleName() + "." + mm.methodName() + " -> " + owner + "." + name);
            }));
        }
    }

    static boolean isPublic(String cls, String name, boolean field) {
        try {
            Class<?> k = Class.forName(cls, false, DeliveryTest.class.getClassLoader());
            if (!java.lang.reflect.Modifier.isPublic(k.getModifiers())) return false;
            if (field) return java.lang.reflect.Modifier.isPublic(k.getDeclaredField(name).getModifiers());
            for (java.lang.reflect.Method mt : k.getDeclaredMethods())
                if (mt.getName().equals(name) && java.lang.reflect.Modifier.isPublic(mt.getModifiers())) return true;
            return false;
        } catch (ReflectiveOperationException | LinkageError e) { return false; }
    }

    static final long P120 = 1_000_000_000L / 120;   // 8.333 ms

    // the next frame is due in 5 ms: sleep 4.8 ms, spin the last 0.2 ms
    static void limiterSleepsToSpin() {
        long now = 100_000_000L, current = now - 1_000_000L, acc = P120 - 5_000_000L - 1_000_000L;
        assert LimiterSleep.sleepNanos(now, current, acc, P120, LimiterSleep.SPIN_NS) == 4_800_000L;
    }

    static void limiterNoSleepInsideSpin() {
        long now = 100_000_000L;
        assert LimiterSleep.sleepNanos(now, now, P120 - 150_000L, P120, LimiterSleep.SPIN_NS) == 0;
    }

    static void limiterOverdue() {
        long now = 100_000_000L;
        assert LimiterSleep.sleepNanos(now, now, P120 + 10, P120, LimiterSleep.SPIN_NS) == 0;
        assert LimiterSleep.sleepNanos(now, now + 5, 0, P120, LimiterSleep.SPIN_NS) == 0;   // clock went back
    }

    static void limiterUncapped() {
        assert !LimiterSleep.active(true, 120) && !LimiterSleep.active(false, 0) && LimiterSleep.active(false, 60);
    }

    static void limiterBounded() {
        long now = 100_000_000L, twoSeconds = 2_000_000_000L;
        assert LimiterSleep.sleepNanos(now, now, 0, twoSeconds, LimiterSleep.SPIN_NS) == LimiterSleep.MAX_NS;
    }

    // work queued for the main thread wakes the limiter at once
    static void limiterWakesOnQueue() {
        Thread waker = new Thread(() -> { SelfTest.sleep(5); LimiterSleep.wake(); });
        waker.start();
        long t0 = System.nanoTime();
        LimiterSleep.park(40_000_000L);
        long took = System.nanoTime() - t0;
        try { waker.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        assert took < 25_000_000L : took;
    }

    // the render thread sees the stamps of the state it acquired, not of the newest push
    static void stampsRoundTrip() {
        Object a = new Object(), b = new Object();
        FrameClock.frameNo = 6; FrameClock.frameStartNs = 10_000_000L; FrameStamps.pendingInputAgeNs = 2_000_000L;
        FrameStamps.onPush(a);
        FrameClock.frameNo = 7; FrameClock.frameStartNs = 26_000_000L; FrameStamps.pendingInputAgeNs = 0;
        FrameStamps.onPush(b);
        FrameStamps.onAcquire(a);
        assert FrameStamps.renderFrameNo == 7 && FrameStamps.renderFrameStartNs == 10_000_000L
            && FrameStamps.renderInputAgeNs == 2_000_000L;
        FrameStamps.onAcquire(b);
        assert FrameStamps.renderFrameNo == 8 && FrameStamps.renderFrameStartNs == 26_000_000L;
    }

    static void stampsUnknownState() {
        FrameStamps.onAcquire(new Object());
        assert FrameStamps.renderFrameNo == -1 && FrameStamps.renderFrameStartNs == 0;
    }

    // two swaps: the second line has the interval since the first, the time since its frame started, the swap time
    static void presentLineFormat() {
        java.io.File dir = SelfTest.tmp("present/x").getParentFile();
        PresentLog.openLogs(dir);
        Object a = new Object();
        FrameClock.frameNo = 6; FrameClock.frameStartNs = 10_000_000L; FrameStamps.pendingInputAgeNs = 1_250_000L;
        FrameStamps.onPush(a);
        FrameStamps.onAcquire(new Object());
        PresentLog.present(1_000_000L, 3_500_000L, 1000L);
        FrameStamps.onAcquire(a);
        PresentLog.present(17_000_000L, 18_000_000L, 1017L);
        PresentLog.flush();
        String[] lines = readAll(new java.io.File(dir, "present.out")).split("\n");
        assert lines.length == 2 : lines.length;
        assert lines[0].equals("1000 -1 0.000 0.000 2.500 0.000") : lines[0];
        assert lines[1].equals("1017 7 14.500 8.000 1.000 1.250") : lines[1];
        assert PresentLog.lastSwapEndNs == 18_000_000L && PresentLog.lastSwapNs == 1_000_000L;
    }

    // one second: game thread 500 ms of CPU, render thread 250 ms
    static void threadsLineFormat() {
        java.io.File dir = SelfTest.tmp("present/x").getParentFile();
        PresentLog.threadsLine(2000L, 500_000_000L, 250_000_000L, 1_000_000_000L);
        PresentLog.flush();
        assert readAll(new java.io.File(dir, "threads.out")).equals("2000 50.000 25.000\n");
    }

    static final String[] FLAGS = {"limiterSleep", "renderIdleWait", "frameQueue", "frameQueueGpu", "inputLatch",
        "keyboardFresh", "frameStartGate", "cursorLatch", "vsyncAdaptive", "vrrCap", "reflexSleep", "reflexBoost",
        "vblankLock"};
    static final String[] MODES = {"presentPacing", "vrr", "borderlessFullscreen"};

    // a fresh file with the master switch on: every Plan 2 key is off, numbers have the spec's defaults
    static void settingsKeysOffByDefault() {
        java.io.File f = SelfTest.tmp("fresh2.properties"); f.delete();
        Settings.load(f);
        Settings.load(write(readAll(f).replace("enabled=false", "enabled=true")));
        assert Settings.enabled;
        for (String k : FLAGS) assert !Settings.flag(k) : k;
        for (String k : MODES) assert Settings.mode(k).equals("off") : k;
        assert Settings.num("frameQueueMinPct", -1, 0, 1000) == 260 && Settings.num("aimHoldMs", -1, 0, 5000) == 150;
        assert readAll(f).contains("frameStartGate=false") && readAll(f).contains("gpuMaxFrames=0");
    }

    static void settingsNumClamped() {
        Settings.load(write("enabled=true\nframeQueueMinPct=9999\ngpuMaxFrames=abc\n"));
        assert Settings.num("frameQueueMinPct", 260, 10, 1000) == 1000;
        assert Settings.num("gpuMaxFrames", 0, 0, 8) == 0;
        assert Settings.num("missingKey", 7, 0, 8) == 7;
    }

    // master switch off: every feature reads as off, whatever its own key says
    static void settingsModeMasterOff() {
        Settings.load(write("enabled=false\nvrr=auto\nlimiterSleep=true\n"));
        assert Settings.mode("vrr").equals("off") && !Settings.flag("limiterSleep");
        Settings.load(write("enabled=true\nvrr=AUTO\npresentPacing=on\nborderlessFullscreen=sideways\n"));
        assert Settings.mode("vrr").equals("auto") && Settings.mode("presentPacing").equals("on");
        assert Settings.mode("borderlessFullscreen").equals("off");
    }

    static final class Holder { private static long secret = 42; }

    // a private static long in the same (unnamed) module is reachable; a missing one gives null, no throw
    static void gameStaticLongMissing() {
        assert Game.staticLong(Settings.class, "nope") == null;
        assert Game.staticLong(String.class, "nope") == null;
        java.lang.invoke.VarHandle h = Game.staticLong(Holder.class, "secret");
        assert h != null && (long) h.get() == 42;
    }

    static String readAll(java.io.File f) {
        try { return java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
}
