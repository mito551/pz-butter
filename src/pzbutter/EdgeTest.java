package pzbutter;

import java.util.Arrays;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoWindow;
import zombie.util.list.PZArrayList;

/** Plan 6 edgeTestFast: hand-written isBlockedTo. The choices (sign-pattern table, shared neighbours, lambda leaves) are recorded in the Plan 6 progress ledger. */
final class EdgeTest {
    private EdgeTest() {}

    /** Step code: who << 2 | edge. who is SELF or OTHER; edge is NORTH, WEST, SOUTH or EAST. */
    private static final int SELF = 0, OTHER = 4;
    private static final int NORTH = 0, WEST = 1, SOUTH = 2, EAST = 3;

    /** Ordered steps per sign pattern, at index (signum(self.x - o.x) + 1) * 3 + signum(self.y - o.y) + 1. */
    private static final int[][] STEPS = new int[9][];

    static {
        for (int sx = -1; sx <= 1; sx++) {
            for (int sy = -1; sy <= 1; sy++) {
                int[] s = new int[8];
                int n = 0;
                if (sx > 0) { s[n++] = SELF | WEST; s[n++] = OTHER | EAST; }
                if (sx < 0) { s[n++] = OTHER | WEST; s[n++] = SELF | EAST; }
                if (sy > 0) { s[n++] = SELF | NORTH; s[n++] = OTHER | SOUTH; }
                if (sy < 0) { s[n++] = OTHER | NORTH; s[n++] = SELF | SOUTH; }
                STEPS[(sx + 1) * 3 + sy + 1] = Arrays.copyOf(s, n);
            }
        }
    }

    /** One leaf (wall, window or door) on one facing of one square. */
    private interface Facing { boolean test(IsoGridSquare sq, GridSquareEdgeFacingDirection dir); }

    private static final Facing WALL = (sq, dir) ->
        sq.hasProperty(dir == GridSquareEdgeFacingDirection.NORTH_SOUTH ? IsoFlagType.collideN : IsoFlagType.collideW)
            && !sq.isWindow(dir);

    private static final Facing WINDOW = (sq, dir) -> {
        PZArrayList<IsoObject> objs = sq.getObjects();
        Object[] a = objs.getElements();
        for (int i = 0, n = objs.size(); i < n; i++) {
            if (a[i] instanceof IsoWindow w && w.isBlocked(dir)) return true;
        }
        return false;
    };

    private static final Facing DOOR = (sq, dir) -> {
        PZArrayList<IsoObject> objs = sq.getObjects();
        Object[] a = objs.getElements();
        for (int i = 0, n = objs.size(); i < n; i++) {
            if (IsoGridSquare.isBlockedDoor((IsoObject) a[i], dir)) return true;
        }
        return false;
    };

    /** Stock isBlockedTo(o, gs): wall, window, door, then the stair test; first true wins. */
    static boolean blockedTo(IsoGridSquare self, IsoGridSquare o, IsoGridSquare.GetSquare gs) {
        if (o != null && o != self) {
            int[] steps = STEPS[(Integer.signum(self.x - o.x) + 1) * 3 + Integer.signum(self.y - o.y) + 1];
            for (int code : steps) {
                IsoGridSquare sq = (code & OTHER) != 0 ? o : self;
                int edge = code & 3;
                if (edge >= SOUTH) {
                    sq = edge == SOUTH ? sq.getSouth(gs) : sq.getEast(gs);
                    if (sq == null) continue;
                }
                GridSquareEdgeFacingDirection dir = (edge & 1) == 0
                    ? GridSquareEdgeFacingDirection.NORTH_SOUTH : GridSquareEdgeFacingDirection.EAST_WEST;
                if (WALL.test(sq, dir) || WINDOW.test(sq, dir) || DOOR.test(sq, dir)) return true;
            }
        }
        return self.isStairBlockedTo(o);
    }

    /** Init: the holder on isBlockedTo is on only with edgeTestFast. */
    static void init() {
        ChunkPatches.edgeGuard.on = Settings.flag("edgeTestFast");
    }
}
