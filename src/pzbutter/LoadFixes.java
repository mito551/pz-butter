package pzbutter;

/**
 * World-load keys (Plan 11a). {@code skipIdChecks}: {@code BuildingRoomsEditor.checkBuildingAndRoomIDs} (both overloads) only
 * reads the building and room tables and reports mismatches to the debug log (Plan 11a Task 2 Step 1), so outside debug the
 * walks are skipped.
 *
 * {@code noIntroWait} and {@code noClickToStart} are deferred to route C (no hook).
 */
public final class LoadFixes {
    private LoadFixes() {}

    public static final Guard idGuard = new Guard("skipIdChecks");

    public static final Guard noLoadFadeGuard = new Guard("noLoadFade");
    public static final Guard noLoadingScreenGuard = new Guard("noLoadingScreen");

    static void init() {
        idGuard.on = Settings.flag("skipIdChecks");
        noLoadFadeGuard.on = Settings.flag("noLoadFade");
        noLoadingScreenGuard.on = Settings.flag("noLoadingScreen");
    }

    /** True when the walk is skipped: the game is not in debug. */
    static boolean skipIdCheck(boolean debug) {
        return !debug;
    }

    // ---- scoped flag for GameLoadingState.exit() ----
    public static volatile boolean loadExitActive;
    public static volatile Thread loadExitOwner;

    public static void loadExitEnter() { loadExitActive = true; loadExitOwner = Thread.currentThread(); }
    public static void loadExitLeave() { loadExitActive = false; loadExitOwner = null; }
    public static boolean inLoadExit() { return loadExitActive && loadExitOwner == Thread.currentThread(); }

    // ---- scoped flag for GameLoadingState.enter() ----
    public static volatile boolean loadEnterActive;
    public static volatile Thread loadEnterOwner;

    public static void loadEnterEnter() { loadEnterActive = true; loadEnterOwner = Thread.currentThread(); }
    public static void loadEnterLeave() { loadEnterActive = false; loadEnterOwner = null; }
    public static boolean inLoadEnter() { return loadEnterActive && loadEnterOwner == Thread.currentThread(); }

    /** True when the pre-load fade to black is skipped (only inside GameLoadingState.exit). */
    static boolean skipLoadFade() {
        return noLoadFadeGuard.on && inLoadExit();
    }

    /** True when the fade from black during the new-game load is skipped (only inside GameLoadingState.enter, and only at alpha 0). */
    static boolean skipLoadingScreen() {
        return noLoadingScreenGuard.on && inLoadEnter();
    }

    static boolean skipLoadingScreen(Object fader) {
        if (!(noLoadingScreenGuard.on && inLoadEnter())) return false;
        float alpha = alphaOf(fader);
        return alpha == 0f;
    }

    static boolean skipLoadingScreen(Object fader, float alpha) {
        if (!(noLoadingScreenGuard.on && inLoadEnter())) return false;
        return alpha == 0f;
    }

    private static float alphaOf(Object fader) {
        if (fader == null) return 0f;
        if (fader instanceof zombie.ui.ScreenFader sf) return sf.getAlpha();
        try {
            java.lang.reflect.Method m = fader.getClass().getMethod("getAlpha");
            Object v = m.invoke(fader);
            if (v instanceof Number n) return n.floatValue();
        } catch (Throwable ignored) {}
        return 0f;
    }
}