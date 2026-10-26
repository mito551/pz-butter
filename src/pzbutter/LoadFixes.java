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

    // Scoped flags: set by GameLoadingState.exit() / enter() holders for their duration, on the thread that runs them.
    // A leave from another thread does not clear the owner's flag.
    public static volatile boolean loadExitActive;
    public static volatile Thread loadExitOwner;
    public static volatile boolean loadEnterActive;
    public static volatile Thread loadEnterOwner;

    public static void loadExitEnter() { loadExitOwner = Thread.currentThread(); loadExitActive = true; }
    public static void loadExitLeave() { if (loadExitOwner == Thread.currentThread()) { loadExitActive = false; loadExitOwner = null; } }
    public static boolean inLoadExit() { return loadExitActive && loadExitOwner == Thread.currentThread(); }

    public static void loadEnterEnter() { loadEnterOwner = Thread.currentThread(); loadEnterActive = true; }
    public static void loadEnterLeave() { if (loadEnterOwner == Thread.currentThread()) { loadEnterActive = false; loadEnterOwner = null; } }
    public static boolean inLoadEnter() { return loadEnterActive && loadEnterOwner == Thread.currentThread(); }

    /** True when the pre-load fade to black is skipped (only inside GameLoadingState.exit). */
    static boolean skipLoadFade() {
        return noLoadFadeGuard.on && inLoadExit();
    }

    /**
     * True when the fade from black is skipped: only inside GameLoadingState.enter, and only when the fader is already
     * clear (alpha 0), so a skip can never leave the screen black.
     */
    static boolean skipLoadingScreen(float alpha) {
        return noLoadingScreenGuard.on && inLoadEnter() && alpha == 0f;
    }
}