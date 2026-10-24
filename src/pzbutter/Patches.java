package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import pzbutter.luabudget.Budget;

public final class Patches {
    private Patches() {}

    @Patch(className = "zombie.GameWindow", methodName = "frameStep")
    public static class Frame {
        @Patch.OnEnter
        public static void enter() { LuaPatches.onFrameYield(); DeliveryPatches.onFrameGate(); FrameClock.enter(); DeliveryPatches.onFrameStarted(); Budget.frameStart(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { FrameClock.exit(); DeliveryPatches.onFrameEnd(); Budget.frameEnd(); LuaPatches.onFrameEndFiles(); LogPatches.onFrameEnd(); ZombiePatches.onFrameEnd(); }
    }
}
