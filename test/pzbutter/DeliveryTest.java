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
