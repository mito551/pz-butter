package pzbutter;

/** Plan 9 checks (sprite buffers and GPU; Task 1: keys and template, Task 2: size classes, Task 3: combined texture release, Task 4: batch size, Task 5 was fast quads, dropped). Run from SelfTest.main. Later tasks append their calls to runAll(). */
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
        removedFastQuadsKeyIsKept();
        slotWaitsForUnsignalledFrame();
        slotSameFrameFencesNow();
        perBatchFenceMode();
        slotBudgetClamp();
        fenceRingOrderAndWrap();
        slotRotation();
        persistentMapRotatesNames();
        persistentWaitsForUnsignalledFrame();
        persistentSameFrameFencesNow();
        persistentNotCapableStaysStock();
        detachGivesFreshName();
        persistentOffOrOtherThreadPassThrough();
        mapHolderIsStrict();
        persistentGuardOffDetachesAtNextMap();
        persistentPerBatchFences();
        persistentContextChangeDetaches();
        persistentCreateFailureStaysStock();
        persistentMappedBufferStaysStock();
        persistentWaitTimeoutDetaches();
        persistentWaitErrorDetaches();
        persistentInitReadsKeys();
        namePoolHandsOutDriverNames();
        shapeMemoNeverRemembersIncomplete();
        uniformCacheDropsRelinkedProgram();
        programRecordUnknownPassesThrough();
        checkCounterCadence();
        noSyncShimsAnswerFromRecords();
        fboStatusOnlyInsideInit();
        noSyncOffOrOtherThreadAsks();
        bakeNoSyncCheckLogsMismatch();
        bakeNoSyncCheckCorrectsUniformAndStatus();
        noSyncStalePendingIsNotTaken();
        noSyncInitReadsKeys();
        noSyncHoldersTargetStock();
        gpuTimerReadsThreeFramesLate();
        gpuTimerSkipsUnavailable();
        gpuLoadFeedsBudget();
        gpuTimerIncompleteAndNotCapable();
        gpuTimerShimsGateOnThreadAndKey();
        gpuTimerInitNeedsAdaptive();
        gpuStatsLineCountsTheMinute();
        gpuStatsTickCadence();
        gpuShimsOffTouchNothing();
        gpuStatsInitReadsKey();
    }

    static final String[] PLAN9_FLAGS = {"persistentVbo", "smartTextureRelease", "bakeNoSync", "bakeNoSyncCheck", "bakeBudgetGpu", "gpuStats"};

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
                "vboSizeClasses=off", "vboSizeClassMinKb=64", "smartTextureRelease=false", "bakeNoSync=false",
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

    // vboFastQuads was dropped (no gain on the hooked path): a settings file that still names it keeps the line (as an unknown key) and loads
    static void removedFastQuadsKeyIsKept() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("vboFastQuads", "true");
        String t = Settings.render(p);
        assert !Settings.render(new java.util.Properties()).contains("vboFastQuads") && t.contains("\nvboFastQuads=true\n") : t;
        Settings.load(SelfTest.write("enabled=true\nvboFastQuads=true\n"));
        GpuPatches.init();
        Settings.load(SelfTest.write("enabled=true\n"));
        GpuPatches.init();
        assert !UploadSizer.guard.on && !BatchSize.guard.on;
    }

    // ---- Task 6: slot and fence cores ----

    static void slotWaitsForUnsignalledFrame() {
        assert SlotRing.decide(-1, 10, -1, true, false) == SlotRing.FREE;
        assert SlotRing.decide(7, 10, 7, true, false) == SlotRing.FREE;
        assert SlotRing.decide(8, 10, 7, true, false) == SlotRing.WAIT_FRAME;
        assert SlotRing.decide(2, 10, -1, true, false) == SlotRing.FREE;          // 8 swaps old: reused without asking
        assert SlotRing.decide(3, 10, -1, true, false) == SlotRing.WAIT_FRAME;
    }

    static void slotSameFrameFencesNow() {
        assert SlotRing.decide(10, 10, 9, true, false) == SlotRing.FENCE_NOW;
    }

    static void perBatchFenceMode() {
        assert SlotRing.decide(5, 10, -1, false, true) == SlotRing.FREE;
        assert SlotRing.decide(5, 10, 9, false, false) == SlotRing.WAIT_BATCH;   // the frame fence does not count in per-batch mode
        assert SlotRing.decide(-1, 10, -1, false, false) == SlotRing.FREE;
    }

    static void slotBudgetClamp() {
        long mb = 1L << 20;
        assert SlotRing.slots(4, 10 * mb, SlotRing.BUDGET) == 4 && SlotRing.slots(99, mb, SlotRing.BUDGET) == 8;
        assert SlotRing.slots(8, 10 * mb, SlotRing.BUDGET) == 6 && SlotRing.slots(0, mb, SlotRing.BUDGET) == 1;
        assert SlotRing.slots(4, 70 * mb, SlotRing.BUDGET) == 1;                 // never below one
        long debug = 256L * (262144 + 21843);                                    // Core.debug: 256 x (256 KB + its index buffer)
        assert SlotRing.slots(4, debug, SlotRing.BUDGET) == 1;
        long normal = 128L * (65536 + 5460) + 48L * (65536 + 6144);              // the two rings of a normal run: 12.5 MB a slot
        assert SlotRing.slots(4, normal, SlotRing.BUDGET) == 4 && SlotRing.slots(8, normal, SlotRing.BUDGET) == 5;
    }

    static void fenceRingOrderAndWrap() {
        SlotRing.FenceRing r = new SlotRing.FenceRing();
        for (long s = 0; s < 5; s++) assert r.put(s, 100 + s) == 0;
        long[] out = new long[16];
        assert r.pollOrder(out) == 5 && out[0] == 0 && out[4] == 4;
        r.signalled(0); r.signalled(1);
        assert r.completed == 1 && r.handleFor(1) == 0 && r.handleFor(2) == 102;
        assert r.pollOrder(out) == 3 && out[0] == 2;
        for (long s = 5; s < 18; s++) r.put(s, 100 + s);
        assert r.put(18, 118) == 102 && r.handleFor(2) == 0;                     // 16 swaps later the old handle comes back for deletion
    }

    static void slotRotation() {
        SlotRing.Buf b = new SlotRing.Buf(4);
        int[] seen = new int[6];
        for (int i = 0; i < 6; i++) { seen[i] = SlotRing.next(b); b.cur = seen[i]; }
        assert java.util.Arrays.equals(seen, new int[] {0, 1, 2, 3, 0, 1});
    }

    // ---- Task 7: persistentVbo ----

    /** GL for persistentVbo without a context: names from 100 up, fences 1, 2, 3, ..., a log of the calls. */
    static final class FakeGl implements PersistentVbo.Gl {
        boolean capable = true, awaitResult = true, awaitThrows, mapNull, storageError;
        int nextName = 100, storageCalls, pendingError;
        long fences, ctx = 1;
        final java.util.List<String> log = new java.util.ArrayList<>();
        final java.util.Set<Long> signalledSet = new java.util.HashSet<>();
        final java.util.List<Long> awaited = new java.util.ArrayList<>();
        final java.util.List<Long> deletedFences = new java.util.ArrayList<>();
        public boolean capable() { return capable; }
        public int genBuffer() { return nextName++; }
        public void storage(int type, long size) { storageCalls++; if (storageError) pendingError = 1282; }
        public java.nio.ByteBuffer mapPersistent(int type, long size) { return mapNull ? null : java.nio.ByteBuffer.allocateDirect((int) size); }
        public void bind(int type, int name) { log.add("bind " + type + " " + name); }
        public void unmapAndDelete(int type, int name) { log.add("unmapDelete " + type + " " + name); }
        public void deleteBuffer(int name) { log.add("delete " + name); }
        public long fence() { return ++fences; }
        public boolean signalled(long f) { return signalledSet.contains(f); }
        public boolean await(long f, long ns) { awaited.add(f); if (awaitThrows) throw new IllegalStateException("wait failed"); return awaitResult; }
        public void deleteFence(long f) { deletedFences.add(f); }
        public int error() { int e = pendingError; pendingError = 0; return e; }
        public long context() { return ctx; }
    }

    static zombie.core.VBO.GLVertexBufferObject ringVbo() {
        zombie.core.VBO.GLVertexBufferObject v = new zombie.core.VBO.GLVertexBufferObject(65536, 34962, 35040);
        try { java.lang.reflect.Field f = v.getClass().getDeclaredField("id"); f.setAccessible(true); f.setInt(v, 7); } catch (Exception e) { throw new RuntimeException(e); }
        return v;
    }

    static int idOf(Object v) throws Exception { return (int) field(v, "id"); }

    /** persistentVbo on over a FakeGl, the calling thread as the render thread; ends with everything restored. */
    interface Body { void run(FakeGl g) throws Exception; }

    static void withPersistent(boolean frameSync, int slots, Body body) throws Exception {
        PersistentVbo.resetForTest();
        FakeGl g = new FakeGl();
        PersistentVbo.gl = g;
        PersistentVbo.guard.on = true; PersistentVbo.frameSync = frameSync; PersistentVbo.wantSlots = slots; PersistentVbo.ringBytes = 1 << 20;
        Thread was = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try { body.run(g); }
        finally { PresentLog.renderThread = was; PersistentVbo.resetForTest(); }
    }

    static void persistentMapRotatesNames() throws Exception {
        withPersistent(true, 4, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            java.nio.ByteBuffer[] got = new java.nio.ByteBuffer[5];
            int[] ids = new int[5];
            for (int i = 0; i < 5; i++) {
                assert GpuPatches.onMapEnter(v);
                got[i] = GpuPatches.onMapExit(null); ids[i] = idOf(v);
                assert GpuPatches.onUnmapEnter(v) && GpuPatches.onUnmapExit(false);
                GpuPatches.onSwapDone();                                   // one batch a frame
            }
            assert ids[0] == 100 && ids[1] == 101 && ids[3] == 103 && ids[4] == 100 && got[4] == got[0];
            assert got[0].limit() == 65536 && got[0].order() == java.nio.ByteOrder.nativeOrder();
            assert g.log.contains("bind 34962 100") && g.storageCalls == 4;
            assert g.log.contains("delete 7") && PersistentVbo.saved == 5 && PersistentVbo.slotCount == 4;      // the game's own name is given back
        });
    }

    static void persistentWaitsForUnsignalledFrame() throws Exception {
        withPersistent(true, 1, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);           // swap 0, slot 0
            GpuPatches.onSwapDone();                                       // fence 1 for swap 0, not signalled
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);           // slot 0 again: written at swap 0, not complete
            assert g.awaited.contains(1L) && PersistentVbo.waits == 1;
            g.signalledSet.add(2L); GpuPatches.onSwapDone();               // the poll stops at fence 1 (not signalled)
            assert PersistentVbo.frames.completed == -1;
        });
    }

    static void persistentSameFrameFencesNow() throws Exception {
        withPersistent(true, 1, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);           // the same storage twice within one frame
            assert PersistentVbo.stalls == 1 && g.awaited.size() == 1 && g.deletedFences.equals(java.util.List.of(1L));
        });
    }

    static void persistentNotCapableStaysStock() throws Exception {
        withPersistent(true, 4, g -> {
            g.capable = false;
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            assert !GpuPatches.onMapEnter(v) && !PersistentVbo.guard.on && idOf(v) == 7 && g.storageCalls == 0;
            assert !PersistentVbo.anyAttached;
        });
    }

    static void detachGivesFreshName() throws Exception {
        withPersistent(true, 2, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);
            PersistentVbo.guard.on = false; PersistentVbo.detachAll();
            assert !GpuPatches.onMapEnter(v);                              // stock body runs on a fresh mutable name
            assert idOf(v) == 102 && g.log.contains("unmapDelete 34962 100") && g.log.contains("unmapDelete 34962 101");
            assert g.log.get(g.log.size() - 1).equals("bind 34962 102");   // the game's map() works on the bound buffer
            assert !GpuPatches.onUnmapEnter(v) && !PersistentVbo.anyAttached;
        });
    }

    static void persistentOffOrOtherThreadPassThrough() throws Exception {
        PersistentVbo.resetForTest();
        assert !GpuPatches.onMapEnter(null) && !GpuPatches.onUnmapEnter(null) && GpuPatches.onMapExit(null) == null;
        Thread was = PresentLog.renderThread;
        try { PersistentVbo.guard.on = true; PresentLog.renderThread = null; assert !GpuPatches.onMapEnter(ringVbo()); }
        finally { PresentLog.renderThread = was; PersistentVbo.resetForTest(); }
    }

    static void mapHolderIsStrict() throws Exception {
        me.zed_0xff.zombie_buddy.Patch p = GpuPatches.VboMap.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
        assert p.strictMatch() && p.methodName().equals("map");
        for (java.lang.reflect.Method m : GpuPatches.VboMap.class.getDeclaredMethods())
            for (java.lang.annotation.Annotation[] as : m.getParameterAnnotations())
                for (java.lang.annotation.Annotation a : as) assert !(a instanceof me.zed_0xff.zombie_buddy.Patch.Argument);
    }

    /** The guard going off (5 failures elsewhere) detaches every buffer at its own next map(), through the shim alone. */
    static void persistentGuardOffDetachesAtNextMap() throws Exception {
        withPersistent(true, 2, g -> {
            zombie.core.VBO.GLVertexBufferObject a = ringVbo(), b = ringVbo();
            GpuPatches.onMapEnter(a); GpuPatches.onMapExit(null); GpuPatches.onMapEnter(b); GpuPatches.onMapExit(null);
            assert idOf(a) == 100 && idOf(b) == 102;
            PersistentVbo.guard.on = false;
            assert GpuPatches.onUnmapEnter(a) && GpuPatches.onUnmapExit(false);       // an unmap before the next map() is still ours
            assert !GpuPatches.onMapEnter(a) && idOf(a) == 104 && PersistentVbo.anyAttached;
            assert !GpuPatches.onUnmapEnter(a) && !GpuPatches.onUnmapEnter(b);         // a is the game's again; b is marked, and the game's unmap of an unmapped buffer does nothing
            assert !GpuPatches.onMapEnter(b) && idOf(b) == 105 && !PersistentVbo.anyAttached;
            assert g.log.contains("unmapDelete 34962 102") && g.log.contains("unmapDelete 34962 103");
            assert !GpuPatches.onMapEnter(ringVbo()) && g.storageCalls == 4;           // no new buffer goes persistent
        });
    }

    /** A RingBuffer object without GL: sequence, vbo[] and ibo[] are all the per-batch fence reads. */
    static Object ringBuffer(zombie.core.VBO.GLVertexBufferObject[] vbo, zombie.core.VBO.GLVertexBufferObject[] ibo) throws Exception {
        Class<?> c = Class.forName("zombie.core.SpriteRenderer$RingBuffer");
        java.lang.reflect.Constructor<?> k = c.getDeclaredConstructor(); k.setAccessible(true);
        Object rb = k.newInstance();
        setField(rb, "vbo", vbo); setField(rb, "ibo", ibo); setField(rb, "sequence", 0);
        return rb;
    }

    /** One batch as the ring runs it: map both buffers, write, unmap both, render (the hook behind it). */
    static void perBatch(Object v, Object ib, Object rb) {
        GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null); GpuPatches.onMapEnter(ib); GpuPatches.onMapExit(null);
        GpuPatches.onUnmapEnter(v); GpuPatches.onUnmapEnter(ib);
        GpuPatches.onRingRendered(rb);
    }

    static void persistentPerBatchFences() throws Exception {
        withPersistent(false, 2, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo(), ib = ringVbo();
            Object rb = ringBuffer(new zombie.core.VBO.GLVertexBufferObject[] {v}, new zombie.core.VBO.GLVertexBufferObject[] {ib});
            for (int batch = 0; batch < 2; batch++) perBatch(v, ib, rb);   // slots 0 and 1, one fence behind each batch for each of the two buffers
            assert g.fences == 4 && g.awaited.isEmpty();
            perBatch(v, ib, rb);                                           // slot 0 again: its batch fence (1 for v) has not signalled
            assert g.awaited.equals(java.util.List.of(1L, 2L)) && PersistentVbo.waits == 2 && g.deletedFences.equals(java.util.List.of(1L, 2L));
            g.signalledSet.add(3L); g.signalledSet.add(4L);                // slot 1: both fences signalled, no wait, and they are given back
            perBatch(v, ib, rb);
            assert g.awaited.size() == 2 && PersistentVbo.waits == 2 && g.deletedFences.contains(3L) && g.deletedFences.contains(4L);
            long before = g.fences;
            GpuPatches.onSwapDone();                                       // frame fences are made in this mode too, for the buffers no batch fence covers
            assert g.fences == before + 1;
        });
    }

    static void persistentContextChangeDetaches() throws Exception {
        withPersistent(true, 2, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);
            g.ctx = 2; GpuPatches.onSwapDone();
            assert !PersistentVbo.guard.on && PersistentVbo.anyAttached;
            assert !GpuPatches.onMapEnter(v) && idOf(v) == 102 && g.log.stream().noneMatch(l -> l.startsWith("unmapDelete"));   // the old names are not ours to delete in the new context
        });
    }

    static void persistentCreateFailureStaysStock() throws Exception {
        withPersistent(true, 2, g -> {
            g.mapNull = true;
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            assert !GpuPatches.onMapEnter(v) && !PersistentVbo.guard.on && idOf(v) == 7 && !PersistentVbo.anyAttached;
            assert g.log.get(g.log.size() - 1).equals("bind 34962 7") && g.log.contains("delete 100");
        });
        withPersistent(true, 2, g -> {
            g.storageError = true;                                          // a GL error from glBufferStorage
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            assert !GpuPatches.onMapEnter(v) && !PersistentVbo.guard.on && idOf(v) == 7 && !PersistentVbo.anyAttached;
            assert g.log.contains("unmapDelete 34962 100") && g.log.get(g.log.size() - 1).equals("bind 34962 7") && !g.log.contains("delete 7");
        });
    }

    static void persistentMappedBufferStaysStock() throws Exception {
        withPersistent(true, 2, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            setField(v, "mapped", true);                                    // a stock map() is still open on it
            assert !GpuPatches.onMapEnter(v) && PersistentVbo.guard.on && g.storageCalls == 0 && idOf(v) == 7;
            setField(v, "mapped", false);
            assert GpuPatches.onMapEnter(v) && idOf(v) == 100;
        });
    }

    /** A fence that never signals: after the give-up time the feature goes off and the buffer is the game's again. */
    static void persistentWaitTimeoutDetaches() throws Exception {
        withPersistent(true, 1, g -> {
            PersistentVbo.giveUpNs = 20_000_000L;
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);
            GpuPatches.onSwapDone();
            g.awaitResult = false;
            assert !GpuPatches.onMapEnter(v);                              // the wait gave up
            assert !PersistentVbo.guard.on && idOf(v) == 101 && !PersistentVbo.anyAttached && g.log.contains("unmapDelete 34962 100");
            assert GpuPatches.onMapExit(null) == null && !GpuPatches.onUnmapEnter(v);
        });
    }

    /** The driver reports the wait failed: the shim detaches the buffer instead of letting the exception into the game. */
    static void persistentWaitErrorDetaches() throws Exception {
        withPersistent(true, 1, g -> {
            zombie.core.VBO.GLVertexBufferObject v = ringVbo();
            GpuPatches.onMapEnter(v); GpuPatches.onMapExit(null);
            GpuPatches.onSwapDone();
            g.awaitThrows = true;
            assert !GpuPatches.onMapEnter(v) && idOf(v) == 101 && !PersistentVbo.anyAttached;
            assert GpuPatches.onMapExit(null) == null && !GpuPatches.onUnmapEnter(v);
        });
    }

    static void persistentInitReadsKeys() {
        PersistentVbo.resetForTest();
        try {
            Settings.load(SelfTest.write("enabled=true\npersistentVbo=true\npersistentVboSlots=99\npersistentVboFrameSync=false\n"));
            PersistentVbo.init();
            assert PersistentVbo.guard.on && PersistentVbo.wantSlots == 8 && !PersistentVbo.frameSync;
            Settings.load(SelfTest.write("enabled=true\n"));
            PersistentVbo.init();
            assert !PersistentVbo.guard.on && PersistentVbo.wantSlots == 4 && PersistentVbo.frameSync;
        } finally { PersistentVbo.resetForTest(); Settings.load(SelfTest.write("enabled=true\n")); }
    }

    // ---- Tasks 8-9: bakeNoSync cores and hooks ----

    static void namePoolHandsOutDriverNames() {
        NoSync.NamePool p = new NoSync.NamePool();
        assert p.take() == -1 && p.low();
        for (int i = 1; i <= 64; i++) p.add(i);
        p.add(65); p.add(0); p.add(-3);                                           // full, or not a name: ignored
        assert p.size() == 64 && !p.low() && p.take() == 64;
        for (int i = 0; i < 48; i++) p.take();
        assert p.low() && p.size() == 15;
    }

    static void shapeMemoNeverRemembersIncomplete() {
        NoSync.ShapeMemo m = new NoSync.ShapeMemo();
        long k = NoSync.ShapeMemo.key(1024, 512, true, 1024, 512, false), k2 = NoSync.ShapeMemo.key(1024, 512, false, 0, 0, true);
        assert k != k2 && k >= 0 && !m.complete(k);
        m.remember(k, 0x8CD6); assert !m.complete(k);                             // INCOMPLETE_ATTACHMENT is never remembered
        m.remember(k, 0x8CD5); assert m.complete(k) && !m.complete(k2);
        assert NoSync.ShapeMemo.key(70000, 1, false, 0, 0, false) == -1;
        m.remember(-1, 0x8CD5); assert !m.complete(-1);
        m.enter(k); m.enter(k2); assert m.current == k; m.exit(); m.exit(); m.exit(); assert m.current == -1 && m.depth == 0;
        // the key is never negative at the field limits, and each field and boolean changes it
        long big = NoSync.ShapeMemo.key(65535, 65535, true, 32767, 32767, true);
        assert big >= 0 && big != NoSync.ShapeMemo.key(65535, 65535, true, 32767, 32767, false) && big != NoSync.ShapeMemo.key(65535, 65534, true, 32767, 32767, true);
        assert NoSync.ShapeMemo.key(1, 1, true, 32768, 1, false) == -1 && NoSync.ShapeMemo.key(1, 1, true, 0, 0, false) == -1;
        assert NoSync.ShapeMemo.key(1, 1, false, 7, 7, false) == NoSync.ShapeMemo.key(1, 1, false, 0, 0, false);
        m.remember(k, 0x8CD5); m.forget(k); assert !m.complete(k);
    }

    static void uniformCacheDropsRelinkedProgram() {
        NoSync.UniformCache c = new NoSync.UniformCache();
        assert c.get(5, "WOffset") == NoSync.UniformCache.MISS;
        c.put(5, "WOffset", 3); c.put(5, new StringBuilder("WViewport"), -1); c.put(6, "WOffset", 9);
        assert c.get(5, "WOffset") == 3 && c.get(5, "WViewport") == -1 && c.get(6, new StringBuilder("WOffset")) == 9;
        c.drop(5);
        assert c.get(5, "WOffset") == NoSync.UniformCache.MISS && c.get(6, "WOffset") == 9 && c.get(6, null) == NoSync.UniformCache.MISS;
    }

    static void programRecordUnknownPassesThrough() {
        NoSync.ProgramRecord r = new NoSync.ProgramRecord();
        assert r.answer(0x8B8D) == NoSync.UniformCache.MISS;
        r.used(0); assert r.answer(0x8B8D) == 0;
        r.used(42); assert r.answer(0x8B8D) == 42 && r.answer(0x0BA2) == NoSync.UniformCache.MISS;   // other pnames: ask the driver
        r.forget(); assert r.answer(0x8B8D) == NoSync.UniformCache.MISS;
        r.used(7); r.used(-1); assert r.answer(0x8B8D) == NoSync.UniformCache.MISS;                  // not a program: the record is lost
    }

    static void checkCounterCadence() {
        NoSync.CheckCounter c = new NoSync.CheckCounter();
        int due = 0; for (int i = 0; i < 1024; i++) if (c.due()) due++;
        assert due == 4;
        int logged = 0; for (int i = 0; i < 9; i++) if (c.log()) logged++;
        assert logged == 5 && c.mismatches == 9;
    }

    static void noSyncShimsAnswerFromRecords() {
        NoSync.resetForTest(); NoSync.guard.on = true;
        Thread was = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try {
            NoSync.textures.add(77);
            assert GpuPatches.onGenTexture() && GpuPatches.onGenTextureExit(0) == 77 && NoSync.namesPooled == 1;
            assert !GpuPatches.onGenTexture() && GpuPatches.onGenTextureExit(5) == 5;          // empty pool: the driver's name
            NoSync.framebuffers.add(31); NoSync.renderbuffers.add(41);
            assert GpuPatches.onGenFramebuffer() && GpuPatches.onGenFramebufferExit(0) == 31;
            assert GpuPatches.onGenRenderbuffer() && GpuPatches.onGenRenderbufferExit(0) == 41 && NoSync.namesPooled == 3;
            GpuPatches.onUseProgram(12);
            assert GpuPatches.onGetInteger(0x8B8D) && GpuPatches.onGetIntegerExit(0x8B8D, 0) == 12;
            assert !GpuPatches.onGetInteger(0x0BA2) && GpuPatches.onGetIntegerExit(0x0BA2, 9) == 9;
            assert !GpuPatches.onUniform(12, "WOffset") && GpuPatches.onUniformExit(12, "WOffset", 4) == 4;
            assert GpuPatches.onUniform(12, "WOffset") && GpuPatches.onUniformExit(12, "WOffset", 0) == 4;
            GpuPatches.onProgramGone(12);
            assert !GpuPatches.onUniform(12, "WOffset");
            GpuPatches.onUniformExit(12, "WOffset", 8);
            assert NoSync.uniformsAnswered == 1 && NoSync.programAnswered == 1 && NoSync.asked == 2;
        } finally { PresentLog.renderThread = was; NoSync.resetForTest(); }
    }

    static void fboStatusOnlyInsideInit() {
        NoSync.resetForTest(); NoSync.guard.on = true;
        Thread was = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try {
            assert !GpuPatches.onFboStatus() && GpuPatches.onFboStatusExit(0x8CD5) == 0x8CD5;   // outside initInternal: asked, not remembered
            long k = NoSync.ShapeMemo.key(256, 256, false, 0, 0, true);
            NoSync.shapes.enter(k);
            assert !GpuPatches.onFboStatus() && GpuPatches.onFboStatusExit(0x8CD5) == 0x8CD5;   // first of its shape: asked, remembered
            NoSync.shapes.exit();
            NoSync.shapes.enter(k);
            assert GpuPatches.onFboStatus() && GpuPatches.onFboStatusExit(0) == 0x8CD5 && NoSync.fboAnswered == 1;
            NoSync.shapes.exit();
            NoSync.shapes.enter(k);                                                               // an incomplete answer is passed on and never stored
            long k2 = NoSync.ShapeMemo.key(512, 512, false, 0, 0, true);
            NoSync.shapes.exit(); NoSync.shapes.enter(k2);
            assert !GpuPatches.onFboStatus() && GpuPatches.onFboStatusExit(0x8CD6) == 0x8CD6;
            assert !GpuPatches.onFboStatus();
            NoSync.shapes.exit();
        } finally { PresentLog.renderThread = was; NoSync.resetForTest(); }
    }

    static void noSyncOffOrOtherThreadAsks() throws Exception {
        NoSync.resetForTest();
        NoSync.textures.add(77); NoSync.programs.used(3);
        assert !GpuPatches.onGenTexture() && !GpuPatches.onGetInteger(0x8B8D) && GpuPatches.onGetIntegerExit(0x8B8D, 5) == 5;
        Thread was = PresentLog.renderThread;
        try {
            NoSync.guard.on = true; PresentLog.renderThread = null;
            assert !GpuPatches.onGenTexture() && !GpuPatches.onGetInteger(0x8B8D) && NoSync.textures.size() == 1;
            // another thread sees nothing of the render thread's records, and takes no waiting answer
            PresentLog.renderThread = Thread.currentThread();
            assert GpuPatches.onGetInteger(0x8B8D);                                                  // the answer waits for its exit
            int[] got = {-2};
            Thread other = new Thread(() -> {
                got[0] = GpuPatches.onGetIntegerExit(0x8B8D, 5);
                if (GpuPatches.onGenTexture() || GpuPatches.onGetInteger(0x8B8D)) got[0] = -3;
            });
            other.start(); other.join(5000);
            assert got[0] == 5 && NoSync.textures.size() == 1;
            assert GpuPatches.onGetIntegerExit(0x8B8D, 0) == 3;                                      // still there for the render thread
        } finally { PresentLog.renderThread = was; NoSync.resetForTest(); }
    }

    static void bakeNoSyncCheckLogsMismatch() {
        NoSync.resetForTest(); NoSync.guard.on = true; NoSync.check = true;
        NoSync.driverIntegerForTest = pname -> 99;              // the seam the check uses instead of GL11C in tests
        Thread was = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try {
            GpuPatches.onUseProgram(12);
            for (int i = 0; i < 256; i++) { GpuPatches.onGetInteger(0x8B8D); GpuPatches.onGetIntegerExit(0x8B8D, 0); }
            assert NoSync.checker.mismatches == 1 && NoSync.programs.current == 99;            // corrected to the driver's answer
            NoSync.driverIntegerForTest = pname -> 99;
            for (int i = 0; i < 256; i++) { GpuPatches.onGetInteger(0x8B8D); GpuPatches.onGetIntegerExit(0x8B8D, 0); }
            assert NoSync.checker.mismatches == 1;                                              // the record agrees now
        } finally { PresentLog.renderThread = was; NoSync.resetForTest(); }
    }

    static void bakeNoSyncCheckCorrectsUniformAndStatus() {
        NoSync.resetForTest(); NoSync.guard.on = true; NoSync.check = true;
        NoSync.driverUniformForTest = (p, n) -> 6;
        NoSync.driverStatusForTest = () -> 0x8CD6;
        Thread was = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try {
            NoSync.uniforms.put(3, "WOffset", 4);
            for (int i = 0; i < 255; i++) NoSync.checker.due();                                 // the next check is the 256th
            assert GpuPatches.onUniform(3, "WOffset") && GpuPatches.onUniformExit(3, "WOffset", 0) == 6;
            assert NoSync.uniforms.get(3, "WOffset") == 6 && NoSync.checker.mismatches == 1;
            long k = NoSync.ShapeMemo.key(64, 64, false, 0, 0, false);
            NoSync.shapes.remember(k, 0x8CD5); NoSync.shapes.enter(k);
            for (int i = 0; i < 255; i++) NoSync.checker.due();
            assert GpuPatches.onFboStatus() && GpuPatches.onFboStatusExit(0) == 0x8CD6;         // the driver's answer wins, the shape is forgotten
            assert !NoSync.shapes.complete(k) && NoSync.checker.mismatches == 2;
            NoSync.shapes.exit();
        } finally { PresentLog.renderThread = was; NoSync.resetForTest(); }
    }

    static void noSyncStalePendingIsNotTaken() {
        NoSync.resetForTest(); NoSync.guard.on = true;
        Thread was = PresentLog.renderThread; PresentLog.renderThread = Thread.currentThread();
        try {
            NoSync.textures.add(77);
            assert GpuPatches.onGenTexture();                                                    // its exit never came
            assert GpuPatches.onGetIntegerExit(0x8B8D, 5) == 5 && GpuPatches.onUniformExit(1, "x", 2) == 2 && GpuPatches.onFboStatusExit(7) == 7;
            assert GpuPatches.onGenTextureExit(0) == 77;
        } finally { PresentLog.renderThread = was; NoSync.resetForTest(); }
    }

    static void noSyncInitReadsKeys() {
        NoSync.resetForTest();
        try {
            Settings.load(SelfTest.write("enabled=true\nbakeNoSync=true\nbakeNoSyncCheck=true\n"));
            NoSync.init();
            assert NoSync.guard.on && NoSync.check;
            Settings.load(SelfTest.write("enabled=true\nbakeNoSyncCheck=true\n"));
            NoSync.init();
            assert !NoSync.guard.on && !NoSync.check;                                            // the check needs the feature
        } finally { NoSync.resetForTest(); Settings.load(SelfTest.write("enabled=true\n")); }
    }

    static void noSyncHoldersTargetStock() throws Exception {
        java.util.Map<String, String> want = java.util.Map.ofEntries(
            java.util.Map.entry("GenTextures", "org.lwjgl.opengl.GL11.glGenTextures"),
            java.util.Map.entry("GetInteger", "org.lwjgl.opengl.GL11.glGetInteger"),
            java.util.Map.entry("UseProgram", "org.lwjgl.opengl.GL20.glUseProgram"),
            java.util.Map.entry("LinkProgram", "org.lwjgl.opengl.GL20.glLinkProgram"),
            java.util.Map.entry("DeleteProgram", "org.lwjgl.opengl.GL20.glDeleteProgram"),
            java.util.Map.entry("UniformLocation", "org.lwjgl.opengl.GL20.glGetUniformLocation"),
            java.util.Map.entry("GenFramebuffers", "zombie.core.textures.GLFramebufferObject30.glGenFramebuffers"),
            java.util.Map.entry("GenRenderbuffers", "zombie.core.textures.GLFramebufferObject30.glGenRenderbuffers"),
            java.util.Map.entry("FboStatus", "zombie.core.textures.GLFramebufferObject30.glCheckFramebufferStatus"),
            java.util.Map.entry("FboInit", "zombie.core.textures.TextureFBO.initInternal"));
        for (java.util.Map.Entry<String, String> e : want.entrySet()) {
            Class<?> c = null;
            for (Class<?> x : GpuPatches.class.getDeclaredClasses()) if (x.getSimpleName().equals(e.getKey())) c = x;
            assert c != null : e.getKey();
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            assert (p.className() + "." + p.methodName()).equals(e.getValue()) : e.getKey();
        }
        assert GpuPatches.GenTextures.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class).strictMatch();
        // the hooked descriptors exist in the jar: the ()I name getters, the (int, CharSequence) lookup, the (I)I query and (I)V program calls
        assert org.lwjgl.opengl.GL11.class.getMethod("glGenTextures").getReturnType() == int.class;
        assert org.lwjgl.opengl.GL11.class.getMethod("glGetInteger", int.class).getReturnType() == int.class;
        assert org.lwjgl.opengl.GL20.class.getMethod("glGetUniformLocation", int.class, CharSequence.class).getReturnType() == int.class;
        for (String m : new String[] {"glUseProgram", "glLinkProgram", "glDeleteProgram"})
            assert org.lwjgl.opengl.GL20.class.getMethod(m, int.class).getReturnType() == void.class : m;
        for (String m : new String[] {"glGenFramebuffers", "glGenRenderbuffers"})
            assert zombie.core.textures.GLFramebufferObject30.class.getMethod(m).getReturnType() == int.class : m;
        assert zombie.core.textures.GLFramebufferObject30.class.getMethod("glCheckFramebufferStatus", int.class).getReturnType() == int.class;
        java.lang.reflect.Method init = zombie.core.textures.TextureFBO.class.getDeclaredMethod("initInternal",
            zombie.interfaces.ITexture.class, zombie.interfaces.ITexture.class, boolean.class);
        assert java.lang.reflect.Modifier.isPrivate(init.getModifiers());
    }

    // ---- Task 10: GPU frame time ----

    static final class FakeQueries implements GpuTimer.Queries {
        final java.util.Map<Integer, Long> v = new java.util.HashMap<>();
        final java.util.Set<Integer> ready = new java.util.HashSet<>();
        long clock;
        boolean capable = true;
        public boolean capable() { return capable; }
        public void stamp(int id) { v.put(id, clock); }
        public boolean available(int id) { return ready.contains(id); }
        public long value(int id) { return v.get(id); }
        public int[] gen(int n) { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = 10 + i; return a; }
    }

    static void gpuTimerReadsThreeFramesLate() {
        GpuTimer.resetForTest(); FakeQueries q = new FakeQueries(); GpuTimer.q = q; GpuTimer.guard.on = true;
        for (int f = 0; f < 3; f++) { q.clock = f * 10_000_000L; GpuTimer.frameBegin(); q.clock += 4_000_000L; GpuTimer.frameEnd(); }
        assert GpuTimer.lastGpuMs == -1;                                          // nothing readable before frame 3
        q.ready.addAll(q.v.keySet());
        q.clock = 30_000_000L; GpuTimer.frameBegin();
        assert Math.abs(GpuTimer.lastGpuMs - 4.0) < 1e-9;                          // frame 0's begin-to-end
        assert GpuTimer.samples == 1 && GpuTimer.skipped == 0;
        assert GpuTimer.slot(5) == 1 && GpuTimer.slot(4) == 0 && GpuTimer.readable(5) == 2 && GpuTimer.readable(2) < 0;
        GpuTimer.resetForTest();
    }

    static void gpuTimerSkipsUnavailable() {
        GpuTimer.resetForTest(); FakeQueries q = new FakeQueries(); GpuTimer.q = q; GpuTimer.guard.on = true;
        for (int f = 0; f < 6; f++) { GpuTimer.frameBegin(); GpuTimer.frameEnd(); }  // never available: never waits, never reads
        assert GpuTimer.lastGpuMs == -1 && GpuTimer.skipped >= 3;
        // the end of a pair that was not ready is not read later either (its slot was cleared), a later ready pair is
        q.ready.addAll(q.v.keySet());
        for (int f = 0; f < 4; f++) { GpuTimer.frameBegin(); GpuTimer.frameEnd(); q.ready.addAll(q.v.keySet()); }
        assert GpuTimer.lastGpuMs >= 0 && GpuTimer.samples >= 1;
        GpuTimer.resetForTest();
    }

    static void gpuLoadFeedsBudget() {
        GpuTimer.resetForTest();
        assert GpuTimer.loadMs(5.0) == 5.0;
        GpuTimer.guard.on = true; GpuTimer.bakeMode = true; GpuTimer.lastGpuMs = 12.5;
        assert GpuTimer.loadMs(5.0) == 12.5 && GpuTimer.loadMs(20.0) == 20.0;
        assert BakeHold.adaptive(8, 8, GpuTimer.loadMs(5.0), 1000.0 / 120) == 4;   // a GPU-bound frame halves the bake budget
        GpuTimer.guard.on = false;
        assert GpuTimer.loadMs(5.0) == 5.0;                                        // key off: the game thread's step alone
        GpuTimer.resetForTest();
    }

    static void gpuTimerIncompleteAndNotCapable() {
        GpuTimer.resetForTest(); FakeQueries q = new FakeQueries(); GpuTimer.q = q; GpuTimer.guard.on = true;
        // frame 0 never ended (a second frame start replaces it); frames 1 and 2 complete and ready
        GpuTimer.frameBegin(); GpuTimer.frameBegin(); GpuTimer.frameEnd();
        GpuTimer.frameBegin(); GpuTimer.frameEnd();
        q.ready.addAll(q.v.keySet());
        GpuTimer.frameBegin();                                                     // frame 3 reads frame 0: incomplete, skipped
        assert GpuTimer.lastGpuMs == -1 && GpuTimer.skipped == 1;
        GpuTimer.frameEnd(); GpuTimer.frameEnd();                                  // a stray end does nothing
        GpuTimer.resetForTest();
        FakeQueries none = new FakeQueries(); none.capable = false; GpuTimer.q = none; GpuTimer.guard.on = true;
        GpuTimer.frameBegin();
        assert !GpuTimer.guard.on && none.v.isEmpty();                              // no timer queries on this driver: off, nothing stamped
        GpuTimer.frameEnd();
        GpuTimer.resetForTest();
    }

    static void gpuTimerShimsGateOnThreadAndKey() throws Exception {
        GpuTimer.resetForTest(); FakeQueries q = new FakeQueries(); GpuTimer.q = q;
        GpuPatches.onRenderFrameStart(); GpuPatches.onSwapBegin();               // key off
        assert q.v.isEmpty();
        GpuTimer.guard.on = true;
        Thread was = PresentLog.renderThread;
        try {
            PresentLog.renderThread = null;
            GpuPatches.onRenderFrameStart();
            assert q.v.isEmpty();                                                   // before the first swap there is no render thread
            PresentLog.renderThread = new Thread(() -> { });
            GpuPatches.onRenderFrameStart();
            assert q.v.isEmpty();                                                   // another thread passes through
            PresentLog.renderThread = Thread.currentThread();
            GpuPatches.onRenderFrameStart();
            assert q.v.size() == 1;
            GpuPatches.onSwapBegin();
            assert q.v.size() == 2;
            GpuTimer.q = new GpuTimer.Queries() {                                    // a throwing driver counts into the guard, never into the game
                public boolean capable() { throw new IllegalStateException("x"); }
                public void stamp(int id) { }
                public boolean available(int id) { return false; }
                public long value(int id) { return 0; }
                public int[] gen(int n) { return null; }
            };
            GpuTimer.resetForTestKeepQueries();
            GpuTimer.guard.on = true;
            for (int i = 0; i < 6; i++) GpuPatches.onRenderFrameStart();
            assert !GpuTimer.guard.on;
        } finally {
            PresentLog.renderThread = was; GpuTimer.resetForTest();
        }
    }

    static void gpuTimerInitNeedsAdaptive() {
        try {
            Settings.load(SelfTest.write("enabled=true\nbakeBudgetGpu=true\nbakeBudgetAdaptive=false\n"));
            GpuTimer.init();
            assert !GpuTimer.guard.on;
            Settings.load(SelfTest.write("enabled=true\nbakeBudgetGpu=true\nbakeBudgetAdaptive=true\n"));
            GpuTimer.init();
            assert GpuTimer.guard.on;
        } finally {
            Settings.load(SelfTest.write("enabled=true\n"));
            GpuTimer.init(); GpuTimer.resetForTest();
        }
    }

    // ---- Task 11: gpuStats and the off-path check ----

    static void gpuStatsLineCountsTheMinute() {
        GpuStats.resetForTest(); UploadSizer.resetForTest(); SmartRelease.resetForTest();
        UploadSizer.guard.on = true; UploadSizer.uploads = 120; UploadSizer.bytes = 3L << 20; UploadSizer.reallocs = 2;
        String a = GpuStats.line(60_000);
        assert a.contains("uploads 120 (3.0 MiB, 2 storage changes") && a.contains("textures returned -") : a;
        assert a.startsWith("[PZButter] gpu: maps -;") && a.contains("names pooled -; answers -; gpu -") : a;
        UploadSizer.uploads = 150;
        assert GpuStats.line(120_000).contains("uploads 30 (");
        GpuStats.resetForTest(); UploadSizer.resetForTest();
    }

    static void gpuStatsTickCadence() {
        GpuStats.resetForTest(); UploadSizer.resetForTest(); SmartRelease.resetForTest(); PersistentVbo.resetForTest(); NoSync.resetForTest(); GpuTimer.resetForTest();
        java.util.List<String> got = new java.util.ArrayList<>();
        GpuStats.out = got::add; GpuStats.file = t -> { };
        try {
            SmartRelease.guard.on = true; SmartRelease.released = 5; SmartRelease.skipped = 1;
            NoSync.guard.on = true; NoSync.namesPooled = 64; NoSync.fboAnswered = 7; NoSync.uniformsAnswered = 9; NoSync.programAnswered = 11; NoSync.checker.mismatches = 2;
            PersistentVbo.guard.on = true; PersistentVbo.saved = 40; PersistentVbo.waits = 3; PersistentVbo.stalls = 1; PersistentVbo.slotCount = 4;
            GpuTimer.guard.on = true; GpuTimer.samples = 2; GpuTimer.sumMs = 9.0;
            GpuStats.tick(1_000);                                // the minute starts here; what happened before it is not in the line
            GpuStats.tick(30_000);
            assert got.isEmpty();
            SmartRelease.released = 8; NoSync.fboAnswered = 17; PersistentVbo.saved = 100; GpuTimer.samples = 4; GpuTimer.sumMs = 21.0;
            GpuStats.tick(61_000);
            assert got.size() == 1;
            String l = got.get(0);
            assert l.contains("maps 60 saved, 0 waits, 0 stalls, slots 4;") && l.contains("textures returned 3 (0 already pooled)")
                && l.contains("answers fbo 10 uniform 0 shader 0 (0 mismatches)") && l.contains("gpu 6.0 ms") : l;
            GpuStats.tick(100_000);
            assert got.size() == 1;                              // not before the next 60 s
        } finally { GpuStats.resetForTest(); UploadSizer.resetForTest(); SmartRelease.resetForTest(); PersistentVbo.resetForTest(); NoSync.resetForTest(); GpuTimer.resetForTest(); }
    }

    static void gpuShimsOffTouchNothing() {
        UploadSizer.resetForTest(); SmartRelease.resetForTest(); BatchSize.resetForTest();
        PersistentVbo.resetForTest(); NoSync.resetForTest(); GpuTimer.resetForTest(); GpuStats.resetForTest();
        assert !GpuPatches.onBufferData(null, null);
        GpuPatches.onSmartCalculate(null); GpuPatches.onVboFormat(null);
        assert !GpuPatches.onMapEnter(null) && GpuPatches.onMapExit(null) == null && !GpuPatches.onUnmapEnter(null) && GpuPatches.onUnmapExit(true);
        GpuPatches.onRingRendered(null);
        assert !GpuPatches.onGenTexture() && GpuPatches.onGenTextureExit(3) == 3 && !GpuPatches.onFboStatus() && GpuPatches.onFboStatusExit(1) == 1;
        assert !GpuPatches.onUniform(1, null) && !GpuPatches.onGetInteger(0x8B8D) && GpuPatches.onGetIntegerExit(0x8B8D, 4) == 4;
        GpuPatches.onFboInitEnter(null, null, false); GpuPatches.onFboInitExit(); GpuPatches.onUseProgram(1); GpuPatches.onProgramGone(1);
        GpuPatches.onRenderFrameStart(); GpuPatches.onSwapBegin(); GpuPatches.onSwapDone();
        assert UploadSizer.uploads == 0 && SmartRelease.released == 0 && NoSync.namesPooled == 0 && GpuTimer.lastGpuMs == -1;
        assert PersistentVbo.saved == 0 && !PersistentVbo.anyAttached && NoSync.programs.current == NoSync.ProgramRecord.UNKNOWN;
        assert !GpuStats.guard.on && GpuTimer.samples == 0 && GpuTimer.skipped == 0;
    }

    static void gpuStatsInitReadsKey() {
        try {
            Settings.load(SelfTest.write("enabled=true\ngpuStats=true\n"));
            GpuStats.init();
            assert GpuStats.guard.on;
            Settings.load(SelfTest.write("enabled=true\n"));
            GpuStats.init();
            assert !GpuStats.guard.on;
        } finally { Settings.load(SelfTest.write("enabled=true\n")); GpuStats.init(); GpuStats.resetForTest(); }
    }
}
