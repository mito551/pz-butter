package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

public final class Patches {
    private Patches() {}

    @Patch(className = "zombie.GameWindow", methodName = "frameStep")
    public static class Frame {
        @Patch.OnEnter
        public static void enter() { FrameClock.enter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { FrameClock.exit(); DeliveryPatches.onFrameEnd(); }
    }
}
