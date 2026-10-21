package pzbutter;

import static pzbutter.SelfTest.write;

/** Plan 2 (frame delivery) checks. Run from SelfTest.main; failures are AssertionErrors. */
final class DeliveryTest {
    private DeliveryTest() {}

    static void run() {
        settingsKeysOffByDefault();
        settingsUpgradeAddsMissingKeys();
        settingsUpgradeKeepsValues();
        settingsUpgradeKeepsUnknownKeys();
        settingsUpgradeBackupOnce();
        settingsUpgradeNoRewriteWhenCurrent();
        settingsDefaultsOn();
        gpuQueueDefault();
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
        paceFollowsP95();
        paceIgnoresSpike();
        paceCappedAndMargin();
        holdLateFrameNotHeld();
        holdUncappedNone();
        holdLateFrameNoWait();
        capFormula();
        capOnlyWhenAbove();
        capReflexWithVsync();
        vsyncInterval();
        gpuQueueRing();
        monitorAt();
        reflexEwma();
        reflexOncePerFrame();
        reflexBoostParse();
        vrrMatchOutput();
        vrrProbeNoDrm();
        vrrDescribe();
        vrrMode();
        borderlessDecision();
        probeUnavailableOff();
        vblankSecondsMargin();
        cursorRecordLookup();
        cursorNoRecordNoMove();
        aimHoldDelay();
        capNotSavedToOptions();
        latchWaitScaled();
        latchServedDuringHold();
        aimHoldUiCaptured();
        vrrProbeOffThread();
        inputAgeMeasuredWithLog();
        inputAgeSnapshot();
        adviceOnlyPublic();
    }

    // the poll time is taken when the game reads the state: a later re-poll of the same (reused) state object
    // must not make the input look younger
    static void inputAgeSnapshot() {
        Object a = new Object();
        InputLatch.notePoll(a, 1_000_000L);
        long read = InputLatch.pollTimeOf(a);
        InputLatch.notePoll(a, 9_000_000L);
        assert read == 1_000_000L && InputLatch.ageFrom(read, 6_500_000L) == 5_500_000L;
        assert InputLatch.pollTimeOf(new Object()) == -1 && InputLatch.ageFrom(-1, 6_500_000L) == 0;
    }

    // with frameLog on and no input feature, the input age is still measured (a baseline to compare against)
    static void inputAgeMeasuredWithLog() {
        String inputOff = "inputLatch=false\nkeyboardFresh=false\nframeStartGate=false\n";   // on by default since Task 13
        Settings.load(write("enabled=true\nframeLog=true\n" + inputOff));
        InputLatch.init();
        assert InputLatch.guard.on && !InputLatch.latch && !InputLatch.fresh && !InputLatch.gate;
        Settings.load(write("enabled=true\n" + inputOff));
        InputLatch.init();
        assert !InputLatch.guard.on;
    }


    // review I2: while the game writes its options, the getters return the game's own values
    static void capNotSavedToOptions() {
        assert Caps.overrideFps(300, 224, false) == 224 && Caps.overrideFps(300, 224, true) == 300 && Caps.overrideFps(300, 0, false) == 300;
        assert !Caps.overrideUncapped(true, 224, false) && Caps.overrideUncapped(true, 224, true) && Caps.overrideUncapped(true, 0, false);
    }

    // review I3: the game thread waits at most an eighth of a frame (2 ms at most) for a fresh poll
    static void latchWaitScaled() {
        assert InputLatch.waitBoundNs(P240) == P240 / 8 && InputLatch.waitBoundNs(P60) == 2_000_000L;
        assert InputLatch.waitBoundNs(0) == 500_000L;
    }

