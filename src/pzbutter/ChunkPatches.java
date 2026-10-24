package pzbutter;

import zombie.iso.IsoChunk;
import zombie.iso.IsoObject;
import zombie.iso.fboRenderChunk.FBORenderChunk;
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

    /** FBORenderChunk.endRenderThread entry: sets the mip chain's last level before stock builds the mips. */
    public static void onEndRenderThread(Object self) {
        if (!mipGuard.on) return;
        try { BakeMisc.mipBeforeGenerate((FBORenderChunk) self); }
        catch (Throwable t) { mipGuard.fail(t); }
    }

    /**
     * Binds the first argument only: both overloads take the chunk first, and the 3-argument one only forwards to the
     * 5-argument one, so clearing the same list again is harmless.
     */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell", methodName = "renderOneLevel_Blood")
    public static class BloodBake {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoChunk chunk) { onBloodBake(chunk); }
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
}
