package pzbutter;

/** Plan 11a: world-load keys. */
final class LoadTest {
    private LoadTest() {}

    static void run() throws Exception {
        idChecksSkippedOnlyOutsideDebug();
        idChecksOffStock();
        loadingKeysOffStock();
        noLoadFadeThroughShim();
        noLoadFadeOnlyInsideExit();
        flagClearedOnThrow();
        leaveFromOtherThreadKeepsFlag();
        textureBufferWaitsLikeStock();
        textureBufferStockWhenZero();
        fileTransformRewritesOnlyInflight();
        fileInflightStockWhenUnset();
        fileInflightDefaultsWritten();
        filePoolResizes();
        filePoolResizeOrder();
        fileKnobsDefaultsWritten();
        pipelineOnlyDuringLoad();
        autoRuleResolves();
        shaderLookupMatchesStock();
        shaderCacheOffIsStock();
        shaderCacheFailureFallsBack();
        shaderManagerHandleBinds();
        shaderRealLookupMisses();
        shaderDefaultsWritten();
        loadDefaultsWritten();
        vehicleZonesMatchStock();
        vehicleZonesNoDebugNoStrings();
        loaderCpuFixesOffIsStock();
        loaderCpuFixesDefaultsWritten();
        stripCommentsMatchesReference();
        stripCommentsMatchesStock();
        scriptParserFastOffIsStock();
        scriptParserFastDefaultsWritten();
        modWatchVisitsLikeStock();
        modWatchLockPerDirectory();
        modWatchStopsOnNullWatcher();
        modWatchOffIsStock();
        modWatchIOExceptionStopsWatching();
        modWatchGuardOffKeepsLocking();
        modWatchAsyncDefaultsWritten();
        texCompressOffIsStock();
        encodeOnlyWhenStockWouldCompress();
        entryDroppedOnDispose();
        levelForNeedsExactMatch();
        encodeMatchesBc3();
    }

    static void idChecksSkippedOnlyOutsideDebug() {
        assert LoadFixes.skipIdCheck(false);
        assert !LoadFixes.skipIdCheck(true);
    }

    /** Off: the shim returns before touching any game class, and the walk runs. */
    static void idChecksOffStock() {
        LoadFixes.idGuard.on = false;
        assert !LoadPatches.onIdCheck();
    }

    static void loadingKeysOffStock() {
        LoadFixes.noLoadFadeGuard.on = false;
        LoadFixes.loadExitEnter();                  // off: the holder sets no flag
        assert !LoadFixes.inLoadExit();
        assert !LoadPatches.onLoadFade();
    }

    /** On: the shim skips the fade only inside GameLoadingState.exit(), on the thread that entered it. */
    static void noLoadFadeThroughShim() {
        LoadFixes.noLoadFadeGuard.on = true;
        assert !LoadPatches.onLoadFade();
        LoadFixes.loadExitEnter();
        assert LoadPatches.onLoadFade();
        LoadFixes.loadExitLeave();
        assert !LoadPatches.onLoadFade();
        LoadFixes.noLoadFadeGuard.on = false;
    }

    static void noLoadFadeOnlyInsideExit() {
        LoadFixes.noLoadFadeGuard.on = true;
        assert !LoadFixes.skipLoadFade();
        LoadFixes.loadExitEnter();
        assert LoadFixes.skipLoadFade();
        LoadFixes.loadExitLeave();
        assert !LoadFixes.skipLoadFade();
        LoadFixes.noLoadFadeGuard.on = false;
    }

    static void leaveFromOtherThreadKeepsFlag() throws Exception {
        LoadFixes.noLoadFadeGuard.on = true;
        LoadFixes.loadExitEnter();
        Thread t = new Thread(LoadFixes::loadExitLeave);
        t.start(); t.join();
        assert LoadFixes.skipLoadFade();
        boolean[] other = new boolean[1];
        Thread u = new Thread(() -> other[0] = LoadFixes.skipLoadFade());
        u.start(); u.join();
        assert !other[0];
        LoadFixes.loadExitLeave();
        LoadFixes.noLoadFadeGuard.on = false;
    }

