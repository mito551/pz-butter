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

    /**
     * (wx, wy) as one positive key: IsoChunk and its FBORenderLevels are pooled (an instance is reused by the next chunk
     * life at another position), so anything per chunk must key by the position, never by the instance.
     * shortcut: 16 bits per axis, so a map beyond chunk coordinate ±32768 would wrap keys (PZ maps stay inside a few hundred).
     */
    static long chunkKey(int wx, int wy) {
        return (((long) (wx + 0x8000) & 0xFFFFL) << 16) | ((long) (wy + 0x8000) & 0xFFFFL);
    }


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

    /**
     * The 2-argument overload only. strictMatch makes ZombieBuddy match the advice's argument list exactly; without it the
     * advice also lands on isBlockedTo(IsoGridSquare), Byte Buddy rejects that ("does not define an index 1") and the whole
     * IsoGridSquare transform fails, dropping every mod's patches on the class (seen in-game 2026-10-28).
     */
    @Patch(className = "zombie.iso.IsoGridSquare", methodName = "isBlockedTo", strictMatch = true)
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

    // chunkZExtentsFast: getChunk returns null outside 0..chunkGridWidth-1, so the extents loop
    // only needs to cover the cells the chunk lookup can answer.

    public static final Guard extentsGuard = new Guard("chunkZExtentsFast");

    static void initChunkZExtents() {
        try {
            extentsGuard.on = Settings.flag("chunkZExtentsFast");
        } catch (Throwable t) {
            extentsGuard.fail(t);
        }
    }

    public static void calculateZExtents(zombie.iso.IsoChunkMap m) {
        int w = zombie.iso.IsoChunkMap.chunkGridWidth;
        int max = 0;
        int min = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < w; y++) {
                zombie.iso.IsoChunk c = m.getChunk(x, y);
                if (c != null) {
                    max = Math.max(max, c.maxLevel);
                    min = Math.min(min, c.minLevel);
                }
            }
        }
        m.maxHeight = max;
        m.minHeight = min;
    }

    @Patch(className = "zombie.iso.IsoChunkMap", methodName = "calculateZExtentsForChunkMap")
    public static class ZExtents {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) {
            if (!extentsGuard.on) return false;
            try {
                calculateZExtents((zombie.iso.IsoChunkMap) self);
                return true;
            } catch (Throwable t) {
                extentsGuard.fail(t);
                return false;
            }
        }
    }

    // fboChunkMisc: PZArrayList.contains without the per-element callback dispatch (indexForLevel is
    // a closed form, DepthStagger's constructor and calculateOccludingSquares are already flat/compact
    // in 42.21; the free-path one-pass and PerPlayerRender.setSize flat arrays are ruled out in the ledger).

    public static final Guard fboGuard = new Guard("fboChunkMisc");

    static void initFboChunkMisc() {
        try {
            fboGuard.on = Settings.flag("fboChunkMisc");
        } catch (Throwable t) {
            fboGuard.fail(t);
        }
    }

    private static java.lang.reflect.Field pzaElements;

    private static java.lang.reflect.Field pzaNum;

    private static void pzaFields() throws Exception {
        if (pzaElements != null) return;
        pzaElements = zombie.util.list.PZArrayList.class.getDeclaredField("elements");
        pzaElements.setAccessible(true);
        pzaNum = zombie.util.list.PZArrayList.class.getDeclaredField("numElements");
        pzaNum.setAccessible(true);
    }

    /** Null when the guard is off or the shape cannot be trusted (the caller then runs stock contains). */
    public static Boolean containsFast(Object self, Object key) {
        if (!fboGuard.on) return null;
        try {
            pzaFields();
            Object[] a = (Object[]) pzaElements.get(self);
            int n = pzaNum.getInt(self);
            if (a == null || n < 0 || n > a.length) return null;
            for (int i = 0; i < n; i++) {
                Object e = a[i];
                if (e == key || (key != null && key.equals(e))) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable t) {
            fboGuard.fail(t);
            return null;
        }
    }

    @Patch(className = "zombie.util.list.PZArrayList", methodName = "contains", strictMatch = true)
    public static class FastContains {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object key, @Patch.Local("v") Object v) {
            v = containsFast(self, key);
            return v != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("v") Object v) {
            if (v != null) ret = (Boolean) v;
        }
    }

    // ---- indexedHotLoops: iterator-free re-implementations of the named hot loops ----

    public static final Guard hotLoopsGuard = new Guard("indexedHotLoops");

    static void initHotLoops() {
        try {
            hotLoopsGuard.on = Settings.flag("indexedHotLoops");
        } catch (Throwable t) {
            hotLoopsGuard.fail(t);
        }
    }

    private static volatile java.lang.reflect.Field fStencilAreas, fZones;

    private static void hotFields() throws Exception {
        if (fStencilAreas != null) return;
        java.lang.reflect.Field a = zombie.iso.IsoCell.class.getDeclaredField("stencilAreas");
        a.setAccessible(true);
        java.lang.reflect.Field z = zombie.iso.IsoMetaChunk.class.getDeclaredField("zones");
        z.setAccessible(true);
        fStencilAreas = a;
        fZones = z;
    }

    /**
     * indexedHotLoops: IsoCell.isInStencil without the per-call iterator (stencilAreas is a plain ArrayList, so a
     * plain index loop reaches the same records). Answers false when the list is empty, true on the first in-area
     * record — stock's predicate kept verbatim, tileScale factors included.
     */
    public static Boolean isInStencilFast(Object cell, float x, float y) throws Exception {
        if (!hotLoopsGuard.on) return null;
        hotFields();
        java.util.List<?> list = (java.util.List<?>) fStencilAreas.get(cell);
        int tileScale = zombie.core.Core.tileScale;
        for (int i = 0, n = list.size(); i < n; i++) {
            zombie.iso.IsoCell.StencilArea sa = (zombie.iso.IsoCell.StencilArea) list.get(i);
            if (x + 32.0f * tileScale > sa.stencilX1()
                && x - 32.0f * tileScale < sa.stencilX2()
                && y + 32.0f * tileScale > sa.stencilY1()
                && y - 96.0f * tileScale < sa.stencilY2()) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /** indexedHotLoops: IsoMetaChunk.doesHaveZone without the stream (zones is a plain Zone[]). */
    public static Boolean metaHasZone(Object meta, String name) throws Exception {
        if (!hotLoopsGuard.on) return null;
        hotFields();
        zombie.iso.zones.Zone[] zones = (zombie.iso.zones.Zone[]) fZones.get(meta);
        if (zones == null) return Boolean.FALSE;
        for (int i = 0; i < zones.length; i++) {
            zombie.iso.zones.Zone z = zones[i];
            if (z != null && z.type.equalsIgnoreCase(name)) return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /** indexedHotLoops: IsoMetaChunk.doesHaveForaging without the stream. */
    public static Boolean metaHasForaging(Object meta) throws Exception {
        if (!hotLoopsGuard.on) return null;
        hotFields();
        zombie.iso.zones.Zone[] zones = (zombie.iso.zones.Zone[]) fZones.get(meta);
        if (zones == null) return Boolean.FALSE;
        java.util.List<?> foraging = zombie.iso.IsoWorld.instance.getBiomeMap().getForagingZones();
        for (int i = 0; i < zones.length; i++) {
            zombie.iso.zones.Zone z = zones[i];
            if (z != null && foraging.contains(z.type)) return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    @Patch(className = "zombie.iso.IsoCell", methodName = "isInStencil")
    public static class IsInStencil {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) float x, @Patch.Argument(1) float y,
            @Patch.Local("r") Object r) {
            if (!hotLoopsGuard.on) return false;
            try {
                r = isInStencilFast(self, x, y);
                return r != null;
            } catch (Throwable t) {
                hotLoopsGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (Boolean) r;
        }
    }

    @Patch(className = "zombie.iso.IsoMetaChunk", methodName = "doesHaveForaging")
    public static class MetaForaging {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Local("r") Object r) {
            if (!hotLoopsGuard.on) return false;
            try {
                r = metaHasForaging(self);
                return r != null;
            } catch (Throwable t) {
                hotLoopsGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (Boolean) r;
        }
    }

    @Patch(className = "zombie.iso.IsoMetaChunk", methodName = "doesHaveZone")
    public static class MetaZone {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) String name,
            @Patch.Local("r") Object r) {
            if (!hotLoopsGuard.on) return false;
            try {
                r = metaHasZone(self, name);
                return r != null;
            } catch (Throwable t) {
                hotLoopsGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (Boolean) r;
        }
    }
}
