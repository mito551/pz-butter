package pzbutter;

/**
 * Frame caps and swap interval (SPEC 3.8, 3.12).
 * vrrCap: with VRR, uncapped or a cap above the panel becomes refresh - refresh^2/3600 (224 at 240 Hz), so frames
 * stay inside the VRR range. reflexCapFps: with reflexSleep and vsync, the cap is that formula (0) or the number
 * given, so frames don't queue behind vsync. vsyncAdaptive: swap interval -1 (a late frame tears).
 */
final class Caps {
    static final Guard guard = new Guard("frame caps");
    static volatile boolean vrrCap, reflex, adaptive;
    static volatile int reflexCapFps;
    static volatile int refreshHz;             // the window's monitor, read on the render thread
    static volatile int[] monitorRect;         // x, y, w, h of that monitor

    private Caps() {}

    static void init() {
        vrrCap = Settings.flag("vrrCap");
        reflex = Settings.flag("reflexSleep");
        reflexCapFps = Settings.num("reflexCapFps", 0, 0, 1000);
        adaptive = Settings.flag("vsyncAdaptive");
        guard.on = vrrCap || reflex || adaptive;
    }

    static int vrrCapFps(int refreshHz) { return (refreshHz * 3600 - refreshHz * refreshHz) / 3600; }

    /** The cap to use instead of the game's, or 0 to keep the game's. */
    static int capFor(int lockFps, boolean uncapped, int refreshHz, boolean vrrOn, boolean vsyncOn, boolean reflexOn,
                      int reflexCapFps) {
        if (refreshHz <= 0) return 0;
        if (vrrOn) {
            int c = vrrCapFps(refreshHz);
            if (uncapped || lockFps > refreshHz) return c;
        }
        if (reflexOn && vsyncOn) {
            int c = reflexCapFps > 0 ? reflexCapFps : vrrCapFps(refreshHz);
            if (uncapped || lockFps > c) return c;
        }
        return 0;
    }

    static int swapInterval(boolean vsync, boolean adaptive, boolean tearSupported) {
        if (!vsync) return 0;
        return adaptive && tearSupported ? -1 : 1;
    }

    /** The index of the rectangle {x, y, w, h} that holds (cx, cy), or -1. */
    static int monitorAt(int[][] rects, int cx, int cy) {
        for (int i = 0; i < rects.length; i++) {
            int[] r = rects[i];
            if (cx >= r[0] && cx < r[0] + r[2] && cy >= r[1] && cy < r[1] + r[3]) return i;
        }
        return -1;
    }
}
