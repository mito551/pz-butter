package pzbutter;

/** Plan 9 checks (sprite buffers and GPU; Task 1: keys and template, Task 2: size classes, Task 3: combined texture release, Task 4: batch size, Task 5: fast quads). Run from SelfTest.main. Later tasks append their calls to runAll(). */
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
        sizeClassTable();
        orphanReallocsOnClassChange();
        subdataOnlyGrows();
        uploadGuardTripsAfterThree();
        sizeClassesOffPassesThrough();
        uploadUsesClassesOnTheGlThread();
        uploadPassesOffThread();
        sizeClassInitReadsKeys();
        fastQuadBytesAreStock();
        fastQuadFallsBackBeforeFlush();
        fastQuadOtherModesStock();
        fastQuadFallsBackWhenIndicesFull();
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

    // ---- Task 2: vboSizeClasses ----

    static final class FakeUploader implements UploadSizer.Uploader {
        final StringBuilder log = new StringBuilder();
        public void alloc(int type, long bytes, int usage) { log.append("A").append(bytes).append(' '); }
        public void sub(int type, java.nio.ByteBuffer d) { log.append("S").append(d.remaining()).append(' '); }
    }

    static void sizeClassTable() {
        long m = 65536;
        assert UploadSizer.sizeClass(1, m) == m && UploadSizer.sizeClass(m, m) == m && UploadSizer.sizeClass(m + 1, m) == 131072;
        assert UploadSizer.sizeClass(131072, m) == 131072 && UploadSizer.sizeClass(1_000_000, m) == 1 << 20;
        assert UploadSizer.sizeClass(Long.MAX_VALUE, m) == Long.MAX_VALUE;
    }

    static void orphanReallocsOnClassChange() {
        int O = UploadSizer.ORPHAN, A = UploadSizer.ALLOC, R = UploadSizer.RESPEC;
        assert UploadSizer.decide(false, false, 0, 4096, 65536, O) == A;
        assert UploadSizer.decide(true, false, 65536, 4096, 65536, O) == R;
        assert UploadSizer.decide(true, false, 131072, 4096, 65536, O) == A;      // orphan follows the class down as well
        assert UploadSizer.decide(true, true, 65536, 4096, 65536, O) == A;        // a new GL name never inherits a capacity
    }

    static void subdataOnlyGrows() {
        int S = UploadSizer.SUBDATA;
        assert UploadSizer.decide(true, false, 131072, 4096, 65536, S) == UploadSizer.SUB;
        assert UploadSizer.decide(true, false, 65536, 70000, 131072, S) == UploadSizer.ALLOC;
    }

    static void uploadGuardTripsAfterThree() {
        UploadSizer.Guard3 g = new UploadSizer.Guard3();
        long s = 1_000_000_000L, t = 0;
        for (int w = 0; w < 2; w++) { assert !g.add(300_000_000L, t += s); }      // two windows over 250 ms/s
        assert !g.add(100_000_000L, t += s);                                     // one under: the count restarts
        assert !g.add(300_000_000L, t += s) && !g.add(300_000_000L, t += s) && g.add(300_000_000L, t += s);
    }

    static void sizeClassesOffPassesThrough() {
        UploadSizer.resetForTest();
        assert !GpuPatches.onBufferData(null, null) && UploadSizer.uploads == 0;
    }

    static void uploadUsesClassesOnTheGlThread() throws Exception {
        UploadSizer.resetForTest(); FakeUploader f = new FakeUploader(); UploadSizer.gl = f;
        zombie.core.VBO.IGLBufferObject was = zombie.core.VBO.GLVertexBufferObject.funcs;
        zombie.core.VBO.GLVertexBufferObject.funcs = new zombie.core.VBO.GLBufferObject15();
        Thread was2 = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try {
            UploadSizer.mode = UploadSizer.ORPHAN; UploadSizer.minBytes = 65536; UploadSizer.guard.on = true;
            zombie.core.VBO.GLVertexBufferObject v = new zombie.core.VBO.GLVertexBufferObject(4096, 34962, 35040);   // no GL in the constructor
            assert GpuPatches.onBufferData(v, java.nio.ByteBuffer.allocateDirect(4000));
            assert GpuPatches.onBufferData(v, java.nio.ByteBuffer.allocateDirect(3000));
            assert GpuPatches.onBufferData(v, java.nio.ByteBuffer.allocateDirect(100_000));
            assert f.log.toString().equals("A65536 S4000 A65536 S3000 A131072 S100000 ") : f.log;   // orphan: same size re-specified (A65536), then a class up
            assert UploadSizer.uploads == 3 && UploadSizer.reallocs == 2;
            assert !GpuPatches.onBufferData(v, java.nio.ByteBuffer.allocateDirect(0));            // empty: stock
            // subdata: a smaller batch writes into the bigger storage, a bigger one allocates the next class
            UploadSizer.mode = UploadSizer.SUBDATA; f.log.setLength(0);
            assert GpuPatches.onBufferData(v, java.nio.ByteBuffer.allocateDirect(500));
            assert GpuPatches.onBufferData(v, java.nio.ByteBuffer.allocateDirect(200_000));
            assert f.log.toString().equals("S500 A262144 S200000 ") : f.log;
            // a funcs that is not GLBufferObject15 leaves the upload to the game
            zombie.core.VBO.GLVertexBufferObject.funcs = null;
            assert !GpuPatches.onBufferData(v, java.nio.ByteBuffer.allocateDirect(10));
        } finally {
            zombie.core.VBO.GLVertexBufferObject.funcs = was; PresentLog.renderThread = was2; UploadSizer.resetForTest();
        }
    }

    static void uploadPassesOffThread() throws Exception {
        UploadSizer.resetForTest(); FakeUploader f = new FakeUploader(); UploadSizer.gl = f;
        Thread was = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try {
            UploadSizer.mode = UploadSizer.ORPHAN; UploadSizer.guard.on = true;
            boolean[] got = new boolean[1];
            Thread t = new Thread(() -> got[0] = GpuPatches.onBufferData(null, java.nio.ByteBuffer.allocateDirect(10)));
            t.start(); t.join(5000);
            assert !got[0] && UploadSizer.passed == 1 && f.log.length() == 0;
        } finally { PresentLog.renderThread = was; UploadSizer.resetForTest(); }
    }

    static void sizeClassInitReadsKeys() {
        Settings.load(SelfTest.write("enabled=true\nvboSizeClasses=Subdata\nvboSizeClassMinKb=100\n")); UploadSizer.init();
        assert UploadSizer.mode == UploadSizer.SUBDATA && UploadSizer.minBytes == 64 * 1024 && UploadSizer.guard.on;
        Settings.load(SelfTest.write("enabled=true\nvboSizeClasses=orphan\nvboSizeClassMinKb=1\n")); UploadSizer.init();
        assert UploadSizer.mode == UploadSizer.ORPHAN && UploadSizer.minBytes == 4 * 1024;
        Settings.load(SelfTest.write("enabled=true\nvboSizeClasses=banana\n")); UploadSizer.init();
        assert UploadSizer.mode == UploadSizer.OFF && !UploadSizer.guard.on;
        Settings.load(SelfTest.write("enabled=false\nvboSizeClasses=orphan\n")); UploadSizer.init();
        assert UploadSizer.mode == UploadSizer.OFF && !UploadSizer.guard.on;
        Settings.load(SelfTest.write("enabled=true\n"));
        UploadSizer.resetForTest();
    }

    // ---- Task 5: vboFastQuads ----

    static byte[] bytes(java.nio.ByteBuffer b) { byte[] o = new byte[b.position()]; b.duplicate().flip().get(o); return o; }

    static void fastQuadBytesAreStock() throws Exception {
        QuadWriter.resetForTest(); QuadWriter.guard.on = true; QuadWriter.init();
        java.util.Random rnd = new java.util.Random(9);
        for (int trial = 0; trial < 200; trial++) {
            zombie.core.opengl.VBORenderer a = vbo(), b = vbo();
            float dx = rnd.nextFloat(), dy = -rnd.nextFloat(), dz = rnd.nextFloat() * 3;
            a.setOffset(dx, dy, dz); b.setOffset(dx, dy, dz);
            int quads = 1 + rnd.nextInt(28);                         // at most 28 quads of 144 bytes stay below the 4,068-byte isFull mark: never a flush
            for (int i = 0; i < quads; i++) {
                float[] q = new float[24];
                for (int j = 0; j < 24; j++) q[j] = (float) (rnd.nextGaussian() * Math.pow(10, rnd.nextInt(6) - 3));
                a.addQuad(q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7], q[8], q[9], q[10], q[11], q[12], q[13], q[14], q[15], q[16], q[17], q[18], q[19], q[20], q[21], q[22], q[23]);
                assert QuadWriter.tryQuad(b, q);
            }
            assert java.util.Arrays.equals(bytes((java.nio.ByteBuffer) field(a, "elements")), bytes((java.nio.ByteBuffer) field(b, "elements")));
            assert java.util.Arrays.equals(bytes((java.nio.ByteBuffer) field(a, "indices")), bytes((java.nio.ByteBuffer) field(b, "indices")));
            assert (int) field(field(a, "runInProgress"), "vertexCount") == (int) field(field(b, "runInProgress"), "vertexCount");
        }
        QuadWriter.resetForTest();
    }

    /** The game's own predicates, called reflectively without a flush: whether adding one quad would flush. */
    static boolean stockWouldFlush(zombie.core.opengl.VBORenderer r) throws Exception {
        java.lang.reflect.Method room = zombie.core.opengl.VBORenderer.class.getDeclaredMethod("hasRoomFor", int.class), full = zombie.core.opengl.VBORenderer.class.getDeclaredMethod("isFull");
        room.setAccessible(true); full.setAccessible(true);
        if (!(boolean) room.invoke(r, 4)) return true;
        java.nio.ByteBuffer e = (java.nio.ByteBuffer) field(r, "elements"); Object run = field(r, "runInProgress");
        java.lang.reflect.Field vc = run.getClass().getDeclaredField("vertexCount"); vc.setAccessible(true);
        int p0 = e.position(), c0 = vc.getInt(run), es = (int) field(r, "elementSize");
        try {
            for (int k = 0; k < 4; k++) { e.position(p0 + k * es); vc.setInt(run, c0 + k); if ((boolean) full.invoke(r)) return true; }
            return false;
        } finally { e.position(p0); vc.setInt(run, c0); }
    }

    static void fastQuadFallsBackBeforeFlush() throws Exception {
        QuadWriter.resetForTest(); QuadWriter.guard.on = true; QuadWriter.init();
        for (int pos = 0; pos <= 4096 - 36; pos += 36) for (int vc = 0; vc < 8; vc++) {
            zombie.core.opengl.VBORenderer r = vbo();
            ((java.nio.ByteBuffer) field(r, "elements")).position(pos);
            Object run = field(r, "runInProgress"); java.lang.reflect.Field f = run.getClass().getDeclaredField("vertexCount"); f.setAccessible(true); f.setInt(run, vc);
            boolean stock = stockWouldFlush(r);
            assert QuadWriter.flushWouldFire(7, vc, pos, 4096, 36, 4096 / 36) == stock : pos + "/" + vc;
            if (stock) assert !QuadWriter.tryQuad(r, new float[24]) && ((java.nio.ByteBuffer) field(r, "elements")).position() == pos;
        }
        QuadWriter.resetForTest();
    }

    static void fastQuadOtherModesStock() throws Exception {
        QuadWriter.resetForTest(); QuadWriter.guard.on = true; QuadWriter.init();
        zombie.core.opengl.VBORenderer r = vbo();
        Object run = field(r, "runInProgress"); java.lang.reflect.Field m = run.getClass().getDeclaredField("mode"); m.setAccessible(true);
        for (int mode : new int[] {1, 4}) { m.setInt(run, mode); assert !QuadWriter.tryQuad(r, new float[24]); }
        assert !GpuPatches.onAddQuad(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        QuadWriter.resetForTest();
    }

    /** A full index buffer leaves the quad to the game (which throws as it always did) before a byte of the elements changes. */
    static void fastQuadFallsBackWhenIndicesFull() throws Exception {
        QuadWriter.resetForTest(); QuadWriter.guard.on = true; QuadWriter.init();
        zombie.core.opengl.VBORenderer r = vbo();
        setField(r, "indices", java.nio.ByteBuffer.allocateDirect(6).order(java.nio.ByteOrder.nativeOrder()));
        assert !QuadWriter.tryQuad(r, new float[24]) && ((java.nio.ByteBuffer) field(r, "elements")).position() == 0;
        QuadWriter.resetForTest();
    }
}
