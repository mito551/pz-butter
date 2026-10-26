package pzbutter;

/** Plan 11a: world-load keys. */
final class LoadTest {
    private LoadTest() {}

    static void run() throws Exception {
        idChecksSkippedOnlyOutsideDebug();
        idChecksOffStock();
        loadingKeysOffStock();
        noLoadFadeOnlyInsideExit();
        noLoadingScreenOnlyInsideEnter();
        otherFadersStock();
        noLoadingScreenNeverLeavesBlack();
        flagClearedOnThrow();
        leaveFromOtherThreadKeepsFlag();
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
        LoadFixes.noLoadingScreenGuard.on = false;
        LoadFixes.loadExitEnter();
        LoadFixes.loadEnterEnter();
        assert !LoadPatches.onLoadFade();
        assert !LoadPatches.onLoadingScreen(null);
        assert !LoadFixes.skipLoadingScreen(0f);
        LoadFixes.loadExitLeave();
        LoadFixes.loadEnterLeave();
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

    static void noLoadingScreenOnlyInsideEnter() {
        LoadFixes.noLoadingScreenGuard.on = true;
        assert !LoadFixes.skipLoadingScreen(0f);
        LoadFixes.loadEnterEnter();
        assert LoadFixes.skipLoadingScreen(0f);
        LoadFixes.loadEnterLeave();
        assert !LoadFixes.skipLoadingScreen(0f);
        LoadFixes.noLoadingScreenGuard.on = false;
    }

    /** A null or unknown fader never skips (the shim fails safe). */
    static void otherFadersStock() {
        LoadFixes.noLoadingScreenGuard.on = true;
        LoadFixes.loadEnterEnter();
        assert !LoadPatches.onLoadingScreen(null);
        assert !LoadPatches.onLoadingScreen(new Object());   // not a ScreenFader: caught, no skip
        LoadFixes.loadEnterLeave();
        LoadFixes.noLoadingScreenGuard.on = false;
    }

    static void noLoadingScreenNeverLeavesBlack() {
        LoadFixes.noLoadingScreenGuard.on = true;
        LoadFixes.loadEnterEnter();
        assert !LoadFixes.skipLoadingScreen(1f);
        assert !LoadFixes.skipLoadingScreen(0.5f);
        LoadFixes.loadEnterLeave();
        LoadFixes.noLoadingScreenGuard.on = false;
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
            Class<?> c2 = Class.forName("pzbutter.LoadPatches$GameLoadingEnter");
            m = c2.getMethod("exit");
            a = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
            assert a != null && a.onThrowable() == Throwable.class && a.suppress() == Throwable.class;
        } catch (NoSuchMethodException | ClassNotFoundException e) { throw new AssertionError(e); }
    }

    static void loadDefaultsWritten() {
        assert Settings.render(new java.util.Properties()).contains("\nskipIdChecks=false\n");
        assert Settings.render(new java.util.Properties()).contains("\nnoLoadFade=false\n");
        assert Settings.render(new java.util.Properties()).contains("\nnoLoadingScreen=false\n");
    }
}