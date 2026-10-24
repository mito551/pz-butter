package pzbutter;

import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.WeakHashMap;
import zombie.core.Core;
import zombie.core.PerformanceSettings;
import zombie.core.math.PZMath;
import zombie.core.textures.ColorInfo;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderLevels;

/**
 * Plan 6 bake-hold planner (bakeScheduler and its numbers). The pure core comes first: dirt classes, the per-frame
 * budget plan, the gate-time decision for one pair, the adaptive budget, the strong-light cap, the global-move test and
 * the light delta (tested in ChunkTest). The per-pair state and the shims the holders in ChunkPatches call follow it.
 *
 * <p>Order in a frame (research "Design available to advice"): plan (before the stock prepare step) -> prepare (skipped for
 * a held pair) -> cutaways -> occlusion (counts granted pairs only) -> lighting -> the bake gate (held pair: the stock
 * preamble is run here and the level answers "not this frame", so stock composites its previous texture).
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

    // a class cap of 0 means no cap: its left* count is then never read or changed
    private static void reset(Budget b) {
        b.leftTotal = b.total;
        b.leftRebake = b.rebake;
        b.leftStrong = b.strong;
        b.leftLight = b.light;
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
            case CUTAWAY: case REDRAW: return b.rebake <= 0 || b.leftRebake > 0;
            case STRONG: return b.strong <= 0 || b.leftStrong > 0;
            case LIGHT: return b.light <= 0 || b.leftLight > 0;
            default: return true;
        }
    }

    private static void count(int cls, Budget b) {
        if (b.leftTotal > 0) b.leftTotal--;
        switch (cls) {
            case CUTAWAY: case REDRAW: if (b.rebake > 0 && b.leftRebake > 0) b.leftRebake--; break;
            case STRONG: if (b.strong > 0 && b.leftStrong > 0) b.leftStrong--; break;
            case LIGHT: if (b.light > 0 && b.leftLight > 0) b.leftLight--; break;
            default: break;
        }
    }

    // scratch of plan: per-class singly linked lists of candidate indices, threaded through vNext
    private static int[] vNext = new int[256];
    private static final int[] vHead = new int[LIGHT + 1];

    /**
     * Decides every dirty on-screen pair of one player for this frame. Fills {@code grant[0..n)}, sets the {@code left*}
     * counts in {@code b} (what the gate may still hand out) and returns the number of counted grants. NEW and OBJECT
     * go and are not counted; overdue pairs go first and are counted even past the caps (the counts stop at 0); then
     * the eligible ones in (class, distance, index) order while the total and their class cap have room. A class above LIGHT
     * (none exists today) is treated as OBJECT: never held, not counted.
     */
    static int plan(int n, int[] cls, int[] dist, int[] held, long[] dirtySinceMs, long[] lastBakeMs, long nowMs, Budget b, boolean[] grant) {
        reset(b);
        if (vNext.length < n) vNext = new int[n + (n >> 1)];
        for (int c = 0; c <= LIGHT; c++) vHead[c] = -1;
        int counted = 0;
        // pass A: class <= OBJECT and overdue are granted and counted; the rest start ungranted
        for (int i = 0; i < n; i++) {
            int c = cls[i];
            if (c <= OBJECT || c > LIGHT) { grant[i] = true; continue; }
            if (overdue(c, held[i], b)) { grant[i] = true; count(c, b); counted++; continue; }
            grant[i] = false;
        }
        // pass B: link the eligible candidates; prepending leaves each list in descending index
        for (int i = 0; i < n; i++) {
            if (grant[i]) continue;
            int c = cls[i];
            if (!eligible(c, dirtySinceMs[i], lastBakeMs[i], nowMs, b)) continue;
            vNext[i] = vHead[c];
            vHead[c] = i;
        }
        // pass C: per class, ascending; repeatedly take the minimum (dist, index) and grant it while the class has room
        for (int c = CUTAWAY; c <= LIGHT; c++) {
            if (b.leftTotal <= 0) break;
            for (;;) {
                int best = -1, bestPrev = -1, bestD = 0, prev = -1;
                for (int j = vHead[c]; j != -1; prev = j, j = vNext[j]) {
                    int d = Math.min(dist[j], 0x7fff);
                    if (best < 0 || d <= bestD) { best = j; bestPrev = prev; bestD = d; }
                }
                if (best < 0 || !roomIn(c, b)) break;
                grant[best] = true;
                count(c, b);
                counted++;
                if (bestPrev < 0) vHead[c] = vNext[best];
                else vNext[bestPrev] = vNext[best];
            }
        }
        return counted;
    }

    /** The gate's grant test without the side effects: the same rules as {@link #grantOne}, nothing counted. */
    static boolean wouldGrant(int cls, int held, long dirtySinceMs, long lastBakeMs, long nowMs, Budget b) {
        if (cls <= OBJECT || cls > LIGHT || overdue(cls, held, b)) return true;
        return eligible(cls, dirtySinceMs, lastBakeMs, nowMs, b) && roomIn(cls, b);
    }

    /** One pair decided after the plan, at the gate: the same rules, using and decrementing the plan's counts. */
    static boolean grantOne(int cls, int held, long dirtySinceMs, long lastBakeMs, long nowMs, Budget b) {
        if (!wouldGrant(cls, held, dirtySinceMs, lastBakeMs, nowMs, b)) return false;
        if (cls > OBJECT && cls <= LIGHT) count(cls, b);
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

    // ---- per-pair state, numbers, shims ----

    /** What the planner remembers about one pair of levels (one texture). Game thread only. */
    static final class PairState {
        long planFrame = -1;
        boolean held;
        int heldFrames;
        long dirtySinceMs = -1;
        long lastBakeMs = Long.MIN_VALUE / 2;
        byte[] light;   // square light at the last grant: 2 levels x 64 squares x r, g, b
        int cls;        // the class of the last decision (for the tests and the ledger)
        boolean noHold; // set by ChunkTrees for the follow-up bake of a corner tree's render-square chunk: classed OBJECT until baked
    }

    static final int SNAP = 2 * 64 * 3;
    static final int PLAYERS = 4;

    // numbers (Settings clamps)
    static int frameBudget = 8, rebakeBudget = 4, rebakeMaxFrames = 3, lightBudget = 8, lightMaxFrames = 30, lightMs = 250;
    static int strongDelta = 6, strongBudget = 8, strongFrameMs = 9, globalPct = 2, bloodMs = 250;
    static boolean adaptiveOn, occlusionGranted;

    private static final Budget[] budgets = new Budget[PLAYERS];
    private static final int[] adaptiveTotal = new int[PLAYERS];
    private static final long[] globalFrame = new long[PLAYERS];   // the frame in which the plan found a global light move
    static { for (int i = 0; i < PLAYERS; i++) budgets[i] = new Budget(); }

    // private game members, read through handles (FBORenderChunkManager.xoff / yoff are package-private floats)
    private static VarHandle xoffH, yoffH;

    static void init() {
        frameBudget = Settings.num("bakeFrameBudget", 8, 1, 256);
        rebakeBudget = Settings.num("rebakeBudget", 4, 0, 256);
        rebakeMaxFrames = Settings.num("rebakeMaxFrames", 3, 1, 600);
        lightBudget = Settings.num("lightingRebakeBudget", 8, 0, 256);
        lightMaxFrames = Settings.num("lightingRebakeMaxFrames", 30, 1, 600);
        lightMs = Settings.num("lightingRebakeMs", 250, 0, 10000);
        strongDelta = Settings.num("lightingStrongDelta", 6, 0, 255);
        strongBudget = Settings.num("lightingStrongBudget", 8, 0, 256);
        strongFrameMs = Settings.num("lightingStrongFrameMs", 9, 0, 1000);
        globalPct = Settings.num("lightingGlobalDeltaPct", 2, 0, 100);
        bloodMs = Settings.num("bloodRebakeCoalesceMs", 250, 0, 10000);
        adaptiveOn = Settings.flag("bakeBudgetAdaptive");
        occlusionGranted = Settings.flag("occlusionGrantedOnly");
        java.util.Arrays.fill(adaptiveTotal, frameBudget);
        java.util.Arrays.fill(globalFrame, -1);
        boolean want = Settings.flag("bakeScheduler");
        if (want && xoffH == null) {
            xoffH = Game.field(FBORenderChunkManager.class, "xoff", float.class);
            yoffH = Game.field(FBORenderChunkManager.class, "yoff", float.class);
        }
        ChunkPatches.holdGuard.on = want && LightChecks.hasChunkHandles() && xoffH != null && yoffH != null;
        if (want && !ChunkPatches.holdGuard.on) System.out.println("[PZButter] bakeScheduler off: game members not found");
    }

    private static final WeakHashMap<FBORenderLevels, PairState[]> states = new WeakHashMap<>();

    /** Index of the pair that holds a level: stock indexForLevel (calculateMinLevel(level) + 32) / 2. */
    static int slot(int level) { return (Math.floorDiv(level, 2) * 2 + 32) / 2; }

    static PairState state(FBORenderLevels levels, int level) {
        PairState[] a = states.get(levels);
        if (a == null) states.put(levels, a = new PairState[32]);
        int i = slot(level);
        PairState st = a[i];
        if (st == null) a[i] = st = new PairState();
        return st;
    }

    /** The pair's state when it has one (never creates). */
    static PairState peek(FBORenderLevels levels, int level) {
        PairState[] a = states.get(levels);
        return a == null ? null : a[slot(level)];
    }

    // ---- seams (pure, tested) ----

    /**
     * True when the pair is held this frame. One decision per pair per frame: the plan's, or the first gate call's for dirt
     * that came after the plan; the pair's second level repeats it (it would draw into the main frame otherwise).
     */
    static boolean gateDecision(PairState st, long frame, int cls, long nowMs, Budget b) {
        if (st.planFrame == frame) return st.held;
        st.planFrame = frame;
        st.cls = cls;
        boolean held = !grantOne(cls, st.heldFrames, st.dirtySinceMs, st.lastBakeMs, nowMs, b);
        st.held = held;
        if (held) st.heldFrames++;
        return held;
    }

    /** A held pair is not prepared (its square flags and light cache stay as they were). */
    static boolean skipPrepare(PairState st, long frame) { return st.planFrame == frame && st.held; }

    /** The occlusion check does not count a pair that waits this frame. */
    static boolean countsForOcclusion(PairState st, long frame) { return !skipPrepare(st, frame); }

    /** A pair is baked this frame: its wait is over. */
    static void granted(PairState st, long nowMs) {
        st.heldFrames = 0;
        st.dirtySinceMs = -1;
        st.lastBakeMs = nowMs;
        st.noHold = false;
    }

    /** FBORenderChunkManager.beginRenderChunkLevel offsets 50-116, float operations in stock order. */
    static float yoff(int min, int top, int ppl, int extra) {
        float y = (float) ((top - min + 1) * ppl);
        y += (float) (min * ppl);
        y += (float) extra;
        return y;
    }

    // ---- classification ----

    /** The pair's dirt class from its flags and texture, read with the public dirt queries (5 calls at most). */
    private static int classifyPair(FBORenderLevels levels, int lvl, float zoom) {
        long flags;
        if (levels.isDirty(lvl, OBJECT_MASK | CREATE, zoom)) flags = OBJECT_MASK;   // either means never held
        else {
            flags = 0;
            if (levels.isDirty(lvl, CUTAWAY_MASK, zoom)) flags |= CUTAWAY_MASK;
            if (levels.isDirty(lvl, REDRAW_FLAG, zoom)) flags |= REDRAW_FLAG;
            if (levels.isDirty(lvl, BLOOD_FLAG, zoom)) flags |= BLOOD_FLAG;
            if (levels.isDirty(lvl, LIGHT_FLAG, zoom)) flags |= LIGHT_FLAG;
        }
        FBORenderChunk fbo = levels.getFBOForLevel(lvl, zoom);
        return classify(flags, fbo != null && fbo.isInit);
    }

    /**
     * Square light of the pair's two levels as bytes (r, g, b x 255, quantised as stock compares them): the pair's lower level
     * first, then the upper one. A level the chunk does not have (a basement chunk's pair, a one-level chunk) or a missing
     * square reads 0, so the layout is the same for every snapshot of a pair.
     */
    static void snapshot(IsoChunk c, FBORenderLevels levels, int level, int p, byte[] out) {
        int lo = levels.getMinLevel(level), hi = levels.getMaxLevel(level), base = Math.floorDiv(level, 2) * 2;
        int o = 0;
        for (int z = base; z <= base + 1; z++) {
            boolean have = z >= lo && z <= hi;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    IsoGridSquare sq = have ? c.getGridSquare(x, y, z) : null;
                    ColorInfo ci = sq == null ? null : sq.getLightInfo(p);
                    if (ci == null) { out[o++] = 0; out[o++] = 0; out[o++] = 0; }
                    else { out[o++] = (byte) q(ci.r); out[o++] = (byte) q(ci.g); out[o++] = (byte) q(ci.b); }
                }
            }
        }
    }

    private static byte[] cur = new byte[SNAP];   // scratch snapshot; the gate hands it to a pair on a grant and takes the old one back

    /** Mean absolute channel move between two snapshots (0-255). */
    static double meanDelta(byte[] a, byte[] b, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 255) - (b[i] & 255);
            sum += d < 0 ? -d : d;
        }
        return n == 0 ? 0 : sum / (double) n;
    }

    static long nowMs() { return System.nanoTime() / 1_000_000L; }

    // ---- plan ----

    private static PairState[] pSt = new PairState[256];
    private static int[] pCls = new int[256], pDist = new int[256], pHeld = new int[256];
    private static long[] pSince = new long[256], pLast = new long[256];
    private static boolean[] pGrant = new boolean[256];
    private static int[] pLevel = new int[256];

    private static void growPlan(int need) {
        if (need <= pSt.length) return;
        int n = Math.max(need, pSt.length * 2);
        pSt = java.util.Arrays.copyOf(pSt, n);
        pCls = java.util.Arrays.copyOf(pCls, n);
        pDist = java.util.Arrays.copyOf(pDist, n);
        pHeld = java.util.Arrays.copyOf(pHeld, n);
        pSince = java.util.Arrays.copyOf(pSince, n);
        pLast = java.util.Arrays.copyOf(pLast, n);
        pGrant = new boolean[n];
        pLevel = java.util.Arrays.copyOf(pLevel, n);
    }

    private static double capMs() {
        int fps = PerformanceSettings.getLockFPS();
        return fps <= 0 || fps > 1000 ? 0 : 1000.0 / fps;
    }

    /** FBORenderCell.prepareChunksForUpdating entry: decides every dirty on-screen pair of player p for this frame. */
    static void plan(Object cell, int p) {
        if (!FrameClock.on || p < 0 || p >= PLAYERS) return;
        ArrayList<?> chunks = LightChecks.onScreen(cell, p);
        if (chunks == null) return;
        planChunks(chunks, p, Core.getInstance().getZoom(p), FrameClock.frameNo, nowMs());
    }

    static void planChunks(ArrayList<?> chunks, int p, float zoom, long frame, long now) {
        IsoCamera.FrameState fs = IsoCamera.frameState;
        int camCx = Math.floorDiv(PZMath.fastfloor(fs.camCharacterX), 8), camCy = Math.floorDiv(PZMath.fastfloor(fs.camCharacterY), 8);
        int n = 0, onScreen = 0, lightPairs = 0, deltaPairs = 0;
        double deltaSum = 0;
        for (int i = 0; i < chunks.size(); i++) {
            IsoChunk c = (IsoChunk) chunks.get(i);
            FBORenderLevels levels = c.getRenderLevels(p);
            for (int lvl = c.minLevel; lvl <= c.maxLevel; lvl++) {
                if (lvl != levels.getMinLevel(lvl) || !levels.isOnScreen(lvl)) continue;
                onScreen++;
                if (!levels.isDirty(lvl, zoom)) {
                    PairState idle = peek(levels, lvl);
                    if (idle != null) { idle.heldFrames = 0; idle.dirtySinceMs = -1; }
                    continue;
                }
                PairState st = state(levels, lvl);
                if (st.dirtySinceMs < 0) st.dirtySinceMs = now;
                int cls = classifyPair(levels, lvl, zoom);
                if (st.noHold && cls > OBJECT) cls = OBJECT;
                if (cls == LIGHT) {
                    lightPairs++;
                    if (strongDelta == 0) cls = STRONG;
                    else if (st.light != null) {
                        snapshot(c, levels, lvl, p, cur);
                        if (maxDelta(st.light, cur, SNAP) >= strongDelta) cls = STRONG;
                        if (globalPct > 0) { deltaSum += meanDelta(st.light, cur, SNAP); deltaPairs++; }
                    }
                }
                growPlan(n + 1);
                pSt[n] = st;
                pCls[n] = cls;
                pDist[n] = Math.max(Math.abs(c.wx - camCx), Math.abs(c.wy - camCy));
                pHeld[n] = st.heldFrames;
                pSince[n] = st.dirtySinceMs;
                pLast[n] = st.lastBakeMs;
                n++;
            }
        }
        if (globalMove(lightPairs, onScreen, deltaPairs == 0 ? 0 : deltaSum / deltaPairs, globalPct)) {
            globalFrame[p] = frame;
            for (int i = 0; i < n; i++) if (pCls[i] == STRONG) pCls[i] = LIGHT;
        }
        Budget b = budgets[p];
        b.total = adaptiveOn ? (adaptiveTotal[p] = adaptive(adaptiveTotal[p], frameBudget, GpuTimer.loadMs(FrameClock.stepMs), capMs())) : frameBudget;
        b.rebake = rebakeBudget;
        b.strong = strongCap(strongBudget, FrameClock.stepMs, strongFrameMs);
        b.light = lightBudget;
        b.rebakeMaxFrames = rebakeMaxFrames;
        b.lightMaxFrames = lightMaxFrames;
        b.lightMs = lightMs;
        b.bloodMs = bloodMs;
        BakeHold.plan(n, pCls, pDist, pHeld, pSince, pLast, now, b, pGrant);
        for (int i = 0; i < n; i++) {
            PairState st = pSt[i];
            st.planFrame = frame;
            st.cls = pCls[i];
            st.held = !pGrant[i];
            if (st.held) st.heldFrames++;
            pSt[i] = null;
        }
    }

    // ---- gate ----

    /** FBORenderChunkManager.beginRenderChunkLevel entry: true = this level is held (stock skipped, it answers "not baked"). */
    static boolean gate(Object mgr, IsoChunk chunk, int level, float zoom) {
        if (!FrameClock.on) return false;
        return gate(mgr, chunk, level, zoom, IsoCamera.frameState.playerIndex, FrameClock.frameNo, nowMs());
    }

    static boolean gate(Object mgr, IsoChunk chunk, int level, float zoom, int p, long frame, long now) {
        if (p < 0 || p >= PLAYERS) return false;
        FBORenderLevels levels = chunk.getRenderLevels(p);
        if (!levels.isDirty(level, zoom)) return false;   // stock returns false itself
        PairState st = state(levels, level);
        Budget b = budgets[p];
        boolean fresh = false;   // `cur` holds the light of this pair as of now
        int cls;
        if (st.planFrame == frame) cls = st.cls;
        else {
            cls = gateClass(levels, level, zoom, st, now);
            // Light dirt that reached a clean pair after the plan (the stock lighting refresh runs later). A pair that goes anyway
            // needs no STRONG decision; one that would wait is STRONG when a square moved a lot, unless the plan saw a global move.
            if (cls == LIGHT) {
                if (strongDelta == 0) cls = STRONG;
                else if (st.light != null && globalFrame[p] != frame && roomIn(STRONG, b) && !wouldGrant(cls, st.heldFrames, st.dirtySinceMs, st.lastBakeMs, now, b)) {
                    snapshot(chunk, levels, level, p, cur);
                    fresh = true;
                    if (maxDelta(st.light, cur, SNAP) >= strongDelta) cls = STRONG;
                }
            }
        }
        boolean held = gateDecision(st, frame, cls, now, b);
        if (held) {
            FBORenderChunk before = levels.getFBOForLevel(level, zoom);
            FBORenderChunk rc = levels.getOrCreateFBOForLevel(level, zoom);
            if (rc == before && rc != null) {   // the stock preamble, offsets 0-116
                ((FBORenderChunkManager) mgr).renderChunk = rc;
                xoffH.set(mgr, rc.w / 2f);
                yoffH.set(mgr, yoff(rc.getMinLevel(), rc.getTopLevel(), FBORenderChunk.PIXELS_PER_LEVEL,
                                    FBORenderLevels.extraHeightForJumboTrees(rc.getMinLevel(), rc.getTopLevel())));
                return true;
            }
            st.held = false;   // the texture was missing or had to be re-made: nothing to show, bake it now
        }
        if (level == levels.getMinLevel(level)) {
            granted(st, now);
            if (strongDelta > 0) {
                if (fresh && st.light != null) { byte[] old = st.light; st.light = cur; cur = old; }   // reuse the decision's snapshot
                else {
                    if (st.light == null) st.light = new byte[SNAP];
                    snapshot(chunk, levels, level, p, st.light);
                }
            }
        }
        return false;
    }

    /** A pair that became dirty after the plan: classified from its flags (the light delta is the gate's job, only for a pair that would wait). */
    private static int gateClass(FBORenderLevels levels, int level, float zoom, PairState st, long now) {
        if (st.dirtySinceMs < 0) st.dirtySinceMs = now;
        int cls = classifyPair(levels, level, zoom);
        return st.noHold && cls > OBJECT ? OBJECT : cls;
    }

    // ---- prepare, occlusion ----

    /** FBORenderCell.prepareChunkForUpdating entry: true = the pair waits this frame, so its prepare is skipped. */
    static boolean prepareHeld(int p, IsoChunk chunk, int level) {
        return FrameClock.on && prepareHeld(p, chunk, level, FrameClock.frameNo);
    }

    static boolean prepareHeld(int p, IsoChunk chunk, int level, long frame) {
        if (chunk == null || p < 0 || p >= PLAYERS) return false;
        PairState st = peek(chunk.getRenderLevels(p), level);
        return st != null && skipPrepare(st, frame);
    }

    /** Whether the occlusion check counts this dirty level: not when its pair waits this frame. */
    static boolean countsLevel(FBORenderLevels levels, int level, long frame) {
        PairState st = peek(levels, level);
        return st == null || countsForOcclusion(st, frame);
    }
}
