package pzbutter;

/**
 * Plan 6 bake-hold planner (bakeScheduler and its numbers). This part is the pure core: dirt classes, the per-frame
 * budget plan, the gate-time decision for one pair, the adaptive budget, the strong-light cap, the global-move test and
 * the light delta. Tested in ChunkTest; the hooks and the per-pair state are further down.
 */
final class BakeHold {
    private BakeHold() {}

    // classes, most urgent first; NEW and OBJECT are never held
    static final int NEW = 0, OBJECT = 1, CUTAWAY = 2, REDRAW = 3, STRONG = 4, BLOOD = 5, LIGHT = 6;

    // FBORenderChunk.DIRTY_* values (public static final long in the game; copied so the core needs no game class)
    static final long OBJECT_MASK = 2 | 4 | 8 | 16 | 64 | 128 | 256;
    static final long CREATE = 512;
    static final long CUTAWAY_MASK = 2048 | 4096 | 8192 | 16384;
    static final long REDRAW_FLAG = 1024;
    static final long BLOOD_FLAG = 1;
    static final long LIGHT_FLAG = 32;

    /** One player's caps for a frame. A cap of 0 means no cap for that class (only {@code total} applies). */
    static final class Budget {
        int total, rebake, strong, light;
        int rebakeMaxFrames, lightMaxFrames, lightMs, bloodMs;
        int leftTotal, leftRebake, leftStrong, leftLight;
    }

    /**
     * The class of a dirty pair from its dirty flags. Nothing is held that has no texture yet, was just created, changed
     * an object, an item or a corpse, or is dirty with no flag at all. STRONG is not set here: the planner promotes a
     * LIGHT pair from its light delta.
     */
    static int classify(long flags, boolean hasTexture) {
        if (!hasTexture || (flags & CREATE) != 0) return NEW;
        if ((flags & OBJECT_MASK) != 0 || flags == 0) return OBJECT;
        if ((flags & CUTAWAY_MASK) != 0) return CUTAWAY;
        if ((flags & REDRAW_FLAG) != 0) return REDRAW;
        if ((flags & BLOOD_FLAG) != 0) return BLOOD;
        if ((flags & LIGHT_FLAG) != 0) return LIGHT;
        return OBJECT;   // dirt with only flags this plan does not know: stock timing
    }

    private static int left(int cap) { return cap > 0 ? cap : Integer.MAX_VALUE; }

    private static void reset(Budget b) {
        b.leftTotal = b.total;
        b.leftRebake = left(b.rebake);
        b.leftStrong = left(b.strong);
        b.leftLight = left(b.light);
    }

    private static boolean overdue(int cls, int held, Budget b) {
        return cls <= REDRAW ? held >= b.rebakeMaxFrames : held >= b.lightMaxFrames;
    }

    /** True when the pair is allowed to go now by its own rules, whatever the caps say. */
    private static boolean eligible(int cls, long dirtySinceMs, long lastBakeMs, long nowMs, Budget b) {
        if (cls == BLOOD) return b.bloodMs <= 0 || nowMs - dirtySinceMs >= b.bloodMs;
        if (cls == LIGHT) return b.lightMs <= 0 || nowMs - lastBakeMs >= b.lightMs;
        return true;
    }

    private static boolean roomIn(int cls, Budget b) {
        if (b.leftTotal <= 0) return false;
        switch (cls) {
            case CUTAWAY: case REDRAW: return b.leftRebake > 0;
            case STRONG: return b.leftStrong > 0;
            case LIGHT: return b.leftLight > 0;
            default: return true;
        }
    }

    private static void count(int cls, Budget b) {
        if (b.leftTotal > 0) b.leftTotal--;
        switch (cls) {
            case CUTAWAY: case REDRAW: if (b.leftRebake > 0) b.leftRebake--; break;
            case STRONG: if (b.leftStrong > 0) b.leftStrong--; break;
            case LIGHT: if (b.leftLight > 0) b.leftLight--; break;
            default: break;
        }
    }

