package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

public final class CameraPatches {
    private CameraPatches() {}

    public static void init() {
        CameraPixels.init();
    }

    private static volatile float enterXf;
    private static volatile float enterYf;
    private static volatile long enterNano;

    @Patch(className = "zombie.iso.PlayerCamera", methodName = "update")
    public static class Update {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) {
            CameraPatches.onEnter(self);
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object self) {
            CameraPatches.onExit(self);
        }
    }

    public static void onEnter(Object self) {
        try {
            if (!(self instanceof zombie.iso.PlayerCamera cam)) return;
            enterXf = CameraPixels.fieldGet(cam, "rightClickXf");
            enterYf = CameraPixels.fieldGet(cam, "rightClickYf");
            enterNano = System.nanoTime();
        } catch (Throwable t) {
            CameraPixels.smoothGuard.fail(t);
        }
    }

    public static void onExit(Object self) {
        if (!CameraPixels.smoothGuard.on && !CameraPixels.pixelGuard.on) return;
        try {
            if (!(self instanceof zombie.iso.PlayerCamera cam)) return;
            float zoom = zombie.core.Core.getInstance().getZoom(cam.playerIndex);
            long dt = System.nanoTime() - enterNano;
            float xf = enterXf;
            float yf = enterYf;
            if (CameraPixels.smoothGuard.on) {
                int lockFps = zombie.core.PerformanceSettings.getLockFPS();
                xf = CameraPixels.step(enterXf, cam.rightClickTargetX, 1.875f, dt, lockFps);
                yf = CameraPixels.step(enterYf, cam.rightClickTargetY, 1.875f, dt, lockFps);
                CameraPixels.fieldSet(cam, "rightClickXf", xf);
                CameraPixels.fieldSet(cam, "rightClickYf", yf);
            }
            float newX = CameraPixels.publish(xf, zoom, CameraPixels.smoothGuard.on, CameraPixels.pixelGuard.on);
            float newY = CameraPixels.publish(yf, zoom, CameraPixels.smoothGuard.on, CameraPixels.pixelGuard.on);
            cam.rightClickX = newX;
            cam.rightClickY = newY;
        } catch (Throwable t) {
            CameraPixels.smoothGuard.fail(t);
        }
    }
}
