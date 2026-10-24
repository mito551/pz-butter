package pzbutter;

import java.util.WeakHashMap;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.objects.IsoTree;
import java.util.ArrayList;

/**
 * Plan 6 tree layer (Task 1): treesInChunkTexture and driveTreeCutaway. The holders in ChunkPatches call the shims
 * here; the pure cores are tested in ChunkTest.
 */
final class ChunkTrees {
    private ChunkTrees() {}

    // true: stock 42.21 (driving cuts). Set from driveTreeCutaway in init; the cutaway shim only runs when it is false.
    static volatile boolean stockDriving = true;

    static int maxPerSec;

    static void init() {
        ChunkPatches.treeBakeGuard.on = Settings.flag("treesInChunkTexture");
        stockDriving = Settings.flag("driveTreeCutaway");
        ChunkPatches.driveCutGuard.on = Settings.enabled && !stockDriving;
        maxPerSec = Settings.num("treeBakeMaxChunksPerSec", 0, 0, 1000);
        ChunkPatches.treeRateGuard.on = ChunkPatches.treeBakeGuard.on && maxPerSec > 0;
        marks.clear();
        rate = new Rate();
    }

    /** Stock answer when the tree bake is off; with it on, a tree is per frame only in a chunk marked for it. */
    static boolean treePerFrame(boolean isTree, boolean bakeTrees, boolean chunkMarked) {
        return bakeTrees ? isTree && chunkMarked : isTree;
    }

    /** Stock asks the stencil when cutting, or when the tree is on or south-east of the camera square. */
    static boolean testStencil(boolean cut, int sqX, int sqY, int camX, int camY, boolean camSquare) {
        return cut || (sqX >= camX && sqY >= camY && camSquare);
    }

    /** Stock 42.21: aiming or driving cuts. The 42.20 rule (rule4221 false): aiming only. */
    static boolean cutForDriving(boolean aimKey, boolean inVehicle, boolean rule4221) {
        return aimKey || (rule4221 && inVehicle);
    }

    // ---- treeBakeMaxChunksPerSec (Task 8) ----

    /** Chunks whose trees stay per frame for now (new textures are arriving fast). Weak keys; IsoChunk does not override equals / hashCode. Game thread only. */
    private static final WeakHashMap<IsoChunk, Boolean> marks = new WeakHashMap<>();
    private static Rate rate = new Rate();

    /** Arrivals of new chunk textures over the last second, in ten 100 ms buckets. */
    static final class Rate {
        private static final long BUCKET_NS = 100_000_000L;
        private final long[] stamp = new long[10];   // bucket number (time / 100 ms) held in each slot; -1 = empty
        private final int[] count = new int[10];
        /** When the rate last fell to the limit (and stayed there); -1 while above it or before the first look. */
        long quietSinceNs = -1;

        Rate() { java.util.Arrays.fill(stamp, -1); }

        void add(long nowNs) {
            long b = Math.floorDiv(nowNs, BUCKET_NS);
            int s = (int) Math.floorMod(b, 10L);
            if (stamp[s] != b) { stamp[s] = b; count[s] = 0; }
            count[s]++;
        }

        int perSecond(long nowNs) {
            long b = Math.floorDiv(nowNs, BUCKET_NS);
            int n = 0;
            for (int s = 0; s < 10; s++) if (stamp[s] > b - 10 && stamp[s] <= b) n += count[s];
            return n;
        }

        /** Looks at the rate once a frame: quiet begins when it is at most {@code max}, and ends when it is above it. */
        void update(long nowNs, int max) {
            if (perSecond(nowNs) <= max) { if (quietSinceNs < 0) quietSinceNs = nowNs; }
            else quietSinceNs = -1;
        }
    }

    /** New textures arrive too fast: this chunk's trees stay per frame. */
    static boolean markNew(int rate, int max) { return max > 0 && rate > max; }

    /** The rate has stayed at the limit or below for a second: re-bake the marked chunks with their trees. */
    static boolean releaseNow(int rate, int max, long quietSinceNs, long nowNs) {
        return rate <= max && quietSinceNs >= 0 && nowNs - quietSinceNs >= 1_000_000_000L;
    }

    static boolean isMarked(IsoChunk c) { return marks.containsKey(c); }

    /** Trees of a marked chunk stay per frame (the chunk of the tree's square). */
    static boolean marked(Object obj) {
        if (marks.isEmpty() || !(obj instanceof IsoObject o)) return false;
        IsoGridSquare sq = o.square;
        return sq != null && sq.chunk != null && marks.containsKey(sq.chunk);
    }

