package pzbutter;

import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;
import me.zed_0xff.zombie_buddy.Patch;

/**
 * Chunk texture hooks (Plan 6), one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members). Methods that already have a Plan 5a
 * holder keep it in WorldPatches and get the new advice composed in.
 */
public final class ChunkPatches {
    private ChunkPatches() {}

    static final Guard treeBakeGuard = new Guard("treesInChunkTexture");
    static final Guard driveCutGuard = new Guard("driveTreeCutaway");
    static final Guard bloodFadeGuard = new Guard("bloodFadeFix");
    static final Guard mipGuard = new Guard("bakeMipLevels");
    static final Guard edgeGuard = new Guard("edgeTestFast");
    static final Guard translucentGuard = new Guard("translucentTilesInChunkTexture");
    static final Guard zoomGuard = new Guard("zoomEaseMs");
    static final Guard holdGuard = new Guard("bakeScheduler");
    static final Guard treeRateGuard = new Guard("treeBakeMaxChunksPerSec");

    /** FBORenderCell.isTreeRenderedEveryFrame entry: the answer when treesInChunkTexture is on; null runs stock. */
    public static Boolean onTreeEveryFrame(Object obj) {
        if (!treeBakeGuard.on) return null;
        try { return ChunkTrees.everyFrame(obj); }
        catch (Throwable t) { treeBakeGuard.fail(t); return null; }
    }

    /** FBORenderCell.isTranslucentTree entry: the answer when driveTreeCutaway is false; null runs stock. */
    public static Boolean onTranslucentTree(Object obj) {
        if (!driveCutGuard.on) return null;
        try { return ChunkTrees.translucentTree(obj); }
        catch (Throwable t) { driveCutGuard.fail(t); return null; }
    }

    /** FBORenderCell.renderOneLevel_Blood entry (both overloads): clears the chunk's fading splats when bloodFadeFix is on. */
    public static void onBloodBake(IsoChunk chunk) {
        if (!bloodFadeGuard.on || chunk == null) return;
        try { BakeMisc.dropFading(chunk.floorBloodSplatsFade); }
        catch (Throwable t) { bloodFadeGuard.fail(t); }
    }

    /** IsoGridSquare.isBlockedTo(IsoGridSquare, GetSquare) entry: the answer when edgeTestFast is on; null runs stock. */
    public static Boolean onBlockedTo(IsoGridSquare self, IsoGridSquare o, IsoGridSquare.GetSquare gs) {
        if (!edgeGuard.on) return null;
        try { return EdgeTest.blockedTo(self, o, gs) ? Boolean.TRUE : Boolean.FALSE; }
        catch (Throwable t) { edgeGuard.fail(t); return null; }
    }

    /** FBORenderChunk.endRenderThread entry: sets the mip chain's last level before stock builds the mips. */
    public static void onEndRenderThread(Object self) {
        if (!mipGuard.on) return;
        try { BakeMisc.mipBeforeGenerate((FBORenderChunk) self); }
        catch (Throwable t) { mipGuard.fail(t); }
    }

    /** FBORenderCell.calculateObjectRenderLayer exit: the layer to use; the stock answer when the key is off or on failure. */
    public static ObjectRenderLayer onObjectLayer(IsoObject obj, ObjectRenderLayer stock) {
        if (!translucentGuard.on) return stock;
        try { return TranslucentBake.layer(obj, stock); }
        catch (Throwable t) { translucentGuard.fail(t); return stock; }
    }

    /** MultiTextureFBO2.doZoomScroll entry: the zoom and target before the wheel step (null when off or on failure). */
    public static Object onZoomScrollEnter(Object fbo, int p) {
        if (!zoomGuard.on) return null;
        try { return ZoomEase.scrollEnter(fbo, p); }
        catch (Throwable t) { zoomGuard.fail(t); return null; }
    }

    /** MultiTextureFBO2.doZoomScroll exit: starts the ease when the wheel moved the target. */
    public static void onZoomScrollExit(Object fbo, int p, Object before) {
        if (before == null || !zoomGuard.on) return;
        try { ZoomEase.scrollExit(fbo, p, before); }
        catch (Throwable t) { zoomGuard.fail(t); }
    }

    /** MultiTextureFBO2.update exit: an active ease sets the zoom for this frame. */
    public static void onZoomUpdate(Object fbo) {
        if (!zoomGuard.on) return;
        try { ZoomEase.updateExit(fbo); }
        catch (Throwable t) { zoomGuard.fail(t); }
    }

