package pzbutter;

/**
 * World-load keys (Plan 11a). {@code skipIdChecks}: {@code BuildingRoomsEditor.checkBuildingAndRoomIDs} (both overloads) only
 * reads the building and room tables and reports mismatches to the debug log (Plan 11a Task 2 Step 1), so outside debug the
 * walks are skipped.
 *
 * Not built (rulings): {@code noIntroWait} and {@code noClickToStart} need a mid-method change (route C);
 * {@code noLoadingScreen} blacked out the first continued load of a session and saved no time.
 */
public final class LoadFixes {
    private LoadFixes() {}

    public static final Guard idGuard = new Guard("skipIdChecks");

    public static final Guard noLoadFadeGuard = new Guard("noLoadFade");

    static void init() {
        idGuard.on = Settings.flag("skipIdChecks");
        noLoadFadeGuard.on = Settings.flag("noLoadFade");
    }

    /** True when the walk is skipped: the game is not in debug. */
    static boolean skipIdCheck(boolean debug) {
        return !debug;
    }

    // Scoped flag: set by the GameLoadingState.exit() holder (only with noLoadFade on) for their duration, on the thread that runs them.
    // A leave from another thread does not clear the owner's flag.
    public static volatile boolean loadExitActive;
    public static volatile Thread loadExitOwner;

    public static void loadExitEnter() { if (!noLoadFadeGuard.on) return; loadExitOwner = Thread.currentThread(); loadExitActive = true; }
    public static void loadExitLeave() { if (loadExitOwner == Thread.currentThread()) { loadExitActive = false; loadExitOwner = null; } }
    public static boolean inLoadExit() { return loadExitActive && loadExitOwner == Thread.currentThread(); }


    /** True when the pre-load fade to black is skipped (only inside GameLoadingState.exit). */
    static boolean skipLoadFade() {
        return noLoadFadeGuard.on && inLoadExit();
    }

}