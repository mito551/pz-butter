package pzbutter;

/** Plan 12 Task 1 checks. Run from SelfTest.main. */

final class RuntimeTest {
    private RuntimeTest() {}

    static void run() throws Exception {
        RuntimeFixes.resetForTest();
        try { runAll(); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new Exception(t); }
    }

    static void runAll() throws Throwable {
        plan12KeysDefaults();
        settingsTemplateHasPlan12Keys();
        luaGcSkipsCollectStepNil();
        luaGcCountUntouched();
        luaGcOffStock();
        luaGcMinuteLine();
        lightParkDeadlineAdvances();
        lightParkCatchesUp();
        lightParkZeroFps();
        lightParkFpsChange();
        lightParkOffStock();
        lightParkInterrupt();
    }

    static void plan12KeysDefaults() {
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        assert !Settings.flag("luaGcNoop");
        assert !Settings.flag("lightingSyncPark");
    }

    static void settingsTemplateHasPlan12Keys() {
        String t = Settings.render(new java.util.Properties());
        assert t.contains("\nluaGcNoop=false\n");
        assert t.contains("\nlightingSyncPark=false\n");
    }

    static void luaGcSkipsCollectStepNil() {
        RuntimeFixes.resetForTest(); RuntimeFixes.gcGuard.on = true;
        assert RuntimeFixes.skipCollect(null);
        assert RuntimeFixes.skipCollect("step");
        assert RuntimeFixes.skipCollect("collect");
        assert RuntimeFixes.gcSkipped == 3;
        RuntimeFixes.resetForTest();
    }

    static void luaGcCountUntouched() {
        RuntimeFixes.resetForTest(); RuntimeFixes.gcGuard.on = true;
        assert !RuntimeFixes.skipCollect("count");
        assert !RuntimeFixes.skipCollect("stop");
        assert RuntimeFixes.gcSkipped == 0;
        RuntimeFixes.resetForTest();
    }

    static void luaGcOffStock() {
        RuntimeFixes.resetForTest();
        assert !RuntimeFixes.gcGuard.on;
        assert !RuntimeFixes.skipCollect(null);
        assert !RuntimePatches.onCollect(null, 0);
        RuntimeFixes.resetForTest();
    }

    static void luaGcMinuteLine() {
        RuntimeFixes.resetForTest();
        RuntimeFixes.gcGuard.on = true;
        RuntimeFixes.gcSkipped = 5;
        java.io.PrintStream old = System.out;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(baos));
        try {
            long min = 60_000_000_000L;
            assert RuntimeFixes.tick(0L) == 0;             // first call: baseline only, nothing printed
            assert baos.toString().isEmpty();
            assert RuntimeFixes.tick(min - 1) == 0;        // not yet a minute
            assert baos.toString().isEmpty();
            RuntimeFixes.gcSkipped = 7;                    // count rose
            assert RuntimeFixes.tick(min) == 2;            // minute elapsed, line printed
            assert baos.toString().equals("[PZButter] luaGcNoop: 2 forced collections skipped\n");
            baos.reset();
            assert RuntimeFixes.tick(2 * min) == 0;        // quiet minute: nothing
            assert baos.toString().isEmpty();
        } finally { System.setOut(old); }
        RuntimeFixes.resetForTest();
    }

    static void lightParkDeadlineAdvances() {
        RuntimeFixes.resetForTest();
        assert RuntimeFixes.nextDeadline(0L, 0L, 30) == 33_333_333L;
        assert RuntimeFixes.nextDeadline(33_333_333L, 33_333_333L, 30) == 66_666_666L;
        RuntimeFixes.resetForTest();
    }

    static void lightParkCatchesUp() {
        RuntimeFixes.resetForTest();
        long now = 100_000_000L;
        assert RuntimeFixes.nextDeadline(0L, now, 30) == now;
        RuntimeFixes.resetForTest();
    }

    static void lightParkZeroFps() {
        RuntimeFixes.resetForTest();
        assert RuntimeFixes.nextDeadline(10L, 5L, 0) == 10L;
        assert RuntimeFixes.nextDeadline(10L, 100L, 0) == 10L;
        RuntimeFixes.parkUntil(System.nanoTime() - 1L);
        RuntimeFixes.resetForTest();
    }

    static void lightParkFpsChange() {
        RuntimeFixes.resetForTest();
        long d30 = RuntimeFixes.nextDeadline(0L, 0L, 30);
        long d60 = RuntimeFixes.nextDeadline(d30, d30, 60);
        assert d60 == d30 + 16_666_666L;
        RuntimeFixes.resetForTest();
    }

    static void lightParkOffStock() {
        RuntimeFixes.resetForTest();
        assert !RuntimeFixes.lightGuard.on;
        assert !RuntimePatches.onSync(30);
        RuntimeFixes.resetForTest();
    }

    static void lightParkInterrupt() {
        RuntimeFixes.resetForTest();
        long deadline = System.nanoTime() + 20_000_000L;
        Thread.currentThread().interrupt();
        RuntimeFixes.parkUntil(deadline);
        assert System.nanoTime() >= deadline;
        assert Thread.currentThread().isInterrupted();
        Thread.interrupted();
        RuntimeFixes.resetForTest();
    }
}
