package pzbutter;

import zombie.iso.IsoObject;
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
