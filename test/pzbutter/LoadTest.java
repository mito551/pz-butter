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
        shaderDefaultsWritten();
        loadDefaultsWritten();
        vehicleZonesMatchStock();
        vehicleZonesNoDebugNoStrings();
        loaderCpuFixesOffIsStock();
        loaderCpuFixesDefaultsWritten();
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
        int x, y, w, h;
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
                a.add(z1);
                b.add(z2);
            }
            java.util.ArrayList<String> da = new java.util.ArrayList<>();
            java.util.ArrayList<String> db = new java.util.ArrayList<>();
            java.util.function.BiConsumer<VZone, VZone> ca = (x1, y1) -> da.add(x1.x + "," + x1.y + "->" + y1.x + "," + y1.y);
            java.util.function.BiConsumer<VZone, VZone> cb = (x1, y1) -> db.add(x1.x + "," + x1.y + "->" + y1.x + "," + y1.y);
            stockDedupe(a, ca);
            VehicleZones.guard.on = true;
            VehicleZones.dedupe(b,
                z -> (((long) z.x) << 32) | (z.y & 0xffffffffL),
                z -> (((long) z.h) << 32) | (z.w & 0xffffffffL),
                cb);
            assert a.size() == b.size() : "size mismatch test " + t;
            for (int i = 0; i < a.size(); i++) {
                VZone x1 = a.get(i), y1 = b.get(i);
                assert x1.name.equals(y1.name) && x1.type.equals(y1.type) && x1.x == y1.x && x1.y == y1.y && x1.w == y1.w && x1.h == y1.h : "elem mismatch";
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
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        VehicleZones.dedupe(zones, z -> (((long) z.x) << 32) | (z.y & 0xffffffffL), z -> (((long) z.h) << 32) | (z.w & 0xffffffffL), null);
        assert calls.get() == 0;
        assert zones.size() == 1;
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
}