    static void flagClearedOnThrow() {
        try {
            Class<?> c1 = Class.forName("pzbutter.LoadPatches$GameLoadingExit");
            java.lang.reflect.Method m = c1.getMethod("exit");
            me.zed_0xff.zombie_buddy.Patch.OnExit a = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
            assert a != null && a.onThrowable() == Throwable.class && a.suppress() == Throwable.class;
        } catch (NoSuchMethodException | ClassNotFoundException e) { throw new AssertionError(e); }
    }

    static void loadDefaultsWritten() {
        assert Settings.render(new java.util.Properties()).contains("\nskipIdChecks=false\n");
        assert Settings.render(new java.util.Properties()).contains("\nnoLoadFade=false\n");
        assert !Settings.render(new java.util.Properties()).contains("noLoadingScreen");
        assert Settings.render(new java.util.Properties()).contains("\nshaderCache=false\n");
        assert Settings.render(new java.util.Properties()).contains("\nloaderCpuFixes=true\n");
    }

    static void textureBufferWaitsLikeStock() {
        FileKnobs.resetForTest();
        FileKnobs.textureBufferMb = 256;
        FileKnobs.guard.on = true;
        FileKnobs.inLoad = true;
        long cap = 256L << 20;
        long[] seq = {cap + 1, cap + 1, cap + 1, cap};          // above the cap three times, then at it
        int[] i = {0}, sleeps = {0}, interrupts = {0};
        FileKnobs.bytes = () -> seq[Math.min(i[0]++, seq.length - 1)];
        FileKnobs.sleeper = ms -> {
            assert ms == 20;
            sleeps[0]++;
            if (sleeps[0] == 2) { interrupts[0]++; throw new InterruptedException(); }   // stock loops on an interrupt
        };
        assert FileKnobs.onWaitFileTask();
        assert sleeps[0] == 3 && interrupts[0] == 1 && FileKnobs.guard.on;
        assert FileKnobs.bufferCapBytes() == cap;
        FileKnobs.resetForTest();
    }

    static void textureBufferStockWhenZero() {
        FileKnobs.resetForTest();
        FileKnobs.guard.on = true;                 // e.g. fileThreads set, textureBufferMb not
        FileKnobs.bytes = () -> { throw new AssertionError("must not run"); };
        assert !FileKnobs.onWaitFileTask();        // stock's own loop runs
        assert FileKnobs.bufferCapBytes() == 52428800L;
        FileKnobs.resetForTest();
    }

    static void filePoolResizes() {
        FileKnobs.resetForTest();
        zombie.fileSystem.FileSystemImpl fs = new zombie.fileSystem.FileSystemImpl();
        var h = Game.field(zombie.fileSystem.FileSystemImpl.class, "executor", java.util.concurrent.ExecutorService.class);
        var pool = (java.util.concurrent.ThreadPoolExecutor) h.get(fs);
        try {
            int stock = pool.getCorePoolSize();
            assert FileKnobs.resize(fs, 8) != null && pool.getCorePoolSize() == 8 && pool.getMaximumPoolSize() == 8;
            assert FileKnobs.resize(fs, 2) != null && pool.getCorePoolSize() == 2 && pool.getMaximumPoolSize() == 2;   // shrink: core first
            assert FileKnobs.resize(fs, 0) == null && pool.getCorePoolSize() == 2;                                     // 0: untouched
            assert FileKnobs.resize(new Object(), 8) == null;
            assert stock == (Runtime.getRuntime().availableProcessors() > 4 ? 4 : 2);
        } finally { pool.shutdownNow(); }
        FileKnobs.resetForTest();
    }