    /**
     * FBORenderChunkManager.beginRenderChunkLevel entry, before the planner: a dirty pair whose texture was never drawn is an
     * arrival (counted once, at the pair's first level). While the rate is over the limit the chunk of a pair that holds level 0
     * is marked, and its first bake (this frame, right after this call) leaves its trees per frame.
     */
    static void atGate(IsoChunk chunk, int level, float zoom) {
        atGate(chunk, level, zoom, IsoCamera.frameState.playerIndex, System.nanoTime());
    }

    static void atGate(IsoChunk chunk, int level, float zoom, int p, long nowNs) {
        FBORenderLevels levels = chunk.getRenderLevels(p);
        if (level != levels.getMinLevel(level) || !levels.isDirty(level, zoom)) return;
        FBORenderChunk fbo = levels.getFBOForLevel(level, zoom);
        if (fbo != null && fbo.isInit) return;
        rate.add(nowNs);
        if (BakeHold.slot(level) == BakeHold.slot(0) && markNew(rate.perSecond(nowNs), maxPerSec)) marks.put(chunk, Boolean.TRUE);
    }

    /**
     * Once a frame (player 0's plan): after a quiet second every marked chunk still on screen is re-baked for its trees
     * (DIRTY_TREES; with bakeScheduler these are cutaway-class holds and spread), then all marks go. A chunk that left the
     * screen is just unmarked.
     */
    static void release(int p, long nowNs) {
        rate.update(nowNs, maxPerSec);
        if (marks.isEmpty() || !releaseNow(rate.perSecond(nowNs), maxPerSec, rate.quietSinceNs, nowNs)) return;
        ArrayList<IsoChunk> list = new ArrayList<>(marks.keySet());
        marks.clear();
        for (int i = 0; i < list.size(); i++) {
            IsoChunk c = list.get(i);
            if (c.getRenderLevels(p).isOnScreen(0)) invalidateTrees(c, IsoTree::getRenderSquare);
        }
    }

    /**
     * Level 0 of the chunk, and of the chunk each of its corner trees is drawn into, gets DIRTY_TREES for all four players
     * (IsoChunk.invalidateRenderChunkLevel). Stock draws a jumbo tree into the texture of its getRenderSquare(): the square to the
     * south of a tree at local (0, 7), to the east of one at local (7, 0), which lies in the next chunk, and the tree is skipped
     * by the bake of its own chunk. So that chunk has to be baked again too. {@code renderSquare} is IsoTree::getRenderSquare.
     */
    static void invalidateTrees(IsoChunk c, java.util.function.Function<IsoTree, IsoGridSquare> renderSquare) {
        c.invalidateRenderChunkLevel(0, FBORenderChunk.DIRTY_TREES);
        for (int k = 0; k < 2; k++) {
            IsoGridSquare sq = k == 0 ? c.getGridSquare(0, 7, 0) : c.getGridSquare(7, 0, 0);
            if (sq == null) continue;
            var objs = sq.getObjects();
            for (int i = 0; i < objs.size(); i++) {
                if (!(objs.get(i) instanceof IsoTree t)) continue;
                IsoGridSquare rs = renderSquare.apply(t);
                if (rs != null && rs.chunk != null && rs.chunk != c) rs.chunk.invalidateRenderChunkLevel(0, FBORenderChunk.DIRTY_TREES);
            }
        }
    }

    /** isTreeRenderedEveryFrame from the tree-bake switch: a tree is per frame only when its chunk is marked. */
    static Boolean everyFrame(Object obj) {
        return treePerFrame(obj instanceof IsoTree, true, marked(obj));
    }

    /**
     * isTranslucentTree from public members (stock offsets 0-152). Boolean.FALSE for a non-tree; null when the tree has
     * no square (stock runs and fails the same way).
     */
    static Boolean translucentTree(Object obj) {
        if (!(obj instanceof IsoTree)) return Boolean.FALSE;
        IsoCamera.FrameState fs = IsoCamera.frameState;
        IsoPlayer pl = IsoPlayer.getPlayer(fs.playerIndex);
        boolean cut = cutForDriving(pl.isAnyAimKeyDown(), pl.getVehicle() != null, stockDriving);
        IsoGridSquare sq = ((IsoObject) obj).square;
        if (sq == null) return null;
        sq.IsOnScreen();   // stock makes this call and drops the result; it may update a cache
        int camX = PZMath.fastfloor(fs.camCharacterX), camY = PZMath.fastfloor(fs.camCharacterY);
        if (!testStencil(cut, sq.x, sq.y, camX, camY, fs.camCharacterSquare != null)) return Boolean.FALSE;
        float sx = sq.cachedScreenX - fs.offX, sy = sq.cachedScreenY - fs.offY;
        return IsoWorld.instance.currentCell.isInStencil(sx, sy);
    }
}
