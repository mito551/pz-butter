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

    /** ScreenFader.startFadeToBlack entry: true skips the pre-load fade (only inside GameLoadingState.exit). */
    public static boolean onLoadFade() {
        if (!LoadFixes.noLoadFadeGuard.on) return false;
        try { return LoadFixes.skipLoadFade(); }
        catch (Throwable t) { LoadFixes.noLoadFadeGuard.fail(t); return false; }
    }

    /** ScreenFader.startFadeFromBlack entry: true skips the fade (only inside GameLoadingState.enter, only at alpha 0). */
    public static boolean onLoadingScreen(Object fader) {
        if (!LoadFixes.noLoadingScreenGuard.on) return false;
        try { return fader != null && LoadFixes.skipLoadingScreen(((zombie.ui.ScreenFader) fader).getAlpha()); }
        catch (Throwable t) { LoadFixes.noLoadingScreenGuard.fail(t); return false; }
    }

    @Patch(className = "zombie.buildingRooms.BuildingRoomsEditor", methodName = "checkBuildingAndRoomIDs")
    public static class CheckBuildingAndRoomIDs {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return LoadPatches.onIdCheck(); }
    }

    @Patch(className = "zombie.ui.ScreenFader", methodName = "startFadeToBlack")
    public static class FadeToBlack {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return LoadPatches.onLoadFade(); }
    }

    @Patch(className = "zombie.ui.ScreenFader", methodName = "startFadeFromBlack")
    public static class FadeFromBlack {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) { return LoadPatches.onLoadingScreen(self); }
    }

    @Patch(className = "zombie.gameStates.GameLoadingState", methodName = "exit")
    public static class GameLoadingExit {
        @Patch.OnEnter
        public static void enter() { LoadFixes.loadExitEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { LoadFixes.loadExitLeave(); }
    }

    @Patch(className = "zombie.gameStates.GameLoadingState", methodName = "enter")
    public static class GameLoadingEnter {
        @Patch.OnEnter
        public static void enter() { LoadFixes.loadEnterEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { LoadFixes.loadEnterLeave(); }
    }
}