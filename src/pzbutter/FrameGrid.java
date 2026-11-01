package pzbutter;

/**
 * Frame clock snapped to the frame grid (SPEC Plan 10b Task 6, frameClockSmooth).
 * Each frame's simulated time becomes a whole number of grid periods; the remainder
 * carries into the next frame, so simulated time keeps pace with real time.
 */
public final class FrameGrid {
    public static final Guard guard = new Guard("frameClockSmooth");
    private FrameGrid() {}

    // carry, seconds, game thread only
    static double carry;
    static final int RING = 4096;
    static final float[] stockRel = new float[RING];
    static final float[] snapRel = new float[RING];
    static int ringPos;
    static int ringCount;
    static float stockPrev = -1f;
    static float snapPrev = -1f;
    static float maxCarryMs;

    static void init() {
        try { guard.on = Settings.enabled && Settings.flag("frameClockSmooth"); }
        catch (Throwable t) { guard.fail(t); guard.on = false; }
    }

    /** Grid period in nanoseconds. 0 means no grid (stock). */
    public static long periodNs(boolean vsync, boolean vrrActive, int refreshHz, boolean uncapped, int lockFps) {
        try {
            if (vsync && !vrrActive && refreshHz > 0) return 1_000_000_000L / refreshHz;
            if (!uncapped && lockFps > 0) return 1_000_000_000L / lockFps;
            return 0L;
        } catch (Throwable t) { return 0L; }
    }

    /** With vsync on and no VRR, frames are shown on refresh multiples: a late frame really is shown n periods later. */
    public static boolean quantized(boolean vsync, boolean vrrActive, int refreshHz) { return vsync && !vrrActive && refreshHz > 0; }

    static double meanDt;        // running mean of stock frame seconds (weight 1/16), game thread only
    static long belowCap;        // frames left stock because the game was not holding its cap (minute line)
    static boolean lastQuantized; static long lastPeriodNs;

    /** Snap a stock fpsMultiplier to the grid with vsync quantization (tests and the vsync path). */
    public static float snap(float stockMultiplier, long periodNs) { return snap(stockMultiplier, periodNs, true); }

    /**
     * Snap a stock fpsMultiplier to the grid, updating the carry. Without vsync quantization a frame is shown when it is done, so the
     * grid only describes the frames while the game holds its cap: above 1.03 grid periods (running mean) the stock value is kept.
     */
    public static float snap(float stockMultiplier, long periodNs, boolean quantized) {
        lastQuantized = quantized;
        lastPeriodNs = periodNs;
        if (periodNs > 0 && stockMultiplier < 5.0f) {
            double d = stockMultiplier / 60.0;
            meanDt = meanDt == 0 ? d : meanDt + (d - meanDt) / 16;
            if (!quantized && meanDt > 1.03 * periodNs / 1e9) {
                carry = 0.0;
                belowCap++;
                stockPrev = stockMultiplier;
                snapPrev = stockMultiplier;
                return stockMultiplier;
            }
        }
        if (periodNs <= 0 || stockMultiplier == 5.0f) {
            carry = 0.0;
            stockPrev = stockMultiplier;
            snapPrev = stockMultiplier;
            if (stockMultiplier == 5.0f) maxCarryMs = 0;
            return stockMultiplier;
        }
        double P = periodNs / 1_000_000_000.0;
        double dt = stockMultiplier / 60.0;
        int n = (int) Math.round((dt + carry) / P);
        if (n < 1) n = 1;
        double newCarry = dt + carry - n * P;
        if (newCarry > P) newCarry = P;
        if (newCarry < -P) newCarry = -P;
        carry = newCarry;
        float result = (float) (60.0 * n * P);
        if (result > 5.0f) result = 5.0f;

        // rings
        if (stockPrev >= 0f && stockPrev != 0f) {
            float rel = Math.abs((stockMultiplier - stockPrev) / stockPrev);
            stockRel[ringPos] = rel;
        } else {
            stockRel[ringPos] = 0f;
        }
        if (snapPrev >= 0f && snapPrev != 0f) {
            float rel = Math.abs((result - snapPrev) / snapPrev);
            snapRel[ringPos] = rel;
        } else {
            snapRel[ringPos] = 0f;
        }
        ringPos = (ringPos + 1) % RING;
        if (ringCount < RING) ringCount++;
        stockPrev = stockMultiplier;
        snapPrev = result;
        float c = (float) (Math.abs(carry) * 1000.0);
        if (c > maxCarryMs) maxCarryMs = c;
        return result;
    }

    private static long nextMinuteNs, snapped;

    /** Once a minute (game thread): frames snapped, p90 frame-to-frame change of the stock vs the snapped multiplier, largest |carry|. */
    static void minute(long nowNs) {
        snapped++;
        if (nextMinuteNs == 0) { nextMinuteNs = nowNs + 60_000_000_000L; return; }
        if (nowNs < nextMinuteNs) return;
        nextMinuteNs = nowNs + 60_000_000_000L;
        int n = ringCount;
        System.out.println(String.format(java.util.Locale.ROOT,
            "[PZButter] frame clock: %d frames snapped, %d left stock below the cap, frame-to-frame change p90 %.1f %% stock vs %.1f %% snapped, max carry %.2f ms (%s grid %.2f ms)",
            snapped - belowCap, belowCap, 100 * p90(stockRel, n), 100 * p90(snapRel, n), maxCarryMs, lastQuantized ? "vsync" : "cap", lastPeriodNs / 1e6));
        snapped = 0;
        belowCap = 0;
        maxCarryMs = 0;
        ringCount = 0;
    }

    static float[] ringCopy(float[] src, int n) {
        float[] out = new float[n];
        int start = (ringPos - n + RING) % RING;
        for (int i = 0; i < n; i++) out[i] = src[(start + i) % RING];
        return out;
    }

    static float p90(float[] v, int n) {
        if (n == 0) return 0f;
        float[] c = ringCopy(v, n);
        java.util.Arrays.sort(c);
        int idx = (int) Math.ceil(0.9 * (n - 1));
        if (idx >= n) idx = n - 1;
        return c[idx];
    }

    static void resetForTest() {
        carry = 0.0;
        ringPos = 0;
        ringCount = 0;
        stockPrev = -1f;
        snapPrev = -1f;
        maxCarryMs = 0;
        nextMinuteNs = 0;
        snapped = 0;
        belowCap = 0;
        meanDt = 0;
        guard.on = false;
    }
}