    // sort scratch: cls << 48 | dist << 32 | index, game thread only, grown when a plan is larger
    private static long[] keys = new long[512];

    /**
     * Decides every dirty on-screen pair of one player for this frame. Fills {@code grant[0..n)}, sets the {@code left*}
     * counts in {@code b} (what the gate may still hand out) and returns the number of counted grants. NEW and OBJECT
     * go and are not counted; overdue pairs go first and are counted even past the caps (the counts stop at 0); then
     * the eligible ones in (class, distance, index) order while the total and their class cap have room.
     */
    static int plan(int n, int[] cls, int[] dist, int[] held, long[] dirtySinceMs, long[] lastBakeMs, long nowMs, Budget b, boolean[] grant) {
        reset(b);
        int counted = 0, m = 0;
        long[] k = keys;
        if (k.length < n) keys = k = new long[Math.max(n, k.length * 2)];
        for (int i = 0; i < n; i++) {
            int c = cls[i];
            grant[i] = false;
            if (c <= OBJECT) { grant[i] = true; continue; }
            if (overdue(c, held[i], b)) { grant[i] = true; count(c, b); counted++; continue; }
            if (!eligible(c, dirtySinceMs[i], lastBakeMs[i], nowMs, b)) continue;
            long key = (long) c << 48 | (long) Math.min(dist[i], 0x7fff) << 32 | i;
            int j = m++;
            while (j > 0 && k[j - 1] > key) { k[j] = k[j - 1]; j--; }
            k[j] = key;
        }
        for (int r = 0; r < m; r++) {
            long key = k[r];
            int c = (int) (key >>> 48);
            if (!roomIn(c, b)) continue;   // a later pair of another class may still fit
            grant[(int) key] = true;
            count(c, b);
            counted++;
        }
        return counted;
    }

    /** One pair decided after the plan, at the gate: the same rules, using and decrementing the plan's counts. */
    static boolean grantOne(int cls, int held, long dirtySinceMs, long lastBakeMs, long nowMs, Budget b) {
        if (cls <= OBJECT) return true;
        if (overdue(cls, held, b)) { count(cls, b); return true; }
        if (!eligible(cls, dirtySinceMs, lastBakeMs, nowMs, b) || !roomIn(cls, b)) return false;
        count(cls, b);
        return true;
    }

    /**
     * The budget for the next frame: halves after a frame over 90 % of the cap's time, grows by one under 60 %. An
     * unknown cap (capMs <= 0) uses the maximum. Never below min(2, max).
     */
    static int adaptive(int cur, int max, double stepMs, double capMs) {
        if (capMs <= 0) return max;
        int r = cur;
        if (stepMs > 0.9 * capMs) r = Math.min(max, Math.max(2, cur / 2));
        else if (stepMs < 0.6 * capMs) r = Math.min(max, cur + 1);
        return Math.max(Math.min(2, max), r);
    }

    /** A long frame step halves the strong-light cap for the next plan (0 stays "no cap"). */
    static int strongCap(int cap, double stepMs, int frameMs) {
        return cap > 0 && frameMs > 0 && stepMs > frameMs ? Math.max(1, cap / 2) : cap;
    }

    /** A light move across most of the screen: at least half the on-screen pairs are lighting-dirty and the mean delta is pct % of 255. */
    static boolean globalMove(int lightPairs, int onScreenPairs, double meanDelta, int pct) {
        return pct > 0 && onScreenPairs > 0 && lightPairs * 2 >= onScreenPairs && meanDelta >= pct * 255.0 / 100;
    }

    /** The largest channel move between two light snapshots (bytes read as 0-255). */
    static int maxDelta(byte[] a, byte[] b, int n) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 255) - (b[i] & 255);
            if (d < 0) d = -d;
            if (d > m) m = d;
        }
        return m;
    }

    /** A light channel as stock compares it: {@code (int) (c * 255)}, clamped to a byte's range. */
    static int q(float c) {
        int v = (int) (c * 255f);
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
