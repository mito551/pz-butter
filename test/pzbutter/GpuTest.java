package pzbutter;

/** Plan 9 checks (sprite buffers and GPU; Task 1: keys and template, Task 3: combined texture release, Task 4: batch size). Run from SelfTest.main. Later tasks append their calls to runAll(). */
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
        batchKbZeroInstallsNothing();
        batchSizesAndClamp();
        batchInstallsOnEmptyBuffers();
        batchKeepsBuffersMidBatch();
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

    // ---- Task 4: vboBatchKb ----

    static Object field(Object o, String f) throws Exception { java.lang.reflect.Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); return x.get(o); }

    static void setField(Object o, String f, Object v) throws Exception { java.lang.reflect.Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v); }

    /**
     * A headless VBORenderer in the state a batch starts from: stock 4 KB buffers, the stock format set (through the private setFormat,
     * without the advice), and a mode 7 run in progress, so addQuad writes into it. Never flushes (research Surprise 5).
     */
    static zombie.core.opengl.VBORenderer vbo() throws Exception {
        zombie.core.opengl.VBORenderer r = new zombie.core.opengl.VBORenderer();
        setField(r, "elements", java.nio.ByteBuffer.allocateDirect(4096).order(java.nio.ByteOrder.nativeOrder()));
        setField(r, "indices", java.nio.ByteBuffer.allocateDirect(1024).order(java.nio.ByteOrder.nativeOrder()));
        Class<?> rc = Class.forName("zombie.core.opengl.VBORenderer$Run");
        java.lang.reflect.Constructor<?> c = rc.getDeclaredConstructors()[0]; c.setAccessible(true);
        Object run = c.getParameterCount() == 0 ? c.newInstance() : c.newInstance(r);
        setField(run, "mode", 7);
        setField(run, "format", r.formatPositionColorUv);
        java.lang.reflect.Method sf = zombie.core.opengl.VBORenderer.class.getDeclaredMethod("setFormat", zombie.core.skinnedmodel.model.VertexBufferObject.VertexFormat.class);
        sf.setAccessible(true); sf.invoke(r, r.formatPositionColorUv);
        setField(r, "runInProgress", run);
        return r;
    }

    /** The private setFormat as the game calls it, then the shim the advice runs after it (the advice is not woven in tests). */
    static void callSetFormat(zombie.core.opengl.VBORenderer r) throws Exception {
        java.lang.reflect.Method m = zombie.core.opengl.VBORenderer.class.getDeclaredMethod("setFormat", zombie.core.skinnedmodel.model.VertexBufferObject.VertexFormat.class);
        m.setAccessible(true); m.invoke(r, r.formatPositionColorUv);
        GpuPatches.onVboFormat(r);
    }

    static void batchKbZeroInstallsNothing() throws Exception {
        BatchSize.resetForTest();
        zombie.core.opengl.VBORenderer r = vbo();
        callSetFormat(r);
        assert ((java.nio.ByteBuffer) field(r, "elements")).capacity() == 4096 && (int) field(r, "numElements") == 4096 / 36;
    }

    static void batchSizesAndClamp() {
        Settings.load(SelfTest.write("enabled=true\nvboBatchKb=1\n")); BatchSize.init(); assert BatchSize.kb == 8;
        Settings.load(SelfTest.write("enabled=true\nvboBatchKb=99999\n")); BatchSize.init(); assert BatchSize.kb == 1792;
        assert BatchSize.elementBytes(1792) / 28 == 65536 && BatchSize.indexBytes(1792) == 2 * 65536 + 16;
        Settings.load(SelfTest.write("enabled=false\nvboBatchKb=512\n")); BatchSize.init(); assert BatchSize.kb == 0 && !BatchSize.guard.on;
        BatchSize.resetForTest();
    }

    static void batchInstallsOnEmptyBuffers() throws Exception {
        BatchSize.resetForTest(); BatchSize.kb = 64; BatchSize.guard.on = true;
        zombie.core.opengl.VBORenderer r = vbo();
        callSetFormat(r);
        java.nio.ByteBuffer e = (java.nio.ByteBuffer) field(r, "elements");
        assert e.capacity() == 65536 && e.order() == java.nio.ByteOrder.nativeOrder() && (int) field(r, "numElements") == 65536 / 36;
        assert ((java.nio.ByteBuffer) field(r, "indices")).capacity() == BatchSize.indexBytes(64);
        BatchSize.resetForTest();
    }

    static void batchKeepsBuffersMidBatch() throws Exception {
        BatchSize.resetForTest(); BatchSize.kb = 64; BatchSize.guard.on = true;
        zombie.core.opengl.VBORenderer r = vbo();
        r.addQuad(0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 1, 1, 0, 1, 0, 0, 1, 1f, 1f, 1f, 1f);   // 144 bytes written, no flush
        callSetFormat(r);
        assert ((java.nio.ByteBuffer) field(r, "elements")).capacity() == 4096;
        BatchSize.resetForTest();
    }
}