    // review I3: a request made while the render thread sits in the swap hold is served from the hold
    static void latchServedDuringHold() {
        boolean latchWas = InputLatch.latch, guardWas = InputLatch.guard.on;
        InputLatch.latch = true; InputLatch.guard.on = true;
        Runnable serveWas = SwapHold.serveDuringWait;
        SwapHold.serveDuringWait = InputLatch::markServed;
        Thread holder = new Thread(() -> SwapHold.waitPrecise(40_000_000L));
        holder.start();
        SelfTest.sleep(5);
        InputLatch.request(Long.MAX_VALUE - 1);
        SwapHold.wakeForRequest();
        long t0 = System.nanoTime();
        InputLatch.awaitServed(10_000_000L);
        long took = System.nanoTime() - t0;
        try { holder.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        SwapHold.serveDuringWait = serveWas; InputLatch.latch = latchWas; InputLatch.guard.on = guardWas;
        assert !InputLatch.pending() && took < 5_000_000L : took;
    }

    // review I5: a right button held over UI (captured) never starts aiming
    static void aimHoldUiCaptured() {
        assert !AimHold.delayed(true, true, 1f, 50) && AimHold.delayed(false, true, 1f, 50);
    }

    // review I7: the render thread only asks for a probe; a slow probe runs elsewhere
    static void vrrProbeOffThread() {
        String modeWas = Vrr.mode;
        java.util.function.Supplier<int[][]> was = Vrr.prober;
        Vrr.mode = "auto";
        Vrr.prober = () -> { SelfTest.sleep(200); return new int[][] {{2560, 1440, 120, 1, 1}}; };
        long t0 = System.nanoTime();
        Vrr.update(new int[] {0, 0, 2560, 1440}, 120);
        long took = System.nanoTime() - t0;
        for (int i = 0; i < 100 && Vrr.probed == null; i++) SelfTest.sleep(10);
        Vrr.prober = was; Vrr.mode = modeWas;
        assert took < 20_000_000L : took;
        assert Vrr.probed == Boolean.TRUE;
        Vrr.probed = null;
    }

    // the cursor quad recorded for a frame is found again when that frame is drawn
    static void cursorRecordLookup() {
        Object a = new Object(), b = new Object();
        CursorLatch.record(7, a, 100, 200);
        CursorLatch.record(8, b, 110, 205);
        int i = CursorLatch.find(8);
        assert i >= 0 && CursorLatch.drawAt(i) == b && CursorLatch.xAt(i) == 110 && CursorLatch.yAt(i) == 205;
        assert CursorLatch.drawAt(CursorLatch.find(7)) == a;
    }

    // no cursor drawn in that frame: nothing to move
    static void cursorNoRecordNoMove() {
        assert CursorLatch.find(9) == -1 && CursorLatch.find(-1) == -1;
    }

    // aiming starts once the right button has been held aimHoldMs (stock: 150 ms)
    static void aimHoldDelay() {
        assert AimHold.delayed(false, true, 0.15f, 150) && !AimHold.delayed(false, true, 0.10f, 150);
        assert AimHold.delayed(false, true, 0.05f, 50) && !AimHold.delayed(false, false, 1f, 0);
    }

    static final int X11 = 0x00060004, WAYLAND = 0x00060003;   // GLFW_PLATFORM_* values

    // vblankLock needs GLFW on X11 and the extension; anything else leaves it off
    static void probeUnavailableOff() {
        assert VblankLock.available(true, X11, X11);
        assert !VblankLock.available(false, X11, X11) && !VblankLock.available(true, WAYLAND, X11);
    }

    // the wait ends the smoothed swap time plus 0.5 ms before vblank
    static void vblankSecondsMargin() {
        assert Math.abs(VblankLock.seconds(2_000_000L) - 0.0025f) < 1e-7f;
        assert Math.abs(VblankLock.seconds(0) - 0.0005f) < 1e-7f;
    }

    // outputs: {w, h, refresh, vrr_capable, VRR_ENABLED}; the window's monitor is matched by mode size and refresh
    static void vrrMatchOutput() {
        int[][] o = {{1920, 1080, 60, 0, 0}, {2560, 1440, 165, 1, 1}};
        assert Vrr.pick(o, 2560, 1440, 165) == Boolean.TRUE;
        assert Vrr.pick(o, 1920, 1080, 60) == Boolean.FALSE;
        assert Vrr.pick(o, 3840, 2160, 60) == null;                       // no match: unknown
        assert Vrr.pick(o, 2560, 1440, 0) == Boolean.TRUE;                // refresh unknown: size only
        int[][] twins = {{2560, 1440, 165, 1, 1}, {2560, 1440, 165, 1, 0}};
        assert Vrr.pick(twins, 2560, 1440, 165) == null;                  // ambiguous and they disagree
        assert Vrr.pick(new int[][] {{2560, 1440, 165, 1, 1}, {2560, 1440, 165, 1, 1}}, 2560, 1440, 165) == Boolean.TRUE;
    }

    // the log line says what the driver reported for the window's monitor, so a change can be explained
    static void vrrDescribe() {
        int[][] o = {{1920, 1080, 60, 0, 0}, {2560, 1440, 120, 1, 0}};
        assert Vrr.describe(o, 2560, 1440, 120).equals("window 2560x1440@120; outputs 1920x1080@60 capable=0 enabled=0, 2560x1440@120 capable=1 enabled=0")
            : Vrr.describe(o, 2560, 1440, 120);
        assert Vrr.describe(null, 2560, 1440, 120).equals("window 2560x1440@120; libdrm not readable");
    }

    static void vrrProbeNoDrm() {
        assert Vrr.probe("libpzbutter-no-such-drm.so.9") == null;
    }

    // vrr=on/off force it; auto uses the probe
    static void vrrMode() {
        assert Vrr.resolve("on", null) == Boolean.TRUE && Vrr.resolve("off", Boolean.TRUE) == Boolean.FALSE;
        assert Vrr.resolve("auto", Boolean.TRUE) == Boolean.TRUE && Vrr.resolve("auto", null) == null;
    }

    // borderlessFullscreen: only for the game's borderless window mode; auto needs VRR active
    static void borderlessDecision() {
        assert Vrr.wantFullscreenWindow("on", null, true, false);
        assert !Vrr.wantFullscreenWindow("on", null, false, false);       // not borderless
        assert !Vrr.wantFullscreenWindow("on", null, true, true);         // real fullscreen already
        assert Vrr.wantFullscreenWindow("auto", Boolean.TRUE, true, false) && !Vrr.wantFullscreenWindow("auto", null, true, false);
        assert !Vrr.wantFullscreenWindow("off", Boolean.TRUE, true, false);
    }

    // the smoothed swap wait converges to a steady wait and lets a spike fade
    static void reflexEwma() {
        long v = 0;
        for (int i = 0; i < 100; i++) v = Reflex.ewma(v, 2_000_000L);
        assert Math.abs(v - 2_000_000L) < 1_000 : v;
        v = Reflex.ewma(v, 50_000_000L);
        assert v < 9_000_000L;
        for (int i = 0; i < 100; i++) v = Reflex.ewma(v, 2_000_000L);
        assert Math.abs(v - 2_000_000L) < 1_000 : v;
    }

    // the extra delay is taken once per frame, when the frame is due, not on every limiter pass
    static void reflexOncePerFrame() {
        assert Reflex.extraFor(41, 40, 3_000_000L, 0) == 3_000_000L;
        assert Reflex.extraFor(41, 41, 3_000_000L, 0) == 0;          // already taken for frame 41
        assert Reflex.extraFor(42, 41, 3_000_000L, 500_000L) == 0;   // not due yet
        assert Reflex.extraFor(42, 41, 0, 0) == 0;
    }

    static void reflexBoostParse() {
        assert Reflex.parseMode("0\n") == 0 && Reflex.parseMode("  2 ") == 2;
        assert Reflex.parseMode("") == null && Reflex.parseMode("ERROR: Unable to load info") == null && Reflex.parseMode(null) == null;
    }

    static void capFormula() {
        assert Caps.vrrCapFps(240) == 224 && Caps.vrrCapFps(165) == 157 && Caps.vrrCapFps(144) == 138;
    }

    // vrrCap only lowers a cap above the panel (or uncapped); a 120 cap at 240 Hz stays
    static void capOnlyWhenAbove() {
        assert Caps.capFor(120, false, 240, true, false, false, 0) == 0;
        assert Caps.capFor(300, false, 240, true, false, false, 0) == 224;
        assert Caps.capFor(60, true, 240, true, false, false, 0) == 224;
        assert Caps.capFor(300, false, 240, false, false, false, 0) == 0;   // VRR off or unknown
        assert Caps.capFor(300, false, 0, true, false, false, 0) == 0;      // refresh unknown
    }

    // reflexCapFps: only with reflexSleep and vsync; 0 is the formula, a number is that cap
    static void capReflexWithVsync() {
        assert Caps.capFor(144, false, 144, false, true, true, 0) == 138;
        assert Caps.capFor(144, false, 144, false, true, true, 100) == 100;
        assert Caps.capFor(144, false, 144, false, false, true, 0) == 0;
        assert Caps.capFor(144, false, 144, false, true, false, 0) == 0;
        assert Caps.capFor(90, false, 144, false, true, true, 0) == 0;      // already under
    }

    static void vsyncInterval() {
        assert Caps.swapInterval(true, true, true) == -1 && Caps.swapInterval(true, true, false) == 1;
        assert Caps.swapInterval(true, false, true) == 1 && Caps.swapInterval(false, true, true) == 0;
    }

    // gpuMaxFrames = 2: before swap k, wait for the fence of swap k - 2; fences live in k % 3
    static void gpuQueueRing() {
        assert GpuQueue.waitIndex(5, 2) == 3 && GpuQueue.waitIndex(1, 2) == -1 && GpuQueue.slot(5, 2) == 2;
    }

    // which monitor holds the window's centre (x, y, w, h per monitor); none: -1
    static void monitorAt() {
        int[][] m = {{0, 0, 2560, 1440}, {2560, 0, 1920, 1080}};
        assert Caps.monitorAt(m, 3000, 500) == 1 && Caps.monitorAt(m, 100, 100) == 0 && Caps.monitorAt(m, 9000, 0) == -1;
    }



    static final SwapHold.Config PACE = new SwapHold.Config(100, 0);   // presentPacingMaxPct, presentPacingMarginPct
    static final long P60 = 1_000_000_000L / 60, P240 = 1_000_000_000L / 240;



    // one second of frames ready 5 ms after their start: the hold becomes 5 ms (none before that)
    static void paceFollowsP95() {
        SwapHold.Learner l = new SwapHold.Learner(PACE);
        long t = 1_000_000_000L;
        for (int i = 0; i < 60; i++) l.observe(5_000_000L, t + i * P60, P60);
        assert l.holdNs() == 0 : l.holdNs();
        l.observe(5_000_000L, t + 60 * P60 + 1_000, P60);
        assert l.holdNs() == 5_000_000L : l.holdNs();
    }

    // a single 40 ms frame among 61 doesn't move the hold (no raise on one late frame)
    static void paceIgnoresSpike() {
        SwapHold.Learner l = new SwapHold.Learner(PACE);
        long t = 1_000_000_000L;
        for (int i = 0; i < 61; i++) l.observe(i == 30 ? 40_000_000L : 5_000_000L, t + i * P60 + (i == 60 ? 1_000 : 0), P60);
        assert l.holdNs() == 5_000_000L : l.holdNs();
    }

    // never more than presentPacingMaxPct of a frame (100 %: one frame); the margin is added on top of the p95
    static void paceCappedAndMargin() {
        SwapHold.Learner l = new SwapHold.Learner(PACE);
        long t = 1_000_000_000L;
        for (int i = 0; i < 61; i++) l.observe(30_000_000L, t + i * P60 + (i == 60 ? 1_000 : 0), P60);
        assert l.holdNs() == P60 : l.holdNs();
        SwapHold.Learner m = new SwapHold.Learner(new SwapHold.Config(100, 10));
        for (int i = 0; i < 61; i++) m.observe(5_000_000L, t + i * P60 + (i == 60 ? 1_000 : 0), P60);
        assert m.holdNs() == 5_000_000L + P60 / 10 : m.holdNs();
    }

    // with a 5 ms hold: a frame ready at 8 ms is swapped at once, one ready at 2 ms waits 3 ms
    static void holdLateFrameNotHeld() {
        SwapHold.Learner l = new SwapHold.Learner(PACE);
        long t = 1_000_000_000L;
        for (int i = 0; i < 61; i++) l.observe(5_000_000L, t + i * P60 + (i == 60 ? 1_000 : 0), P60);
        long s = 2_100_000_000L;   // within the second after the last update: the hold stays 5 ms
        assert SwapHold.holdWaitNs(s + 8_000_000L, s, P60, 60, l, PACE) == 0;
        assert SwapHold.holdWaitNs(s + 2_000_000L, s, P60, 60, l, PACE) == 3_000_000L;
    }

    static void holdUncappedNone() {
        assert SwapHold.holdNs(0, 0, 12_000_000L, PACE) == 0 && SwapHold.holdNs(P60, 60, 12_000_000L, PACE) == 12_000_000L;
    }

    static void holdLateFrameNoWait() {
        assert SwapHold.waitNs(50_000_000L, 10_000_000L, 20_000_000L) == 0;
        assert SwapHold.waitNs(25_000_000L, 10_000_000L, 20_000_000L) == 5_000_000L;
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
        for (Class<?> outer : new Class<?>[] {Patches.class, DeliveryPatches.class, LuaPatches.class, UiPatches.class, MapPatches.class})
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

    static final String[] FLAGS = {"limiterSleep", "renderIdleWait", "inputLatch",
        "keyboardFresh", "frameStartGate", "cursorLatch", "vsyncAdaptive", "vrrCap", "reflexSleep", "reflexBoost",
        "vblankLock"};
    static final String[] MODES = {"presentPacing", "vrr", "borderlessFullscreen"};

    // a fresh file with the master switch on: every Plan 2 key is off, numbers have the spec's defaults
    static void settingsKeysOffByDefault() {
        java.io.File f = SelfTest.tmp("fresh2.properties"); f.delete();
        Settings.load(f);
        Settings.load(write(readAll(f).replace("enabled=false", "enabled=true")));
        assert Settings.enabled;
        for (String k : FLAGS) assert Settings.flag(k) == ON_BY_DEFAULT.contains(k) : k;
        for (String k : MODES) assert Settings.mode(k).equals("off") : k;
        assert Settings.num("presentPacingMaxPct", -1, 0, 1000) == 100 && Settings.num("aimHoldMs", -1, 0, 5000) == 150;
        assert readAll(f).contains("frameStartGate=true") && readAll(f).contains("vrrCap=false") && readAll(f).contains("gpuMaxFrames=2");
    }

    // passed in game (Task 13) and switched on by the user: on when the key is missing, but never without the master
    static final java.util.Set<String> ON_BY_DEFAULT = java.util.Set.of("limiterSleep", "inputLatch", "keyboardFresh", "frameStartGate",
        "reflexSleep", "vsyncAdaptive");

    // gpuMaxFrames defaults to 2 (user, 2026-10-21), and like every feature never acts without the master switch
    static void gpuQueueDefault() {
        Settings.load(write("enabled=true\n"));
        GpuQueue.init();
        assert GpuQueue.maxFrames == 2 && GpuQueue.guard.on;
        Settings.load(write("enabled=false\n"));
        GpuQueue.init();
        assert !GpuQueue.guard.on;
        Settings.load(write("enabled=true\ngpuMaxFrames=0\n"));
        GpuQueue.init();
        assert !GpuQueue.guard.on;
    }

    static void settingsDefaultsOn() {
        Settings.load(write("enabled=true\n"));
        for (String k : FLAGS) assert Settings.flag(k) == ON_BY_DEFAULT.contains(k) : k;
        Settings.load(write("enabled=false\nlimiterSleep=true\n"));
        assert !Settings.flag("limiterSleep");
        Settings.load(write("enabled=true\nlimiterSleep=false\n"));
        assert !Settings.flag("limiterSleep") && Settings.flag("inputLatch");
    }

    // an old file (only enabled/frameLog): every current key appears with its comment; the values load
    static void settingsUpgradeAddsMissingKeys() {
        java.io.File f = write("enabled=true\nframeLog=true\n");
        Settings.load(f);
        String t = readAll(f);
        assert t.contains("enabled=true") && t.contains("frameLog=true") : t;
        assert t.contains("# limiterSleep:") && t.contains("limiterSleep=true") && t.contains("gpuMaxFrames=2") && t.contains("vrr=off");
        assert Settings.enabled && Settings.frameLog && Settings.flag("limiterSleep");
    }

    // the user's values (including non-default spellings) survive the upgrade
    static void settingsUpgradeKeepsValues() {
        java.io.File f = write("enabled=true\nlimiterSleep=false\nvrr=auto\ngpuMaxFrames=4\n");
        Settings.load(f);
        String t = readAll(f);
        assert t.contains("limiterSleep=false") && t.contains("vrr=auto") && t.contains("gpuMaxFrames=4") : t;
        assert !t.contains("limiterSleep=true") && !t.contains("gpuMaxFrames=2");
        assert !Settings.flag("limiterSleep") && Settings.mode("vrr").equals("auto");
    }

    // keys this version doesn't know (removed features, typos) are kept at the end
    static void settingsUpgradeKeepsUnknownKeys() {
        java.io.File f = write("enabled=true\nframeQueue=true\n");
        Settings.load(f);
        String t = readAll(f);
        assert t.contains("frameQueue=true") && t.indexOf("frameQueue=true") > t.indexOf("frameLog=") : t;
    }

    // the first rewrite keeps a copy of the original; later rewrites don't overwrite it
    static void settingsUpgradeBackupOnce() {
        java.io.File f = write("enabled=true\n");
        java.io.File bak = new java.io.File(f.getPath() + ".bak");
        bak.delete();
        Settings.load(f);
        assert bak.exists() && readAll(bak).equals("enabled=true\n");
        try (java.io.FileWriter w = new java.io.FileWriter(f, true)) { w.write("someNewThing=1\n"); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        Settings.load(f);
        assert readAll(bak).equals("enabled=true\n");
    }

    // a file that is already current is not written again
    static void settingsUpgradeNoRewriteWhenCurrent() {
        java.io.File f = write("enabled=true\n");
        Settings.load(f);
        assert f.setLastModified(1_000_000_000_000L);
        Settings.load(f);
        assert f.lastModified() == 1_000_000_000_000L;
    }

    static void settingsNumClamped() {
        Settings.load(write("enabled=true\npresentPacingMaxPct=9999\ngpuMaxFrames=abc\n"));
        assert Settings.num("presentPacingMaxPct", 100, 10, 1000) == 1000;
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
