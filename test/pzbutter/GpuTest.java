package pzbutter;

/** Plan 9 checks (sprite buffers and GPU; Task 1: keys and template). Run from SelfTest.main. Later tasks append their calls to runAll(). */
final class GpuTest {
    private GpuTest() {}

    static void run() throws Exception {
        try { runAll(); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new Exception(t); }
    }

    static void runAll() throws Throwable {
        plan9KeysDefaults();
        settingsTemplateHasPlan9Keys();
    }

    static final String[] PLAN9_FLAGS = {"persistentVbo", "vboFastQuads", "smartTextureRelease", "bakeNoSync", "bakeNoSyncCheck", "bakeBudgetGpu", "gpuStats"};

    static void plan9KeysDefaults() {
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        for (String k : PLAN9_FLAGS) assert !Settings.flag(k) : k;
        assert Settings.flag("persistentVboFrameSync");
        assert Settings.num("persistentVboSlots", -1, -1, 99) == 4 && Settings.num("vboBatchKb", -1, -1, 9999) == 0;
        assert Settings.num("vboSizeClassMinKb", -1, -1, 9999) == 64 && Settings.text("vboSizeClasses", "?").equals("off");
    }

    static void settingsTemplateHasPlan9Keys() {
        String t = Settings.render(new java.util.Properties());
        for (String k : new String[] {"persistentVbo=false", "persistentVboSlots=4", "persistentVboFrameSync=true", "vboBatchKb=0",
                "vboFastQuads=false", "vboSizeClasses=off", "vboSizeClassMinKb=64", "smartTextureRelease=false", "bakeNoSync=false",
                "bakeNoSyncCheck=false", "bakeBudgetGpu=false", "gpuStats=false"})
            assert t.contains("\n" + k + "\n") : k;
        assert t.indexOf("\nentityTiming=false\n") < t.indexOf("\npersistentVbo=false\n") && t.indexOf("\ngpuStats=false\n") < t.indexOf("\ninputLatch=true\n");
        assert t.contains("# persistentVboFrameSync: acts only with persistentVbo");
    }
}