    /** FBORenderCell.prepareChunksForUpdating entry: the bake-hold plan for player p, before the stock prepare step. */
    public static void onPlan(Object cell, int p) {
        if (treeRateGuard.on && p == 0) {
            try { ChunkTrees.release(0, System.nanoTime()); }
            catch (Throwable t) { treeRateGuard.fail(t); }
        }
        if (!holdGuard.on) return;
        try { BakeHold.plan(cell, p); }
        catch (Throwable t) { holdGuard.fail(t); }
    }

    /** FBORenderChunkManager.beginRenderChunkLevel entry: true when the level is held (stock skipped, the old texture shows). */
    public static boolean onGateEnter(Object mgr, IsoChunk chunk, int level, float zoom) {
        if (treeRateGuard.on) {
            try { ChunkTrees.atGate(chunk, level, zoom); }
            catch (Throwable t) { treeRateGuard.fail(t); }
        }
        boolean held = false;
        if (holdGuard.on) {
            try { held = BakeHold.gate(mgr, chunk, level, zoom); }
            catch (Throwable t) { holdGuard.fail(t); }
        }
        if (!held && treeRateGuard.on) {   // the level bakes now: a corner tree's render-square chunk follows it
            try { ChunkTrees.baking(chunk, level, zoom); }
            catch (Throwable t) { treeRateGuard.fail(t); }
        }
        return held;
    }

    /** FBORenderCell.prepareChunkForUpdating entry (holder in WorldPatches): true when the pair waits this frame. */
    public static boolean onPrepareEnter(int p, IsoChunk chunk, int level) {
        if (!holdGuard.on) return false;
        try { return BakeHold.prepareHeld(p, chunk, level); }
        catch (Throwable t) { holdGuard.fail(t); return false; }
    }

    /**
     * Binds the first argument only: both overloads take the chunk first. The 3-argument one calls the 5-argument one on the
     * neighbour chunk (offset 94), so a bake also clears the fading splats of its 8 neighbours, whose textures keep showing
     * them until they re-bake. That fits the key: a pushed-out splat disappears at the next redraw.
     */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "renderOneLevel_Blood")
    public static class BloodBake {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoChunk chunk) { onBloodBake(chunk); }
    }

    /** Binds the 2-argument overload only: the one-argument overload has no argument 1, so the advice does not apply to it. */
    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "isBlockedTo")
    public static class BlockedTo {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This IsoGridSquare self, @Patch.Argument(0) IsoGridSquare o,
                                    @Patch.Argument(1) IsoGridSquare.GetSquare gs, @Patch.Local("v") Object v) {
            v = onBlockedTo(self, o, gs);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;
        }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderChunk", methodName = "endRenderThread")
    public static class BakeMips {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) { onEndRenderThread(self); }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "isTreeRenderedEveryFrame")
    public static class TreeEveryFrame {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject obj, @Patch.Local("v") Object v) {
            v = onTreeEveryFrame(obj);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;
        }
    }

    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "isTranslucentTree")
    public static class TranslucentTree {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject obj, @Patch.Local("v") Object v) {
            v = onTranslucentTree(obj);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;
        }
    }

    /** Private single-overload method; the object is argument 0 and the stock layer is the return value. */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "calculateObjectRenderLayer")
    public static class ObjectLayer {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoObject obj, @Patch.Return(readOnly = false) ObjectRenderLayer ret) {
            ret = onObjectLayer(obj, ret);
        }
    }

    /** Private, one caller, once per player per frame: the planner runs before stock prepares the dirty pairs. */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "prepareChunksForUpdating")
    public static class Plan {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self, @Patch.Argument(0) int p) { onPlan(self, p); }
    }

    /**
     * The bake gate. A held level runs the stock preamble itself (renderChunk, xoff, yoff) and answers false; stock's own
     * OnExit rewrite is not enough here (at the pair's first level the stock body would already have opened the recording).
     */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderChunkManager", methodName = "beginRenderChunkLevel")
    public static class BakeGate {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) IsoChunk chunk, @Patch.Argument(1) int level,
                                    @Patch.Argument(2) float zoom, @Patch.Local("h") Object h) {
            h = onGateEnter(self, chunk, level, zoom) ? Boolean.TRUE : null;
            return h != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("h") Object h) {
            if (h != null) ret = false;
        }
    }

    @Patch(className = "zombie.core.textures.MultiTextureFBO2", methodName = "doZoomScroll")
    public static class ZoomScroll {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self, @Patch.Argument(0) int p, @Patch.Local("z") Object z) {
            z = onZoomScrollEnter(self, p);
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Argument(0) int p, @Patch.Local("z") Object z) {
            onZoomScrollExit(self, p, z);
        }
    }

    @Patch(className = "zombie.core.textures.MultiTextureFBO2", methodName = "update")
    public static class ZoomUpdate {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) { onZoomUpdate(self); }
    }
}
