package pzbutter;

/** Plan 11a: world-load keys. */
final class LoadTest {
    private LoadTest() {}

    static void run() {
        idChecksSkippedOnlyOutsideDebug();
        idChecksOffStock();
        loadingKeysOffStock();
        noLoadFadeOnlyInsideExit();
        noLoadingScreenOnlyInsideEnter();
        otherFadersStock();
        noLoadingScreenNeverLeavesBlack();
        flagClearedOnThrow();
        loadingKeyDecisions();
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
        assert !LoadPatches.onLoadingScreen();
        LoadFixes.loadExitLeave();
        LoadFixes.loadEnterLeave();
    }

    static void noLoadFadeOnlyInsideExit() {
        LoadFixes.noLoadFadeGuard.on = true;
        LoadFixes.loadExitLeave();
        assert !LoadFixes.skipLoadFade(); // outside exit -> no skip
        LoadFixes.loadExitEnter();
        assert LoadFixes.skipLoadFade(); // inside exit -> skip
        LoadFixes.loadExitLeave();
        LoadFixes.noLoadFadeGuard.on = false;
    }

    static void noLoadingScreenOnlyInsideEnter() {
        LoadFixes.noLoadingScreenGuard.on = true;
        LoadFixes.loadEnterLeave();
        class F { float getAlpha() { return 0f; } }
        // alpha not consulted outside enter: still no skip
        assert !LoadFixes.skipLoadingScreen(null);
        LoadFixes.loadEnterEnter();
        assert LoadFixes.skipLoadingScreen(null); // inside enter, null treated as alpha 0
        LoadFixes.loadEnterLeave();
        LoadFixes.noLoadingScreenGuard.on = false;
    }

    static void otherFadersStock() {
        // a ScreenFader call outside the loading state never skips
        LoadFixes.noLoadFadeGuard.on = true;
        LoadFixes.noLoadingScreenGuard.on = true;
        LoadFixes.loadExitLeave();
        LoadFixes.loadEnterLeave();
        assert !LoadPatches.onLoadFade();
        assert !LoadPatches.onLoadingScreen(new Object());
        LoadFixes.noLoadFadeGuard.on = false;
        LoadFixes.noLoadingScreenGuard.on = false;
    }

    static void noLoadingScreenNeverLeavesBlack() {
        LoadFixes.noLoadingScreenGuard.on = true;
        LoadFixes.loadEnterEnter();
        // alpha 1 -> no skip (would leave screen black)
        class F {
            public float getAlpha() { return 1f; }
        }
        assert !LoadFixes.skipLoadingScreen(new F());
        assert !LoadPatches.onLoadingScreen(new F());
        LoadFixes.loadEnterLeave();
        LoadFixes.noLoadingScreenGuard.on = false;
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

    static void loadingKeyDecisions() {
        LoadFixes.noLoadFadeGuard.on = false;
        assert !LoadFixes.skipLoadFade();
        LoadFixes.noLoadFadeGuard.on = true;
        LoadFixes.loadExitEnter();
        assert LoadFixes.skipLoadFade();
        LoadFixes.loadExitLeave();
        LoadFixes.noLoadFadeGuard.on = false;

        LoadFixes.noLoadingScreenGuard.on = false;
        assert !LoadFixes.skipLoadingScreen();
        LoadFixes.noLoadingScreenGuard.on = true;
        LoadFixes.loadEnterEnter();
        assert LoadFixes.skipLoadingScreen();
        LoadFixes.loadEnterLeave();
        LoadFixes.noLoadingScreenGuard.on = false;
    }

    static void loadDefaultsWritten() {
        assert Settings.render(new java.util.Properties()).contains("\nskipIdChecks=false\n");
        assert Settings.render(new java.util.Properties()).contains("\nnoLoadFade=false\n");
        assert Settings.render(new java.util.Properties()).contains("\nnoLoadingScreen=false\n");
    }
}