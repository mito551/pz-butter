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
        adviceOnlyPublic();
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
