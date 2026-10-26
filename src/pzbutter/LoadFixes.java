package pzbutter;

/**
 * World-load keys (Plan 11a). {@code skipIdChecks}: {@code BuildingRoomsEditor.checkBuildingAndRoomIDs} (both overloads) only
 * reads the building and room tables and reports mismatches to the debug log (Plan 11a Task 2 Step 1), so outside debug the
 * walks are skipped. ({@code storyKidsRoomMemo} is not built: one {@code RandomizedBuildingBase.ChunkLoaded} can run several
 * stories, and no seam short of the 44 {@code randomizeBuilding} overrides can clear a memo between them.)
 */
public final class LoadFixes {
    private LoadFixes() {}

    public static final Guard idGuard = new Guard("skipIdChecks");

    static void init() {
        idGuard.on = Settings.flag("skipIdChecks");
    }

    /** True when the walk is skipped: the game is not in debug. */
    static boolean skipIdCheck(boolean debug) {
        return !debug;
    }
}
