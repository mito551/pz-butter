package pzbutter;

/** Plan 13 dev hooks (devMark, devScene, ...). */
public final class DevPatches {
    private DevPatches() {}

    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.gameStates.IngameState", methodName = "enter")
    public static class IngameEnter {
        @me.zed_0xff.zombie_buddy.Patch.OnEnter
        public static void enter() {
            if (!SceneDriver.guard.on && !SceneDriver.sceneSet()) return;
            try { SceneDriver.onWorldReady(); } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
    }

    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.gameStates.IngameState", methodName = "exit")
    public static class IngameExit {
        @me.zed_0xff.zombie_buddy.Patch.OnEnter
        public static void enter() {
            try { SceneDriver.onWorldExit(); } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
    }

    /** Diagnostic: who removes the drive scene's car from the world (prints one stack trace for that car only). */
    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.vehicles.BaseVehicle", methodName = "removeFromWorld")
    public static class VehicleRemoved {
        @me.zed_0xff.zombie_buddy.Patch.OnEnter
        public static void enter(@me.zed_0xff.zombie_buddy.Patch.This Object self) {
            if (SceneDrive.watched == null || SceneDrive.watched != self) return;
            try {
                SceneDrive.watched = null;
                System.out.println("[PZButter] devScene drive: car removed from world by:");
                new Throwable().printStackTrace(System.out);
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
    }

    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.gameStates.GameLoadingState", methodName = "update")
    public static class GameLoadingUpdate {
        @me.zed_0xff.zombie_buddy.Patch.OnEnter
        public static void enter(@me.zed_0xff.zombie_buddy.Patch.This Object self) {
            try { SceneDriver.clickToStart(self); } catch (Throwable t) { SceneDriver.autoGuard.fail(t); }
        }
    }


    /**
     * Diagnostic: who pauses a scene. On the full list something sets game speed 0 on every frame of the settle
     * (823 re-pauses in one S4 run); the first three calls during a scene print their Lua and Java callers.
     */
    public static int pauseTraces;
    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.ui.SpeedControls", methodName = "SetCurrentGameSpeed")
    public static class SpeedSet {
        @me.zed_0xff.zombie_buddy.Patch.OnEnter
        public static void enter(@me.zed_0xff.zombie_buddy.Patch.Argument(0) int speed) {
            if (speed != 0 || pauseTraces >= 3 || !SceneDriver.sceneSet() || !SceneDriver.guard.on) return;
            try {
                pauseTraces++;
                StringBuilder sb = new StringBuilder("[PZButter] devScene: speed 0 set by:");
                String[] lua = zombie.Lua.LuaManager.getLuaStackTraceStrings(zombie.Lua.LuaManager.thread.getCurrentCoroutine());
                if (lua != null) for (String l : lua) sb.append("\n  lua ").append(l);
                StackTraceElement[] java = new Throwable().getStackTrace();
                for (int i = 1; i < Math.min(java.length, 9); i++) sb.append("\n  java ").append(java[i]);
                System.out.println(sb);
            } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
    }

    /** isKeyDown(int) only: strictMatch makes ZombieBuddy match the advice's argument list exactly (as GpuPatches.GenTextures relies on). */
    @me.zed_0xff.zombie_buddy.Patch(className = "zombie.input.GameKeyboard", methodName = "isKeyDown", strictMatch = true)
    public static class KeyDown {
        @me.zed_0xff.zombie_buddy.Patch.OnExit
        public static void exit(@me.zed_0xff.zombie_buddy.Patch.Argument(0) int key,
                                @me.zed_0xff.zombie_buddy.Patch.Return(readOnly = false) boolean ret) {
            if (VirtualKeys.heldCount == 0) return;
            try { if (VirtualKeys.held(key)) ret = true; } catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
    }
}
