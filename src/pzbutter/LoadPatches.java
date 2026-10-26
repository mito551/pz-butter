package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Plan 11a holders: advice on {@code RoomDef.isKidsRoom()} (memoised) and the clear point at the end of a building's
 * story pass.
 */
public final class LoadPatches {
    private LoadPatches() {}

    /** storyKidsRoomMemo enter: -1 means "run stock" (off or uncached); 0/1 means "skip with that answer". */
    public static int onKidsEnter(Object room) {
        if (!LoadFixes.kidsGuard.on) return -1;
        try {
            Boolean c = LoadFixes.kidsCached(room);
            if (c == null) return -1;
            return c ? 1 : 0;
        } catch (Throwable t) {
            LoadFixes.kidsGuard.fail(t);
            return -1;
        }
    }

    /** storyKidsRoomMemo exit: store the answer stock computed (only on the computed path). */
    public static void onKidsExit(Object room, boolean answer) {
        if (!LoadFixes.kidsGuard.on) return;
        try { LoadFixes.kidsStore(room, answer); }
        catch (Throwable t) { LoadFixes.kidsGuard.fail(t); }
    }

    public static void onKidsClear() {
        try { LoadFixes.kidsClear(); }
        catch (Throwable t) { LoadFixes.kidsGuard.fail(t); }
    }

    @Patch(className = "zombie.iso.RoomDef", methodName = "isKidsRoom")
    public static class KidsRoom {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Local("m") int m) {
            m = LoadPatches.onKidsEnter(self);
            return m >= 0;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Return(readOnly = false) boolean ret, @Patch.Local("m") int m) {
            if (m >= 0) ret = (m == 1);
            else LoadPatches.onKidsExit(self, ret);
        }
    }

    @Patch(className = "zombie.randomizedWorld.randomizedBuilding.RandomizedBuildingBase", methodName = "randomizeBuilding")
    public static class KidsRoomClear {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { LoadPatches.onKidsClear(); }
    }
}
