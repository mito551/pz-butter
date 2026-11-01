package pzbutter;

import java.lang.reflect.Field;

public final class CameraPixels {
    private CameraPixels() {}

    public static final Guard smoothGuard = new Guard("driveLookSmooth");
    public static final Guard pixelGuard = new Guard("cameraScreenPixels");

    static volatile float keptXf;
    static volatile float keptYf;
    static volatile long keptNano;

    static void init() {
        try {
            smoothGuard.on = Settings.enabled && Settings.flag("driveLookSmooth");
            pixelGuard.on = Settings.enabled && Settings.flag("cameraScreenPixels");
        } catch (Throwable t) {
            smoothGuard.fail(t);
            pixelGuard.fail(t);
            smoothGuard.on = false;
            pixelGuard.on = false;
        }
    }

    /** The value written to rightClickX (and Y). */
    public static float publish(float xf, float zoom, boolean smooth, boolean screenPixels) {
        if (smooth) return xf;
        if (screenPixels && zoom < 1f) {
            return Math.round(xf / zoom) * zoom;
        }
        return (float) (int) xf;
    }

    /** Time-based look-ahead step, clamped to [0,4] stock frames. */
    public static float step(float current, float target, float stockStep, long dtNs, int lockFps) {
        double frameTime = 1_000_000_000.0 / Math.max(1, lockFps);
        double frames = dtNs / frameTime;
        if (frames < 0) frames = 0;
        if (frames > 4) frames = 4;
        float sign = (target > current) ? 1f : (target < current) ? -1f : 0f;
        float amount = stockStep * (float) frames * sign;
        float next = current + amount;
        if (sign > 0f) next = Math.min(next, target);
        if (sign < 0f) next = Math.max(next, target);
        return next;
    }

    public static float fieldGet(Object obj, String name) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(obj);
        } catch (Throwable t) {
            smoothGuard.fail(t);
            return 0f;
        }
    }

    public static void fieldSet(Object obj, String name, float value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setFloat(obj, value);
        } catch (Throwable t) {
            smoothGuard.fail(t);
        }
    }

    static void resetForTest() {
        smoothGuard.on = false;
        pixelGuard.on = false;
        keptXf = 0f;
        keptYf = 0f;
        keptNano = 0L;
    }
}
