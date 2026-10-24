package pzbutter;

import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoWindow;
import zombie.iso.IsoObject;
import zombie.util.list.PZArrayList;

/**
 * Plan 6 edgeTestFast (Task 2): IsoGridSquare.isBlockedTo(IsoGridSquare, GetSquare) written out. The stock answer is
 * wall || window || door || stair; each edge test asks the square and its neighbour through the same edge table the
 * game uses (research "edgeTestFast"). The holder in ChunkPatches calls blockedTo; the tests in ChunkTest compare it
 * with the stock logic for every relative position.
 */
final class EdgeTest {
    private EdgeTest() {}

    static final int NORTH = 0, WEST = 1, SOUTH = 2, EAST = 3;   // stock GridSquareEdge, as an int
    static final int SELF = 0, OTHER = 1;                         // who holds the edge

    /** Step = who << 2 | edge. Index (sx + 1) * 3 + (sy + 1) for the signs of self - other. */
    private static final int[][] ORDERS = new int[9][];

    static {
        for (int sx = -1; sx <= 1; sx++) {
            for (int sy = -1; sy <= 1; sy++) {
                int[] s = new int[(sx != 0 ? 2 : 0) + (sy != 0 ? 2 : 0)];
                int k = 0;
                // stock isEdgeElementTo: four separate tests, not else-if (a diagonal tests one x pair and one y pair)
                if (sx > 0) { s[k++] = step(SELF, WEST); s[k++] = step(OTHER, EAST); }
                if (sx < 0) { s[k++] = step(OTHER, WEST); s[k++] = step(SELF, EAST); }
                if (sy > 0) { s[k++] = step(SELF, NORTH); s[k++] = step(OTHER, SOUTH); }
                if (sy < 0) { s[k++] = step(OTHER, NORTH); s[k++] = step(SELF, SOUTH); }
                ORDERS[(sx + 1) * 3 + sy + 1] = s;
            }
        }
    }

    private static int step(int who, int edge) { return who << 2 | edge; }

    /** The steps stock asks, in order, for self - other having signs sx and sy. Shared arrays; nothing is allocated. */
    static int[] order(int sx, int sy) {
        return ORDERS[(Integer.signum(sx) + 1) * 3 + Integer.signum(sy) + 1];
    }

    /** The wall on an edge of sq: the square's own north or west edge, or the south / east neighbour's facing edge. */
    static boolean wall(IsoGridSquare sq, int edge, IsoGridSquare.GetSquare gs) {
        switch (edge) {
            case NORTH: return wallOn(sq, true);
            case WEST: return wallOn(sq, false);
            case SOUTH: { IsoGridSquare s = sq.getSouth(gs); return s != null && wallOn(s, true); }
            default: { IsoGridSquare e = sq.getEast(gs); return e != null && wallOn(e, false); }
        }
    }

    /** Stock isWall(dir): collideN (or collideW) and not a window on that facing. */
    static boolean wallOn(IsoGridSquare s, boolean northSouth) {
        if (northSouth) return s.hasProperty(IsoFlagType.collideN)
            && !s.isWindow(GridSquareEdgeFacingDirection.NORTH_SOUTH);
        return s.hasProperty(IsoFlagType.collideW)
            && !s.isWindow(GridSquareEdgeFacingDirection.EAST_WEST);
    }

    /** A blocked window on an edge of sq, same square / neighbour rule as {@link #wall}. */
    static boolean window(IsoGridSquare sq, int edge, IsoGridSquare.GetSquare gs) {
        switch (edge) {
            case NORTH: return windowOn(sq, GridSquareEdgeFacingDirection.NORTH_SOUTH);
            case WEST: return windowOn(sq, GridSquareEdgeFacingDirection.EAST_WEST);
            case SOUTH: { IsoGridSquare s = sq.getSouth(gs); return s != null && windowOn(s, GridSquareEdgeFacingDirection.NORTH_SOUTH); }
            default: { IsoGridSquare e = sq.getEast(gs); return e != null && windowOn(e, GridSquareEdgeFacingDirection.EAST_WEST); }
        }
    }

    /** Stock hasBlockedWindow(dir): the first object that is an IsoWindow blocked on that facing. */
    static boolean windowOn(IsoGridSquare s, GridSquareEdgeFacingDirection dir) {
        PZArrayList<IsoObject> objs = s.getObjects();
        for (int i = 0, n = objs.size(); i < n; i++) {
            if (objs.get(i) instanceof IsoWindow w && w.isBlocked(dir)) return true;
        }
        return false;
    }

    /** A blocked door on an edge of sq, same square / neighbour rule as {@link #wall}. */
    static boolean door(IsoGridSquare sq, int edge, IsoGridSquare.GetSquare gs) {
        switch (edge) {
            case NORTH: return doorOn(sq, GridSquareEdgeFacingDirection.NORTH_SOUTH);
            case WEST: return doorOn(sq, GridSquareEdgeFacingDirection.EAST_WEST);
            case SOUTH: { IsoGridSquare s = sq.getSouth(gs); return s != null && doorOn(s, GridSquareEdgeFacingDirection.NORTH_SOUTH); }
            default: { IsoGridSquare e = sq.getEast(gs); return e != null && doorOn(e, GridSquareEdgeFacingDirection.EAST_WEST); }
        }
    }

    /** Stock hasBlockedDoor(dir): the first object for which the static IsoGridSquare.isBlockedDoor holds. */
    static boolean doorOn(IsoGridSquare s, GridSquareEdgeFacingDirection dir) {
        PZArrayList<IsoObject> objs = s.getObjects();
        for (int i = 0, n = objs.size(); i < n; i++) {
            if (IsoGridSquare.isBlockedDoor(objs.get(i), dir)) return true;
        }
        return false;
    }

    /**
     * Stock isBlockedTo(o, gs), with the same order of tests: wall steps, then window steps, then door steps (each
     * stops at its first true step), then the stair test, which stock also runs for a null or the same square.
     */
    static boolean blockedTo(IsoGridSquare self, IsoGridSquare o, IsoGridSquare.GetSquare gs) {
        if (o != null && o != self) {
            int[] steps = order(self.x - o.x, self.y - o.y);
            for (int st : steps) {
                if (wall((st >> 2) == SELF ? self : o, st & 3, gs)) return true;
            }
            for (int st : steps) {
                if (window((st >> 2) == SELF ? self : o, st & 3, gs)) return true;
            }
            for (int st : steps) {
                if (door((st >> 2) == SELF ? self : o, st & 3, gs)) return true;
            }
        }
        return self.isStairBlockedTo(o);
    }

    /** Init: the holder on isBlockedTo is on only with edgeTestFast. */
    static void init() {
        ChunkPatches.edgeGuard.on = Settings.flag("edgeTestFast");
    }
}
