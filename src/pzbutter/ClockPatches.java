package pzbutter;

import java.lang.invoke.VarHandle;
import me.zed_0xff.zombie_buddy.Patch;

/** frameClockSmooth (Plan 10b Task 6): FPSTracking.frameStep sets GameTime.fpsMultiplier = 60 / instant fps (112-119, cap 5.0 at 128-139). */
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

    /** FPSTracking.frameStep exit (GameWindow.frameStep 32, game thread). dtNs is its return value: the frame's nanoTime delta; it writes fpsMultiplier only when dtNs > 0. */
    public static void onExit(long dtNs) {
        if (!FrameGrid.guard.on || dtNs <= 0) return;
        try {
            Thread m = FrameClock.mainThread;
            if (m != null && Thread.currentThread() != m) return;
            if (!bindCaps()) return;
            boolean vsync = (boolean) vsyncField.get(), vrr = Boolean.TRUE.equals(Vrr.active());
            int refresh = Caps.refreshHz;
            long period = FrameGrid.periodNs(vsync, vrr, refresh, (boolean) uncappedField.get(), (int) lockFpsField.get());
            zombie.GameTime gt = zombie.GameTime.instance;
            if (gt == null) return;
            gt.fpsMultiplier = FrameGrid.snap(gt.fpsMultiplier, period, FrameGrid.quantized(vsync, vrr, refresh));
            if (period > 0) FrameGrid.minute(System.nanoTime());
        } catch (Throwable t) {
            FrameGrid.guard.fail(t);
        }
    }

    @Patch(className = "zombie.FPSTracking", methodName = "frameStep")
    public static class FrameStep {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Return long dtNs) { ClockPatches.onExit(dtNs); }
    }

    static void resetForTest() {
        capsBound = false;
        lockFpsField = vsyncField = uncappedField = null;
        FrameGrid.resetForTest();
    }
}
