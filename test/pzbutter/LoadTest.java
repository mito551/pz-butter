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
        assert Settings.render(new java.util.Properties()).contains("\nloaderCpuFixes=false\n");
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
        assert t.contains("\nloaderCpuFixes=false\n") : t;
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
        assert t.contains("\nscriptParserFast=false\n") : t;
    }
}