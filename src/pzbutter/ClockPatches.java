package pzbutter;

import java.lang.invoke.VarHandle;
import me.zed_0xff.zombie_buddy.Patch;

public final class ClockPatches {
    private ClockPatches() {}

    public static void init() { FrameGrid.init(); }

    private static VarHandle lockFpsField, uncappedField, vsyncField;
    private static boolean capsBound;

    private static boolean bindCaps() {
        if (capsBound) return lockFpsField != null && uncappedField != null && vsyncField != null;
        capsBound = true;
        lockFpsField = Game.staticField(zombie.core.PerformanceSettings.class, "lockFps", int.class);
        uncappedField = Game.staticField(zombie.core.PerformanceSettings.class, "uncappedFps", boolean.class);
        vsyncField = Game.staticField(org.lwjglx.opengl.Display.class, "vsyncEnabled", boolean.class);
        boolean ok = lockFpsField != null && uncappedField != null && vsyncField != null;
        if (!ok) {
            System.out.println("[PZButter] frameClockSmooth off: cap inputs cannot be bound");
            FrameGrid.guard.on = false;
        }
        return ok;
    }

    public static void onEnter() { }
    public static void enter() { onEnter(); }

    public static void onExit() {
        if (!FrameGrid.guard.on) return;
        try {
            if (Thread.currentThread() != FrameClock.mainThread) return;
            if (!bindCaps()) return;
            boolean vsync = vsyncField != null && (boolean) vsyncField.get();
            boolean vrrActive = Boolean.TRUE.equals(Vrr.active());
            int refresh = Caps.refreshHz;
            boolean uncapped = (boolean) uncappedField.get();
            int lockFps = (int) lockFpsField.get();
            long period = FrameGrid.periodNs(vsync, vrrActive, refresh, uncapped, lockFps);
            float stock = zombie.GameTime.instance.fpsMultiplier;
            float snapped = FrameGrid.snap(stock, period);
            zombie.GameTime.instance.fpsMultiplier = snapped;
        } catch (Throwable t) {
            FrameGrid.guard.fail(t);
        }
    }
    public static void exit() { onExit(); }

    @Patch(className = "zombie.FPSTracking", methodName = "frameStep")
    public static class FrameStep {
        @Patch.OnEnter
        public static void enter() { ClockPatches.onEnter(); }
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { ClockPatches.onExit(); }
    }

    static void resetForTest() {
        capsBound = false;
        lockFpsField = vsyncField = uncappedField = null;
        FrameGrid.resetForTest();
    }
}