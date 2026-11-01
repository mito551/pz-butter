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
}