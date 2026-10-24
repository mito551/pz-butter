package pzbutter;

import java.lang.invoke.VarHandle;
import zombie.characters.IsoPlayer;
import zombie.core.textures.MultiTextureFBO2;
import zombie.iso.IsoCamera;

/**
 * Plan 6 Task 5: zoomEaseMs and zoomEase. A mouse-wheel zoom (MultiTextureFBO2.doZoomScroll writes targetZoom directly)
 * moves zoom by a fixed step per frame; with the key on it follows a cubic Bezier over a fixed time instead. Only wheel
 * zooms ease: auto-zoom and anything else that writes targetZoom ends the ease and stock stepping continues. Game thread.
 */
final class ZoomEase {
    private ZoomEase() {}

    static final float[] DEFAULT_CURVE = {0.25f, 0.1f, 0.25f, 1.0f};

    static int ms;
    static float[] curve = DEFAULT_CURVE;
    private static VarHandle zoom, targetZoom;   // float[] fields of MultiTextureFBO2

    // per player (split screen: 4 slots)
    private static final float[] from = new float[4], to = new float[4];
    private static final long[] t0 = new long[4];
    private static final boolean[] active = new boolean[4];

    static void init() {
        ms = Settings.num("zoomEaseMs", 0, 0, 2000);
        curve = parse(Settings.text("zoomEase", "0.25,0.1,0.25,1.0"));
        ChunkPatches.zoomGuard.on = false;
        resetForTest();
        if (!Settings.enabled || ms <= 0) return;
        zoom = Game.field(MultiTextureFBO2.class, "zoom", float[].class);
        targetZoom = Game.field(MultiTextureFBO2.class, "targetZoom", float[].class);
        if (zoom == null || targetZoom == null) {
            System.out.println("[PZButter] zoomEaseMs off: MultiTextureFBO2 zoom handles not found");
            return;
        }
        ChunkPatches.zoomGuard.on = true;
    }

    /** Four comma-separated floats, x1 and x2 within [0, 1]; anything else gives the CSS "ease" curve. */
    static float[] parse(String s) {
        try {
            String[] parts = s.split(",");
            if (parts.length != 4) return DEFAULT_CURVE;
            float[] c = new float[4];
            for (int i = 0; i < 4; i++) {
                c[i] = Float.parseFloat(parts[i].trim());
                if (Float.isNaN(c[i]) || Float.isInfinite(c[i])) return DEFAULT_CURVE;
            }
            if (c[0] < 0f || c[0] > 1f || c[2] < 0f || c[2] > 1f) return DEFAULT_CURVE;
            return c;
        } catch (RuntimeException e) {
            return DEFAULT_CURVE;
        }
    }

    private static double axis(double a, double b, double s) {
        double u = 1.0 - s;
        return 3.0 * u * u * s * a + 3.0 * u * s * s * b + s * s * s;
    }

    private static double slope(double a, double b, double s) {
        double u = 1.0 - s;
        return 3.0 * u * u * a + 6.0 * u * s * (b - a) + 3.0 * s * s * (1.0 - b);
    }

    /** CSS cubic-bezier(x1, y1, x2, y2) at time t: solve x(s) = t (Newton, then bisection), return y(s). */
    static float bezier(float[] c, float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        double x1 = c[0], y1 = c[1], x2 = c[2], y2 = c[3];
        double s = t;
        boolean solved = false;
        for (int i = 0; i < 8; i++) {
            double err = axis(x1, x2, s) - t;
            if (Math.abs(err) < 1e-6) { solved = true; break; }
            double d = slope(x1, x2, s);
            if (Math.abs(d) < 1e-6) break;
            s -= err / d;
            if (s < 0.0 || s > 1.0) break;
        }
        if (!solved) {
            double lo = 0.0, hi = 1.0;
            s = t;
            for (int i = 0; i < 60; i++) {
                double x = axis(x1, x2, s);
                if (Math.abs(x - t) < 1e-6) break;
                if (x < t) lo = s; else hi = s;
                s = (lo + hi) * 0.5;
            }
        }
        return (float) axis(y1, y2, s);
    }

    /** The zoom to show: the target once the time is up (or the ease is off), else along the curve. */
    static float eased(float from, float to, long t0Ns, long nowNs, int ms, float[] c) {
        if (ms <= 0 || nowNs - t0Ns >= ms * 1_000_000L) return to;
        return from + (to - from) * bezier(c, (nowNs - t0Ns) / (ms * 1e6f));
    }

    static void resetForTest() {
        java.util.Arrays.fill(active, false);
    }

    static void start(int p, float fromZoom, float toZoom, long startNs) {
        if (p < 0 || p >= active.length) return;
        from[p] = fromZoom;
        to[p] = toZoom;
        t0[p] = startNs;
        active[p] = true;
    }

    /**
     * The zoom to set for player p now, or NaN when no ease is running or another writer changed the target (the ease
     * ends and stock stepping continues). The step that reaches the end returns the target itself and ends the ease.
     */
    static float step(int p, float currentTarget, long nowNs, int easeMs) {
        if (p < 0 || p >= active.length || !active[p]) return Float.NaN;
        if (currentTarget != to[p]) { active[p] = false; return Float.NaN; }
        float z = eased(from[p], to[p], t0[p], nowNs, easeMs, curve);
        if (z == to[p]) active[p] = false;
        return z;
    }

    // --- hooks ---

    /** doZoomScroll entry: the zoom and target before the wheel step, or null when the handles are missing. */
    static Object scrollEnter(Object fbo, int p) {
        float[] z = (float[]) zoom.get(fbo), t = (float[]) targetZoom.get(fbo);
        if (p < 0 || p >= z.length) return null;
        return new float[] {z[p], t[p]};
    }

    /** doZoomScroll exit: when the wheel changed the target, start an ease from the zoom shown now. */
    static void scrollExit(Object fbo, int p, Object before) {
        if (!(before instanceof float[] b)) return;
        float[] t = (float[]) targetZoom.get(fbo);
        if (t[p] != b[1]) start(p, b[0], t[p], System.nanoTime());
    }

    /** update exit: stock has stepped zoom toward the target; an active ease overwrites it. */
    static void updateExit(Object fbo) {
        int p = IsoPlayer.getPlayerIndex();
        float[] t = (float[]) targetZoom.get(fbo);
        if (p < 0 || p >= t.length) return;
        float z = step(p, t[p], System.nanoTime(), ms);
        if (Float.isNaN(z)) return;
        ((float[]) zoom.get(fbo))[p] = z;
        IsoPlayer pl = IsoPlayer.players[p];
        if (pl != null) pl.dirtyRecalcGridStackTime = 2f;
        IsoCamera.cameras[p].center();
    }
}
