package pzbutter;

/**
 * driveLookSmooth / cameraScreenPixels (Plan 10b Task 2): PlayerCamera.update truncates the look-ahead to whole offscreen
 * pixels (rightClickX = (int) rightClickXf, update 754-770, 943-960, 1292-1309, 1765-1782) and steps it once per call with a
 * per-frame amount that assumes the lock rate.
 */
public final class CameraPixels {
    private CameraPixels() {}

    public static final Guard smoothGuard = new Guard("driveLookSmooth");
    public static final Guard pixelGuard = new Guard("cameraScreenPixels");

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

    /** The value written to rightClickX (and Y): fractional when smooth; whole screen pixels zoomed in; else stock truncation. */
    public static float publish(float xf, float zoom, boolean smooth, boolean screenPixels) {
        if (smooth) return xf;
        if (screenPixels && zoom > 0f && zoom < 1f) return Math.round(xf / zoom) * zoom;
        return (float) (int) xf;
    }

    /**
     * How much of stock's per-call step this frame gets: the time since the previous call over the lock-rate frame, at most 1.
     * At or below the lock rate it is stock (1); above it the look-ahead moves by elapsed time. No previous call: 1.
     */
    public static double frameFactor(long dtNs, int lockFps) {
        if (dtNs <= 0 || lockFps <= 0) return 1.0;
        double k = dtNs * (double) lockFps / 1_000_000_000.0;
        return k > 1.0 ? 1.0 : k;
    }

    /** Stock moved the look-ahead from before to after this call; keep the share k of that step. */
    public static float scale(float before, float after, double k) {
        return (float) (before + (after - before) * k);
    }
}
