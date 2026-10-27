package pzbutter;

/** Plan 13 dev hooks (devMark, devScene, ...). */
public final class DevPatches {
    private DevPatches() {}

    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.gameStates.IngameState", methodName = "enter")
    public static class IngameEnter {
        @me.zed_0xff.zombie_buddy.Patch.OnEnter
        public static void enter() {
            if (!SceneDriver.guard.on) return;
            try { SceneDriver.onWorldReady(); } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
    }

    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.gameStates.MainScreenState", methodName = "update")
    public static class MainScreenUpdate {
        @me.zed_0xff.zombie_buddy.Patch.OnEnter
        public static void enter() {
            try { SceneDriver.autoContinueTick(); } catch (Throwable t) { SceneDriver.autoGuard.fail(t); }
        }
    }

    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.input.GameKeyboard", methodName = "isKeyDown")
    public static class KeyDown {
        @me.zed_0xff.zombie_buddy.Patch.OnExit
        public static void exit(@me.zed_0xff.zombie_buddy.Patch.Argument(0) int key,
                                @me.zed_0xff.zombie_buddy.Patch.Return(readOnly = false) boolean ret) {
            if (VirtualKeys.heldCount == 0) return;
            if (VirtualKeys.held(key)) ret = true;
        }
    }
}
