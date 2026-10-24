package pzbutter;

import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoTree;

/**
 * Plan 6 tree layer (Task 1): treesInChunkTexture and driveTreeCutaway. The holders in ChunkPatches call the shims
 * here; the pure cores are tested in ChunkTest.
 */
final class ChunkTrees {
    private ChunkTrees() {}

    // true: stock 42.21 (driving cuts). Set from driveTreeCutaway in init; the cutaway shim only runs when it is false.
    static volatile boolean stockDriving = true;

    static void init() {
        ChunkPatches.treeBakeGuard.on = Settings.flag("treesInChunkTexture");
        stockDriving = Settings.flag("driveTreeCutaway");
        ChunkPatches.driveCutGuard.on = Settings.enabled && !stockDriving;
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

    /** Chunk marks for tree baking come in Task 8; until then no chunk is marked. */
    static boolean marked(Object obj) {
        return false;
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