    /** Outside GameLoadingState (enter..exit) every limit answers stock: in play nothing changes. */
    static void pipelineOnlyDuringLoad() {
        FileKnobs.resetForTest();
        FileKnobs.guard.on = true;
        FileKnobs.textureBufferMb = 256;
        FileKnobs.fileInflight = 64;
        FileKnobs.bytes = () -> { throw new AssertionError("stock must run outside the load"); };
        assert !FileKnobs.onWaitFileTask() && FileKnobs.inflight() == 16;   // before the load
        FileKnobs.onLoadEnter();
        assert FileKnobs.inflight() == 64;
        FileKnobs.bytes = () -> 0L;
        assert FileKnobs.onWaitFileTask();                                 // in the load: ours (no wait below the cap)
        FileKnobs.onLoadExit();
        FileKnobs.bytes = () -> { throw new AssertionError("stock must run outside the load"); };
        assert !FileKnobs.onWaitFileTask() && FileKnobs.inflight() == 16;   // after the load
        FileKnobs.resetForTest();
    }

    /** -1: the measured setting only where the auto pool widens (more than 8 cores); otherwise stock. Explicit values stay. */
    static void autoRuleResolves() {
        assert FileKnobs.resolveThreads(-1, 12) == 8 && FileKnobs.resolveBuffer(-1, 12) == 256 && FileKnobs.resolveInflight(-1, 12) == 64;
        assert FileKnobs.resolveThreads(-1, 8) == 0 && FileKnobs.resolveBuffer(-1, 8) == 0 && FileKnobs.resolveInflight(-1, 8) == 0;
        assert FileKnobs.resolveBuffer(128, 4) == 128 && FileKnobs.resolveInflight(32, 4) == 32 && FileKnobs.resolveThreads(6, 4) == 6;
        assert FileKnobs.resolveBuffer(0, 12) == 0 && FileKnobs.resolveInflight(0, 12) == 0 && FileKnobs.resolveThreads(0, 12) == 0;
        assert FileKnobs.resolveBuffer(5, 12) == 16 && FileKnobs.resolveInflight(2, 12) == 4;   // the floors
    }

    static void filePoolResizeOrder() {
        assert FileKnobs.resizeOrder(4, 8)[0] == 0;
        assert FileKnobs.resizeOrder(8, 4)[0] == 1;
        assert FileKnobs.resizeOrder(4, 4)[0] == 1;
    }

    static void fileKnobsDefaultsWritten() {
        java.io.File f = SelfTest.tmp("fk.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\ntextureBufferMb=-1\n") && t.contains("\nfileThreads=-1\n") : t;
        assert FileKnobs.autoThreads(12) == 8 && FileKnobs.autoThreads(16) == 8 && FileKnobs.autoThreads(10) == 6;
        assert FileKnobs.autoThreads(8) == 0 && FileKnobs.autoThreads(6) == 0 && FileKnobs.autoThreads(4) == 0 && FileKnobs.autoThreads(2) == 0;   // stock kept
    }

