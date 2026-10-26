package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/** Plan 11 world-load hooks: one holder per game method; each advice calls one public static shim here. */
public final class LoadPatches {
    private LoadPatches() {}

    /** BuildingRoomsEditor.checkBuildingAndRoomIDs entry (both overloads): true skips the walk. */
    public static boolean onIdCheck() {
        if (!LoadFixes.idGuard.on) return false;
        try { return LoadFixes.skipIdCheck(zombie.core.Core.debug); }
        catch (Throwable t) { LoadFixes.idGuard.fail(t); return false; }
    }

    @Patch(className = "zombie.buildingRooms.BuildingRoomsEditor", methodName = "checkBuildingAndRoomIDs")
    public static class CheckBuildingAndRoomIDs {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return LoadPatches.onIdCheck(); }
    }
}
