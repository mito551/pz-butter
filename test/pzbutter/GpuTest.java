package pzbutter;

/** Plan 9 checks (sprite buffers and GPU; Task 1: keys and template, Task 3: combined texture release). Run from SelfTest.main. Later tasks append their calls to runAll(). */
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
        releaseNullsBeforeRelease();
        releaseSkipsTextureAlreadyPooled();
        releaseNothingWhenNullOrOff();
        inPoolReadsCombinerFreeList();
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

    // ---- Task 3: smartTextureRelease ----

    static void releaseNullsBeforeRelease() {
        SmartRelease.resetForTest(); SmartRelease.guard.on = true;
        zombie.core.textures.SmartTexture st = new zombie.core.textures.SmartTexture();   // headless-constructible (research Surprise 5)
        zombie.core.textures.SmartTexture old = new zombie.core.textures.SmartTexture();
        st.result = old;
        java.util.List<Object> got = new java.util.ArrayList<>();
        SmartRelease.pooled = t -> false;
        SmartRelease.release = t -> { assert st.result == null; got.add(t); };
        GpuPatches.onSmartCalculate(st);
        assert got.size() == 1 && got.get(0) == old && st.result == null && SmartRelease.released == 1;
        SmartRelease.resetForTest();
    }

    static void releaseSkipsTextureAlreadyPooled() {
        SmartRelease.resetForTest(); SmartRelease.guard.on = true;
        zombie.core.textures.SmartTexture st = new zombie.core.textures.SmartTexture(), old = new zombie.core.textures.SmartTexture();
        st.result = old;
        SmartRelease.pooled = t -> t == old;
        SmartRelease.release = t -> { throw new AssertionError("released twice"); };
        GpuPatches.onSmartCalculate(st);
        assert st.result == old && SmartRelease.skipped == 1 && SmartRelease.released == 0;   // left as stock leaves it
        SmartRelease.resetForTest();
    }

    static void releaseNothingWhenNullOrOff() {
        SmartRelease.resetForTest();
        zombie.core.textures.SmartTexture st = new zombie.core.textures.SmartTexture(), old = new zombie.core.textures.SmartTexture();
        st.result = old;
        SmartRelease.release = t -> { throw new AssertionError("off"); };
        GpuPatches.onSmartCalculate(st);                       // key off
        assert st.result == old;
        SmartRelease.guard.on = true; st.result = null;
        GpuPatches.onSmartCalculate(st); GpuPatches.onSmartCalculate(null);
        assert SmartRelease.released == 0;
        SmartRelease.resetForTest();
    }

    static void inPoolReadsCombinerFreeList() {
        zombie.core.textures.SmartTexture t = new zombie.core.textures.SmartTexture();
        assert !SmartRelease.inPool(t);                        // headless combiner: no pool entry for 0 x 0
    }
}
