package pzbutter;

import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

/**
 * Plan 5a buildings-in-front gate (Task 2): gridStackInterval runs the scan less often while the player stays put,
 * and roofHideDebounceFrames holds the orphan-structure (carport roof) flag. The pure cores are tested in WorldTest;
 * the holder in WorldPatches calls them.
 */
final class GridGate {
    private GridGate() {}

    static final int PLAYERS = 4;
    static int interval;
    static int roofFrames;

    // per-player state for the scan gate
    private static final boolean[] seen = new boolean[PLAYERS];
    private static final long[] lastRun = new long[PLAYERS];
    private static final int[] lastX = new int[PLAYERS], lastY = new int[PLAYERS], lastZ = new int[PLAYERS], lastFacing = new int[PLAYERS];

    // one debounce for the cell: the held roof flag, and the frame a differing reading first appeared (if pending)
    private static boolean held, pending;
    private static long firstDiff;

    static void init() {
        interval = Settings.num("gridStackInterval", 0, 0, 600);
        roofFrames = Settings.num("roofHideDebounceFrames", 0, 0, 600);
        WorldPatches.gridGuard.on = Settings.enabled && interval > 0;
        WorldPatches.roofGuard.on = Settings.enabled && roofFrames > 0;
    }

    /** True when the scan must run for this player now; records the run. Runs every frame when interval is 0 or less. */
    static boolean shouldRun(int player, long frame, int camX, int camY, int camZ, int facing, boolean dirty, int interval) {
        if (player < 0 || player >= PLAYERS) return true;
        boolean run = !seen[player] || dirty || frame - lastRun[player] >= interval
                || camX != lastX[player] || camY != lastY[player] || camZ != lastZ[player] || facing != lastFacing[player];
        if (run) {
            seen[player] = true;
            lastRun[player] = frame;
            lastX[player] = camX;
            lastY[player] = camY;
            lastZ[player] = camZ;
            lastFacing[player] = facing;
        }
        return run;
    }

    /**
     * The held roof flag after one real scan. A reading that differs from the held value flips it once it has differed
     * for n frames; time is counted in frames, so several calls in one frame count once.
     */
    static boolean debounce(boolean raw, long frame, int n) {
        if (raw == held) { pending = false; return held; }
        if (!pending) { pending = true; firstDiff = frame; return held; }
        if (frame - firstDiff >= n) { held = raw; pending = false; }
        return held;
    }

    /** Entry of CalculateBuildingsToCollapse: Boolean.FALSE skips the scan; null runs stock. */
    static Boolean gateEnter(long frame) {
        IsoCamera.FrameState fs = IsoCamera.frameState;
        IsoGridSquare sq = fs.camCharacterSquare;
        int p = fs.playerIndex;
        if (sq == null) return null;
        IsoPlayer pl = p >= 0 && p < IsoPlayer.players.length ? IsoPlayer.players[p] : null;
        boolean dirty = pl != null && pl.dirtyRecalcGridStack;
        int facing = pl == null ? 0 : Math.round(pl.getDirectionAngle() / 45f);
        return shouldRun(p, frame, sq.getX(), sq.getY(), sq.getZ(), facing, dirty, interval) ? null : Boolean.FALSE;
    }

    /** Exit of a real scan: the stock reading is in the cell flag; write the held value back. */
    static void scanned(Object cutaways, long frame) {
        IsoCell cell = ((FBORenderCutaways) cutaways).cell;
        if (cell != null) cell.occludedByOrphanStructureFlag = debounce(cell.occludedByOrphanStructureFlag, frame, roofFrames);
    }

    static void resetForTest() {
        seen[0] = seen[1] = seen[2] = seen[3] = false;
        held = pending = false;
        firstDiff = 0;
    }
}