    static void fileTransformRewritesOnlyInflight() {
        try {
            java.io.InputStream in = ClassLoader.getSystemResourceAsStream("zombie/fileSystem/FileSystemImpl.class");
            byte[] orig = in.readAllBytes(); in.close();
            byte[] tr = FileTransform.transform(orig);
            assert tr != null;
            var cf = java.lang.classfile.ClassFile.of();
            var a = cf.parse(orig); var b = cf.parse(tr);
            assert FileTransform.sites(a.methods().stream().filter(mm -> mm.methodName().equalsString("updateAsyncTransactions")).findFirst().get()) == 2;   // exactly the two in-flight sites
            assert a.methods().size() == b.methods().size();
            for (int k = 0; k < a.methods().size(); k++) {
                var ma = a.methods().get(k); var mb = b.methods().get(k);
                String name = ma.methodName().stringValue() + ma.methodType().stringValue();
                assert name.equals(mb.methodName().stringValue() + mb.methodType().stringValue()) : name;
                java.util.List<String> ia = invokes(ma), ib = invokes(mb);
                if (name.equals("updateAsyncTransactions()V")) {
                    assert java.util.Collections.frequency(ib, "pzbutter/FileKnobs.inflight()I") == 2 : ib;
                    assert countBipush16(mb) == 0 : ib;   // no bipush 16 left there
                    java.util.List<String> ib2 = new java.util.ArrayList<>(ib);
                    ib2.removeIf(x -> x.equals("pzbutter/FileKnobs.inflight()I"));
                    java.util.List<String> ia2 = ia;
                    assert ia2.equals(ib2) : name + "\n" + ia + "\n" + ib;
                } else {
                    assert codeText(ma).equals(codeText(mb)) : name;   // every other method unchanged
                }
            }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static int countBipush16(java.lang.classfile.MethodModel m) {
        int[] n = {0};
        m.code().ifPresent(c -> { for (var e : c)
            if (e instanceof java.lang.classfile.Instruction i && i instanceof java.lang.classfile.instruction.ConstantInstruction ci
                    && ci.opcode() == java.lang.classfile.Opcode.BIPUSH && ci.constantValue() instanceof Integer v && v == 16) n[0]++; });
        return n[0];
    }

    static java.util.List<String> invokes(java.lang.classfile.MethodModel m) {
        java.util.List<String> r = new java.util.ArrayList<>();
        m.code().ifPresent(c -> { for (var e : c) if (e instanceof java.lang.classfile.instruction.InvokeInstruction i)
            r.add(i.owner().asInternalName() + "." + i.name().stringValue() + i.type().stringValue()); });
        return r;
    }

    static String codeText(java.lang.classfile.MethodModel m) {
        StringBuilder sb = new StringBuilder();
        m.code().ifPresent(c -> { for (var e : c) if (e instanceof java.lang.classfile.Instruction i) sb.append(i).append('\n'); });
        return sb.toString();
    }

    static void fileInflightStockWhenUnset() {
        FileKnobs.resetForTest();
        assert FileKnobs.inflight() == 16;
        FileKnobs.resetForTest();
    }

    static void fileInflightDefaultsWritten() {
        java.io.File f = SelfTest.tmp("fi.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nfileInflight=-1\n") : t;
    }

    static class FakeModel { public boolean isStatic; public Object effect; }

    static void shaderLookupMatchesStock() {
        ShaderReuse.resetForTest();
        ShaderReuse.guard.on = true;
        Object shader = new Object();
        ShaderReuse.lookup = args -> (Boolean) args[1] ? (args[0].equals("x") && !(Boolean) args[2] ? shader : null) : null;
        FakeModel m = new FakeModel();
        m.isStatic = true;
        assert ShaderReuse.onCreateShader(m, "x");
        assert m.effect == shader;
        ShaderReuse.resetForTest();
        ShaderReuse.guard.on = true;
        ShaderReuse.lookup = args -> null;
        FakeModel m2 = new FakeModel();
        m2.isStatic = true;
        assert !ShaderReuse.onCreateShader(m2, "x");
        assert m2.effect == null;
        ShaderReuse.resetForTest();
    }

    static void shaderCacheOffIsStock() {
        ShaderReuse.resetForTest();
        ShaderReuse.guard.on = false;
        FakeModel m = new FakeModel();
        m.isStatic = true;
        ShaderReuse.lookup = args -> new Object();
        assert !ShaderReuse.onCreateShader(m, "x");
        assert m.effect == null;
        assert ShaderReuse.reused == 0 && ShaderReuse.sent == 0;
        ShaderReuse.resetForTest();
    }

    static void shaderCacheFailureFallsBack() {
        ShaderReuse.resetForTest();
        ShaderReuse.guard.on = true;
        FakeModel m = new FakeModel();
        ShaderReuse.lookup = args -> { throw new RuntimeException("boom"); };
        for (int i = 0; i < 5; i++) assert !ShaderReuse.onCreateShader(m, "x");
        assert !ShaderReuse.guard.on;
        ShaderReuse.resetForTest();
    }

    /** The real path: the bound handle on ShaderManager.instance (no GL: an empty list) misses, so stock would build the shader. */
    static void shaderRealLookupMisses() {
        ShaderReuse.resetForTest();
        assert ShaderReuse.lookup.apply(new Object[] {"pzbutterNoSuchShader", true, false}) == null;
        ShaderReuse.resetForTest();
    }

    static void shaderManagerHandleBinds() {
        assert Game.method(zombie.core.skinnedmodel.shader.ShaderManager.class, "getShader",
            java.lang.invoke.MethodType.methodType(zombie.core.skinnedmodel.shader.Shader.class, String.class, boolean.class, boolean.class)) != null;
    }

    static void shaderDefaultsWritten() {
        java.io.File f = SelfTest.tmp("shader.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nshaderCache=false\n") : t;
    }

    static final class VZone {
        String name, type;
        int x, y, w, h, id;
        VZone(String name, String type, int x, int y, int w, int h) {
            this.name = name; this.type = type; this.x = x; this.y = y; this.w = w; this.h = h;
        }
        int getX() { return x; }
        int getY() { return y; }
    }

    static void stockDedupe(java.util.List<VZone> zones, java.util.function.BiConsumer<VZone, VZone> debug) {
        int i = 0;
        while (true) {
            if (i >= zones.size()) break;
            boolean isUnique = true;
            int k = 0;
            while (k < i) {
                VZone a = zones.get(i);
                VZone b = zones.get(k);
                if (a.getX() == b.getX() && a.getY() == b.getY() && a.h == b.h && a.w == b.w) {
                    isUnique = false;
                    if (debug != null) debug.accept(a, b);
                    break;
                }
                k++;
            }
            if (isUnique) {
                i++;
            } else {
                zones.remove(i);
            }
        }
    }

    static void vehicleZonesMatchStock() {
        java.util.Random r = new java.util.Random(12345);
        String[] names = {"a", "b", "c"};
        String[] types = {"car", "trailer"};
        for (int t = 0; t < 200; t++) {
            int n = r.nextInt(301);
            java.util.ArrayList<VZone> a = new java.util.ArrayList<>();
            java.util.ArrayList<VZone> b = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                int x = r.nextInt(6);
                int y = r.nextInt(6);
                int w = r.nextInt(4) + 1;
                int h = r.nextInt(4) + 1;
                VZone z1 = new VZone(names[r.nextInt(names.length)], types[r.nextInt(types.length)], x, y, w, h);
                VZone z2 = new VZone(z1.name, z1.type, z1.x, z1.y, z1.w, z1.h);
                z1.id = i; z2.id = i;   // the same zone in both lists: debug lines must name the same removed and kept zones
                a.add(z1);
                b.add(z2);
            }
            java.util.ArrayList<String> da = new java.util.ArrayList<>();
            java.util.ArrayList<String> db = new java.util.ArrayList<>();
            java.util.function.BiConsumer<VZone, VZone> ca = (x1, y1) -> da.add(x1.id + "->" + y1.id);
            java.util.function.BiConsumer<VZone, VZone> cb = (x1, y1) -> db.add(x1.id + "->" + y1.id);
            stockDedupe(a, ca);
            VehicleZones.guard.on = true;
            VehicleZones.dedupe(b,
                z -> (((long) z.x) << 32) | (z.y & 0xffffffffL),
                z -> (((long) z.h) << 32) | (z.w & 0xffffffffL),
                cb);
            assert a.size() == b.size() : "size mismatch test " + t;
            for (int i = 0; i < a.size(); i++) {
                VZone x1 = a.get(i), y1 = b.get(i);
                assert x1.id == y1.id : "elem mismatch";   // the same survivors, in the same order
            }
            assert da.equals(db) : "debug sequence mismatch";
            VehicleZones.guard.on = false;
        }
        VehicleZones.resetForTest();
    }

    static void vehicleZonesNoDebugNoStrings() {
        VehicleZones.guard.on = true;
        java.util.ArrayList<VZone> zones = new java.util.ArrayList<>();
        zones.add(new VZone("a", "car", 1, 1, 2, 2));
        zones.add(new VZone("b", "car", 1, 1, 2, 2));
        VehicleZones.dedupe(zones, z -> (((long) z.x) << 32) | (z.y & 0xffffffffL), z -> (((long) z.h) << 32) | (z.w & 0xffffffffL), null);
        assert zones.size() == 1 && zones.get(0).name.equals("a");   // the first is kept
        VehicleZones.guard.on = false;
        VehicleZones.resetForTest();
    }

    static void loaderCpuFixesOffIsStock() {
        VehicleZones.guard.on = false;
        assert !LoadPatches.onVehicleZones(new Object());
        VehicleZones.resetForTest();
    }

    static void loaderCpuFixesDefaultsWritten() {
        java.io.File f = SelfTest.tmp("vz.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nloaderCpuFixes=true\n") : t;
    }

    /** Test-only transcription of stock stripComments, offset by offset. */
    static final class ScriptCommentsRef {
        static String stock(String s) {
            StringBuilder sb = new StringBuilder();
            sb.setLength(0);
            sb.append(s);
            int i1 = sb.lastIndexOf("*/");
            while (i1 != -1) {
                int i2 = sb.lastIndexOf("/*", i1 - 1);
                if (i2 == -1) break;
                int i3 = sb.lastIndexOf("*/", i1 - 1);
                if (i3 > i2) {
                    int i4 = i2;
                    i2 = sb.lastIndexOf("/*", i2 - 2);
                    if (i2 == -1) break;
                    i3 = sb.lastIndexOf("*/", i4 - 2);
                    while (i3 > i2 && i2 != -1) {
                        i4 = i2;
                        i2 = sb.lastIndexOf("/*", i2 - 2);
                        if (i2 == -1) break;
                        i3 = sb.lastIndexOf("*/", i4 - 2);
                    }
                    if (i2 == -1) break;
                }
                if (i2 == -1) break;
                sb.replace(i2, i1 + 2, "");
                i1 = sb.lastIndexOf("*/", i2);
            }
            return sb.toString();
        }
    }

    static void stripCommentsMatchesReference() {
        java.util.Random r = new java.util.Random(42);
        char[] alpha = {'/', '*', 'a', '\n', ' '};
        for (int t = 0; t < 10500; t++) {
            int len = t < 10000 ? r.nextInt(61) : r.nextInt(3001);   // short ones, then long ones (many suffix chunks)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) sb.append(alpha[r.nextInt(alpha.length)]);
            String s = sb.toString();
            String ref = ScriptCommentsRef.stock(s);
            assert ScriptComments.strip(s).equals(ref) : "mismatch " + t + ": " + s;
            assert ref.equals(zombie.scripting.ScriptParser.stripComments(s)) : "transcription differs from stock " + t;
        }
    }

    static void stripCommentsMatchesStock() {
        ScriptComments.guard.on = true;
        java.nio.file.Path root = java.nio.file.Paths.get("/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid/media/scripts");
        java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
        try (java.util.stream.Stream<java.nio.file.Path> w = java.nio.file.Files.walk(root)) {
            w.filter(p -> p.toString().endsWith(".txt")).forEach(p -> {
                try {
                    String text = java.nio.file.Files.readString(p);
                    assert ScriptComments.strip(text).equals(zombie.scripting.ScriptParser.stripComments(text)) : p;
                    count.incrementAndGet();
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert count.get() > 0;
        try {   // the one large commented file the boot spends its time on (TileGeometryFile.parseFile)
            String geo = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "/home/mitori/Sonic/SteamLibrary/steamapps/common/ProjectZomboid/projectzomboid/media/tileGeometry.txt"));
            assert geo.length() >= LoadPatches.STRIP_MIN_CHARS;
            assert ScriptComments.strip(geo).equals(zombie.scripting.ScriptParser.stripComments(geo)) : "tileGeometry.txt";
            assert LoadPatches.onScriptComments(geo) != null;          // large: ours
            assert LoadPatches.onScriptComments("a/*b*/c") == null;    // small: stock
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        ScriptComments.guard.on = false;
        ScriptComments.resetForTest();
    }

    static void scriptParserFastOffIsStock() {
        ScriptComments.guard.on = false;
        assert LoadPatches.onScriptComments("a/*b*/c") == null;
        ScriptComments.resetForTest();
    }

    static void scriptParserFastDefaultsWritten() {
        java.io.File f = SelfTest.tmp("sp.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nscriptParserFast=true\n") : t;
    }

    static void modWatchVisitsLikeStock() throws Exception {
        ModWatch.resetForTest();
        ModWatch.guard.on = true;
        ModWatch.watcherSupplier = () -> new Object();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mwv");
        java.nio.file.Files.createDirectories(root.resolve("a/b"));
        java.nio.file.Files.writeString(root.resolve("file.txt"), "x");
        java.nio.file.Files.createSymbolicLink(root.resolve("link"), root.resolve("a"));   // stock's walkFileTree does not follow links
        java.util.List<java.nio.file.Path> visited = new java.util.ArrayList<>();
        ModWatch.register = visited::add;
        ModWatch.runSync(root.toString());
        assert visited.size() == 3 && !visited.contains(root.resolve("link")) : visited;
        ModWatch.resetForTest();
    }

    static void modWatchLockPerDirectory() {
        ModWatch.resetForTest();
        ModWatch.guard.on = true;
        ModWatch.watcherSupplier = () -> new Object();
        java.nio.file.Path root;
        try { root = java.nio.file.Files.createTempDirectory("mwl"); java.nio.file.Files.createDirectories(root.resolve("a/b")); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        ModWatch.register = p -> { assert ModWatch.lock.getHoldCount() == 1; n.incrementAndGet(); };   // held once, per directory, not for the walk
        assert !ModWatch.lock.isHeldByCurrentThread();
        ModWatch.runSync(root.toString());
        assert n.get() == 3;
        assert !ModWatch.lock.isHeldByCurrentThread();
        ModWatch.resetForTest();
    }

    static void modWatchStopsOnNullWatcher() {
        ModWatch.resetForTest();
        ModWatch.guard.on = true;
        ModWatch.watcherSupplier = () -> null;
        java.nio.file.Path root;
        try { root = java.nio.file.Files.createTempDirectory("mws"); java.nio.file.Files.createDirectories(root.resolve("a/b")); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        ModWatch.register = p -> n.incrementAndGet();
        ModWatch.runSync(root.toString());
        assert n.get() == 0;
        ModWatch.resetForTest();
    }

    static void modWatchIOExceptionStopsWatching() throws Exception {
        ModWatch.resetForTest();
        ModWatch.guard.on = true;
        ModWatch.watcherSupplier = () -> new Object();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mwe");
        java.nio.file.Path locked = java.nio.file.Files.createDirectories(root.resolve("a/locked"));
        java.nio.file.Files.createDirectories(locked.resolve("inner"));
        java.nio.file.Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        boolean[] dropped = {false};
        ModWatch.onIOException = () -> dropped[0] = true;
        try {
            ModWatch.runSync(root.toString());
            assert dropped[0];   // stock's registerDirRecursive sets watcher = null on the walk's IOException
        } finally {
            java.nio.file.Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        ModWatch.resetForTest();
    }

    /** A guard turned off mid-walk (modCompat at Main) no longer drops trees: the holders keep locking while the worker is active. */
    static void modWatchGuardOffKeepsLocking() throws Exception {
        ModWatch.resetForTest();
        ModWatch.guard.on = true;
        ModWatch.active = true;
        ModWatch.watcherSupplier = () -> new Object();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mwg");
        java.nio.file.Files.createDirectories(root.resolve("a/b/c"));
        int[] n = {0};
        ModWatch.register = p -> { n[0]++; ModWatch.guard.on = false; };   // the guard turns off after the first directory
        ModWatch.runSync(root.toString());
        assert n[0] == 4 : n[0];                    // the tree still finishes
        boolean locked = ModWatch.lockEnter();      // and the game-thread holders still lock while the worker is active
        assert locked;
        ModWatch.lockExit(locked);
        ModWatch.active = false;
        assert !ModWatch.lockEnter();               // once it is done, off means off
        ModWatch.resetForTest();
    }

    static void modWatchOffIsStock() {
        ModWatch.resetForTest();
        assert !ModWatch.onAddDirectoryRecurse("/tmp/whatever");
        ModWatch.resetForTest();
    }

    static void modWatchAsyncDefaultsWritten() {
        java.io.File f = SelfTest.tmp("mw.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\nmodWatchAsync=true\n") : t;
    }

    static void texCompressOffIsStock() {
        TexCompress.resetForTest();
        TexCompress.s3tc = true;
        try { zombie.core.textures.TextureID.useCompressionOption = true; } catch (Throwable t) { }
        Object img = new Object();
        TexCompress.onPackImageExit(new Object(), img);
        TexCompress.onImageExit(img);
        TexCompress.onDispose(img);
        assert TexCompress.encoded.isEmpty();
        assert TexCompress.encodedCount == 0;
        TexCompress.resetForTest();
    }

    static void encodeOnlyWhenStockWouldCompress() {
        TexCompress.resetForTest();
        TexCompress.guard.on = true;
        TexCompress.s3tc = true;
        try {
            zombie.core.textures.TextureID.useCompressionOption = false;
            assert !TexCompress.wouldCompressImage();
            assert !TexCompress.wouldCompressPack(4);
            zombie.core.textures.TextureID.useCompressionOption = true;
            assert TexCompress.wouldCompressImage();
            assert TexCompress.wouldCompressPack(4);
            assert !TexCompress.wouldCompressPack(0);
            // guard off disables both
            TexCompress.guard.on = false;
            assert !TexCompress.wouldCompressImage();
            assert !TexCompress.wouldCompressPack(4);
        } finally {
            TexCompress.resetForTest();
            try { zombie.core.textures.TextureID.useCompressionOption = false; } catch (Throwable t) { }
        }
    }

    static void entryDroppedOnDispose() {
        TexCompress.resetForTest();
        Object key = new Object();
        TexCompress.encoded.put(key, new TexCompress.Level[0]);
        TexCompress.onDispose(key);
        assert !TexCompress.encoded.containsKey(key);
        assert TexCompress.droppedCount == 1;
        // a second dispose is a no-op
        TexCompress.onDispose(key);
        assert TexCompress.droppedCount == 1;
        TexCompress.resetForTest();
    }

    static void levelForNeedsExactMatch() {
        TexCompress.resetForTest();
        Object id = new Object();
        java.nio.ByteBuffer rgba = java.nio.ByteBuffer.allocateDirect(16);
        TexCompress.Level L = new TexCompress.Level(rgba, 2, 2, java.nio.ByteBuffer.allocateDirect(16));
        TexCompress.encoded.put(id, new TexCompress.Level[] { L });
        assert TexCompress.levelFor(id, 0, rgba, 2, 2) == L;
        assert TexCompress.levelFor(id, 0, rgba, 2, 3) == null;
        assert TexCompress.levelFor(id, 0, java.nio.ByteBuffer.allocateDirect(16), 2, 2) == null;
        assert TexCompress.levelFor(id, 1, rgba, 2, 2) == null;
        assert TexCompress.levelFor(new Object(), 0, rgba, 2, 2) == null;
        TexCompress.resetForTest();
    }

    static void encodeMatchesBc3() {
        TexCompress.resetForTest();
        TexCompress.guard.on = true;
        TexCompress.s3tc = true;
        zombie.core.textures.ImageData img = null;
        try {
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(bi, "png", bo);
            img = new zombie.core.textures.ImageData(new java.io.ByteArrayInputStream(bo.toByteArray()), false);
        } catch (Exception e) { throw new RuntimeException(e); }
        try { zombie.core.textures.TextureID.useCompressionOption = true; } catch (Throwable t) { }
        TexCompress.onImageExit(img);
        assert TexCompress.encodedCount == 1 : TexCompress.encodedCount;
        TexCompress.Level L0 = TexCompress.encoded.get(img)[0];
        java.nio.ByteBuffer copy = L0.rgba.duplicate();
        int n = L0.w * L0.h * 4;
        byte[] src = new byte[n];
        copy.rewind();
        copy.get(src);
        java.nio.ByteBuffer expected = Bc3.encode(java.nio.ByteBuffer.wrap(src), L0.w, L0.h);
        byte[] exp = new byte[expected.capacity()];
        expected.duplicate().rewind().get(exp);
        byte[] got = new byte[L0.bc3.capacity()];
        L0.bc3.duplicate().rewind().get(got);
        assert java.util.Arrays.equals(exp, got);
        try { zombie.core.textures.TextureID.useCompressionOption = false; } catch (Throwable t) { }
        TexCompress.resetForTest();
    }
}