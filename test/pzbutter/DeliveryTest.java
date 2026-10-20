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
