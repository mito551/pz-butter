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
    }

    static void plan12KeysDefaults() {
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        assert !Settings.flag("luaGcNoop");
    }

    static void settingsTemplateHasPlan12Keys() {
        String t = Settings.render(new java.util.Properties());
        assert t.contains("\nluaGcNoop=false\n");
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
}
