package pzbutter;

import java.lang.invoke.VarHandle;
import me.zed_0xff.zombie_buddy.Patch;

/** PlayerCamera.update holder (game thread: IsoPlayer.updateInternal2 1035, IsoCamera.updateAll). */
public final class CameraPatches {
    private CameraPatches() {}

    public static void init() {
        CameraPixels.init();
    }

    static boolean bound;
    private static VarHandle xfH, yfH;
    private static float enterXf, enterYf;          // game thread only
    private static boolean centerRan;               // returnToCenter ran inside this update: keep its snap as stock
    private static final long[] lastNs = new long[4];   // per playerIndex: split screen updates up to four cameras a frame

    @Patch(className = "zombie.iso.PlayerCamera", methodName = "update")
    public static class Update {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) { CameraPatches.onEnter(self); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object self) { CameraPatches.onExit(self); }
    }

    @Patch(className = "zombie.iso.PlayerCamera", methodName = "returnToCenter")
    public static class Center {
        @Patch.OnEnter
        public static void enter() { CameraPatches.onCenter(); }
    }

    /** PlayerCamera.returnToCenter entry: it zeroes the target and snaps the look-ahead to a whole pixel near the end. */
    public static void onCenter() { if (CameraPixels.smoothGuard.on) centerRan = true; }

    private static boolean bind() {
        if (!bound) {
            bound = true;
            xfH = Game.field(zombie.iso.PlayerCamera.class, "rightClickXf", float.class);
            yfH = Game.field(zombie.iso.PlayerCamera.class, "rightClickYf", float.class);
        }
        if (xfH != null && yfH != null) return true;
        CameraPixels.smoothGuard.on = false;
        CameraPixels.pixelGuard.on = false;
        return false;
    }

    public static void onEnter(Object self) {
        if (DriveTrace.guard.on) DriveTrace.record(self);
        if (!CameraPixels.smoothGuard.on) return;
        centerRan = false;
        try {
            if (!(self instanceof zombie.iso.PlayerCamera cam) || !bind()) return;
            enterXf = (float) xfH.get(cam);
            enterYf = (float) yfH.get(cam);
        } catch (Throwable t) { CameraPixels.smoothGuard.fail(t); }
    }

    public static void onExit(Object self) {
        boolean smooth = CameraPixels.smoothGuard.on, pixels = CameraPixels.pixelGuard.on;
        if (!smooth && !pixels) return;
        try {
            if (!(self instanceof zombie.iso.PlayerCamera cam) || !bind()) return;
            float xf = (float) xfH.get(cam), yf = (float) yfH.get(cam);
            if (smooth) {
                int p = cam.playerIndex & 3;
                long now = System.nanoTime(), last = lastNs[p];
                lastNs[p] = now;
                if (!centerRan) {
                double k = CameraPixels.frameFactor(last == 0 ? 0 : now - last, zombie.core.PerformanceSettings.getLockFPS());
                xf = CameraPixels.scale(enterXf, xf, k);
                yf = CameraPixels.scale(enterYf, yf, k);
                xfH.set(cam, xf);
                yfH.set(cam, yf);
                }
            }
            float zoom = zombie.core.Core.getInstance().getZoom(cam.playerIndex);
            cam.rightClickX = CameraPixels.publish(xf, zoom, smooth, pixels);
            cam.rightClickY = CameraPixels.publish(yf, zoom, smooth, pixels);
        } catch (Throwable t) {
            (smooth ? CameraPixels.smoothGuard : CameraPixels.pixelGuard).fail(t);
        }
    }

    static void resetForTest() {
        CameraPixels.smoothGuard.on = false;
        CameraPixels.pixelGuard.on = false;
        bound = false;
        centerRan = false;
        xfH = yfH = null;
        java.util.Arrays.fill(lastNs, 0);
        DriveTrace.resetForTest();
    }
}
