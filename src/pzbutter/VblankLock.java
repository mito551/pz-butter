package pzbutter;

/**
 * vblankLock (SPEC 3.8): with vsync on X11 + NVIDIA, the render thread calls glXDelayBeforeSwapNV before each swap,
 * waiting until the smoothed swap time plus 0.5 ms before vblank, so the swap lands just before it and frames
 * stay locked to the display's beat. Off (one log line) on any other platform or driver.
 */
final class VblankLock {
    static final Guard guard = new Guard("vblankLock");
    static final long MARGIN_NS = 500_000L;
    static volatile long smoothedSwapNs;

    private VblankLock() {}

    static void init() {
        guard.on = Settings.flag("vblankLock");
        if (guard.on) PresentLog.guard.on = true;
    }

    static boolean available(boolean hasExtension, int platform, int x11Platform) { return hasExtension && platform == x11Platform; }

    static float seconds(long smoothedSwapNs) { return (smoothedSwapNs + MARGIN_NS) / 1e9f; }
}
