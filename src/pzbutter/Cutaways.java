package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import zombie.iso.BuildingDef;
import zombie.iso.IsoCamera;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.fboRenderChunk.FBORenderCutaways;

/**
 * Plan 5a cutaway visit (Task 3): a re-implementation of FBORenderCutaways.doCutawayVisitSquares(int, ArrayList) that
 * follows stock's steps in order, with cutawayInvalidateChanged (redraw only chunks whose flags changed),
 * cutawayVisitPrefilter (drop walls that cannot cut) and cutawayRadius (walls only from chunks near the camera).
 * WorldPatches hooks it; with all three keys off, stock runs.
 */
final class Cutaways {
    private Cutaways() {}

    /** DIRTY_CUTAWAYS in stock: the dirty bit a changed chunk level takes. */
    static final long DIRTY_CUTAWAYS = 2048L;
    /** Squares per chunk side (stock's chunk map works in 8s). */
    static final int CHUNK = 8;
    /** Stock's north and west flag bits. */
    static final int NORTH = 1, WEST = 2;

    static boolean changedOnly, prefilter;
    static int radius;
    private static boolean ready;

    // private game members, reached through handles (Game.java); null when one is missing
    private static Class<?> ppdClass, bcClass, cwClass, pirClass, poiClass;
    private static VarHandle perPlayer, pointsOfInterest, lastResults, resultsNorth, resultsWest, visitedNorth, visitedWest,
                             cutawayWalls, buildings, buildingList, poiX, poiY, poiZ;
    private static MethodHandle visitSquares, invalidateChunk, hasAnyWalls, isInRange, setVisited, shouldCut, partOf;
    private static Object outOfRange;

    static void init() {
        changedOnly = Settings.flag("cutawayInvalidateChanged");
        prefilter = Settings.flag("cutawayVisitPrefilter");
        radius = Settings.num("cutawayRadius", 0, 0, 64);
        WorldPatches.cutawayGuard.on = Settings.enabled && (changedOnly || prefilter || radius > 0) && resolveHandles();
    }

    /** Resolves every game member the visit reaches. False when one is missing, so stock runs. */
    static boolean resolveHandles() {
        try {
            ClassLoader cl = FBORenderCutaways.class.getClassLoader();
            String base = "zombie.iso.fboRenderChunk.FBORenderCutaways$";
            ppdClass = Class.forName(base + "PerPlayerData", false, cl);
            bcClass = Class.forName(base + "BuildingsToCollapse", false, cl);
            cwClass = Class.forName(base + "CutawayWall", false, cl);
            pirClass = Class.forName(base + "PlayerInRange", false, cl);
            poiClass = Class.forName(base + "PointOfInterest", false, cl);
            Class<?> cut = FBORenderCutaways.class;
            perPlayer = Game.field(cut, "perPlayerData", Array.newInstance(ppdClass, 0).getClass());
            pointsOfInterest = Game.field(cut, "pointOfInterest", ArrayList.class);
            lastResults = Game.field(ppdClass, "lastCutawayVisitorResults", ArrayList.class);
            resultsNorth = Game.field(ppdClass, "cutawayVisitorResultsNorth", HashSet.class);
            resultsWest = Game.field(ppdClass, "cutawayVisitorResultsWest", HashSet.class);
            visitedNorth = Game.field(ppdClass, "cutawayVisitorVisitedNorth", HashSet.class);
            visitedWest = Game.field(ppdClass, "cutawayVisitorVisitedWest", HashSet.class);
            cutawayWalls = Game.field(ppdClass, "cutawayWalls", ArrayList.class);
            buildings = Game.field(ppdClass, "buildingsToCollapse", bcClass);
            buildingList = Game.field(bcClass, "buildingsToCollapse", ArrayList.class);
            poiX = Game.field(poiClass, "x", int.class);
            poiY = Game.field(poiClass, "y", int.class);
            poiZ = Game.field(poiClass, "z", int.class);
            visitSquares = Game.method(cut, "doCutawayVisitSquares",
                MethodType.methodType(void.class, IsoGridSquare.class, long.class, ArrayList.class));
            invalidateChunk = Game.method(cut, "invalidateChunk",
                MethodType.methodType(void.class, int.class, IsoChunkMap.class, int.class, int.class, int.class));
            hasAnyWalls = Game.method(cut, "hasAnyCutawayWalls",
                MethodType.methodType(boolean.class, int.class, IsoChunk.class, int.class, byte.class, int.class, int.class, int.class, int.class));
            isInRange = Game.method(cwClass, "isPlayerInRange", MethodType.methodType(boolean.class, int.class, pirClass));
            setVisited = Game.method(cwClass, "setVisitedSquares", MethodType.methodType(void.class, ppdClass));
            shouldCut = Game.method(cwClass, "shouldCutawayBuilding", MethodType.methodType(boolean.class, int.class));
            partOf = Game.method(cwClass, "isPartOfBuilding", MethodType.methodType(boolean.class, BuildingDef.class));
            outOfRange = Game.staticField(pirClass, "False", pirClass).get();
        } catch (Throwable t) {
            System.out.println("[PZButter] cutaway members not found: " + t);
            return false;
        }
        ready = perPlayer != null && pointsOfInterest != null && lastResults != null && resultsNorth != null
            && resultsWest != null && visitedNorth != null && visitedWest != null && cutawayWalls != null
            && buildings != null && buildingList != null && poiX != null && poiY != null && poiZ != null
            && visitSquares != null && invalidateChunk != null && hasAnyWalls != null && isInRange != null
            && setVisited != null && shouldCut != null && partOf != null && outOfRange != null;
        return ready;
    }

    /** Chunks where some square's flag differs between the start and the end of a visit. Entries with chunk -1 are skipped. */
    static boolean[] changedChunks(int[] chunkOfSquare, int[] oldFlag, int[] newFlag, int chunkCount) {
        boolean[] changed = new boolean[chunkCount];
        for (int i = 0; i < chunkOfSquare.length; i++)
            if (chunkOfSquare[i] >= 0 && oldFlag[i] != newFlag[i]) changed[chunkOfSquare[i]] = true;
        return changed;
    }

    /**
     * Whether a wall may cut for this player. The check is a superset of stock's cut rules, so a wall it drops
     * could never cut: a wall in a collapsing building, a wall whose occluded rooms are seen, or any wall while peeking.
     */
    static boolean mayCut(boolean idsEmpty, boolean collapseHit, boolean shouldCutawayBuilding, boolean peeking) {
        return peeking || collapseHit || (!idsEmpty && shouldCutawayBuilding);
    }

    /** Chebyshev distance in chunks; r <= 0 means no limit. */
    static boolean inRadius(int camChunkX, int camChunkY, int wx, int wy, int r) {
        return r <= 0 || (Math.abs(wx - camChunkX) <= r && Math.abs(wy - camChunkY) <= r);
    }

    /**
     * The stock method for player p over the chunk list, in stock's order: save and clear the results; collect the
     * walls; visit the points of interest; old results go to flag 0; exterior walls; new north and west flags; then
     * the four edge booleans of every changed chunk. Returns false when the members are not ready (stock runs).
     */
    @SuppressWarnings("unchecked")
    static boolean visit(Object cut, int p, ArrayList<?> chunks) throws Throwable {
        if (!ready) return false;
        FBORenderCutaways c = (FBORenderCutaways) cut;
        Object pd = ((Object[]) perPlayer.get(cut))[p];
        ArrayList<Object> last = (ArrayList<Object>) lastResults.get(pd);
        HashSet<Object> north = (HashSet<Object>) resultsNorth.get(pd), west = (HashSet<Object>) resultsWest.get(pd);
        HashSet<Object> visN = (HashSet<Object>) visitedNorth.get(pd), visW = (HashSet<Object>) visitedWest.get(pd);
        ArrayList<Object> walls = (ArrayList<Object>) cutawayWalls.get(pd);
        IsoChunkMap map = c.cell.chunkMap[p];
        long now = System.currentTimeMillis();

        // stock 0-76: last results = north + west; the result and visited sets are cleared
        last.clear();
        last.addAll(north);
        last.addAll(west);
        north.clear();
        west.clear();
        visN.clear();
        visW.clear();
        int camZ = (int) Math.floor(IsoCamera.frameState.camCharacterZ);

        // stock 77-179: walls of every on-screen chunk level at camZ; radius limits which chunks count
        walls.clear();
        int camChunkX = Math.floorDiv((int) Math.floor(IsoCamera.frameState.camCharacterX), CHUNK);
        int camChunkY = Math.floorDiv((int) Math.floor(IsoCamera.frameState.camCharacterY), CHUNK);
        for (Object o : chunks) {
            IsoChunk chunk = (IsoChunk) o;
            if (camZ < chunk.minLevel || camZ > chunk.maxLevel) continue;
            if (!chunk.getRenderLevels(p).isOnScreen(camZ)) continue;
            if (!inRadius(camChunkX, camChunkY, chunk.wx, chunk.wy, radius)) continue;
            walls.addAll(chunk.getCutawayDataForLevel(camZ).allWalls);
        }

        // cutawayVisitPrefilter: drop walls that cannot cut before any square is checked
        if (prefilter) {
            boolean peeking = c.cell.playerWindowPeekingRoomId[p] != -1;
            boolean idsEmpty = c.cell.tempPlayerCutawayRoomIds.get(p).isEmpty();
            ArrayList<Object> collapsing = (ArrayList<Object>) buildingList.get(buildings.get(pd));
            for (int i = walls.size() - 1; i >= 0; i--) {
                Object w = walls.get(i);
                boolean collapseHit = false;
                for (int b = 0; !collapseHit && b < collapsing.size(); b++)
                    collapseHit = (boolean) partOf.invoke(w, collapsing.get(b));
                boolean cutBuilding = !idsEmpty && (boolean) shouldCut.invoke(w, p);
                if (!mayCut(idsEmpty, collapseHit, cutBuilding, peeking)) walls.remove(i);
            }
        }

        // stock 198-309: each point of interest on camZ is visited unless its square is already in a visited set
        for (Object o : (ArrayList<Object>) pointsOfInterest.get(cut)) {
            if ((int) poiZ.get(o) != camZ) continue;
            IsoGridSquare sq = map.getGridSquare((int) poiX.get(o), (int) poiY.get(o), camZ);
            if (sq == null || visN.contains(sq) || visW.contains(sq)) continue;
            visitSquares.invoke(cut, sq, now, chunks);
        }

        // Every square whose flag may change is tracked from its start flag to its end flag.
        ArrayList<Object> sqs = new ArrayList<>();
        ArrayList<Integer> starts = new ArrayList<>();
        IdentityHashMap<Object, Integer> index = new IdentityHashMap<>();
        HashSet<Object> maskDone = new HashSet<>();

        // stock 322-438: old results go to flag 0; off-screen chunks lose their seen-room mask (once per chunk)
        for (Object o : last) {
            IsoGridSquare sq = (IsoGridSquare) o;
            track(index, sqs, starts, sq, p, now);
            sq.setPlayerCutawayFlag(p, 0, now);
            IsoChunk chunk = sq.getChunk();
            if (chunk != null && maskDone.add(chunk) && !chunk.IsOnScreen(false))
                chunk.getCutawayData().invalidateOccludedSquaresMaskForSeenRooms(p, sq.z);
        }

        // stock 441-575: exterior walls of on-screen chunk levels set their squares visited when the player is not out of range
        for (Object o : chunks) {
            IsoChunk chunk = (IsoChunk) o;
            if (camZ < chunk.minLevel || camZ > chunk.maxLevel) continue;
            if (!chunk.getRenderLevels(p).isOnScreen(camZ)) continue;
            for (Object w : chunk.getCutawayDataForLevel(camZ).exteriorWalls)
                if (!(boolean) isInRange.invoke(w, p, outOfRange)) setVisited.invoke(w, pd);
        }

        // stock 581-774: new north squares get flag 1, then new west squares get flag 2
        for (Object o : north) {
            IsoGridSquare sq = (IsoGridSquare) o;
            track(index, sqs, starts, sq, p, now);
            sq.addPlayerCutawayFlag(p, NORTH, now);
        }
        for (Object o : west) {
            IsoGridSquare sq = (IsoGridSquare) o;
            track(index, sqs, starts, sq, p, now);
            sq.addPlayerCutawayFlag(p, WEST, now);
        }

        // Chunk levels: only a chunk where a square's flag changed is invalidated (all touched chunks when the key is off).
        int n = sqs.size();
        int[] chunkOf = new int[n], before = new int[n], after = new int[n];
        IdentityHashMap<Object, Integer> chunkIndex = new IdentityHashMap<>();
        ArrayList<IsoChunk> chunkList = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            IsoGridSquare sq = (IsoGridSquare) sqs.get(i);
            IsoChunk chunk = sq.getChunk();
            if (chunk == null) {
                chunkOf[i] = -1;
            } else {
                Integer k = chunkIndex.get(chunk);
                if (k == null) {
                    k = chunkList.size();
                    chunkIndex.put(chunk, k);
                    chunkList.add(chunk);
                }
                chunkOf[i] = k;
            }
            before[i] = starts.get(i);
            after[i] = sq.getPlayerCutawayFlag(p, now);
        }
        boolean[] changed = new boolean[chunkList.size()];
        if (changedOnly) changed = changedChunks(chunkOf, before, after, chunkList.size());
        else Arrays.fill(changed, true);
        boolean[] invalidated = new boolean[chunkList.size()];
        for (int i = 0; i < n; i++) {
            int k = chunkOf[i];
            if (k < 0 || !changed[k] || invalidated[k]) continue;
            invalidated[k] = true;
            chunkList.get(k).getRenderLevels(p).invalidateLevel(((IsoGridSquare) sqs.get(i)).z, DIRTY_CUTAWAYS);
        }

        // stock 777-1055: the four edge booleans of each changed chunk; a change invalidates the neighbour chunk
        for (int k = 0; k < chunkList.size(); k++) {
            if (!changed[k]) continue;
            IsoChunk chunk = chunkList.get(k);
            FBORenderCutaways.ChunkLevelData d = chunk.getCutawayData().getDataForLevel(camZ);
            if (d == null) continue;
            int wx = chunk.wx, wy = chunk.wy;
            boolean a = (boolean) hasAnyWalls.invoke(cut, p, chunk, camZ, (byte) NORTH, 0, 0, 0, 7);
            if (a != d.hasCutawayNorthWallsOnWestEdge) {
                d.hasCutawayNorthWallsOnWestEdge = a;
                invalidateChunk.invoke(cut, p, map, wx - 1, wy, camZ);
            }
            boolean b = (boolean) hasAnyWalls.invoke(cut, p, chunk, camZ, (byte) NORTH, 7, 0, 7, 7);
            if (b != d.hasCutawayNorthWallsOnEastEdge) {
                d.hasCutawayNorthWallsOnEastEdge = b;
                invalidateChunk.invoke(cut, p, map, wx + 1, wy, camZ);
            }
            boolean e = (boolean) hasAnyWalls.invoke(cut, p, chunk, camZ, (byte) WEST, 0, 0, 7, 0);
            if (e != d.hasCutawayWestWallsOnNorthEdge) {
                d.hasCutawayWestWallsOnNorthEdge = e;
                invalidateChunk.invoke(cut, p, map, wx, wy - 1, camZ);
            }
            // stock stores the north-edge value (local 13) here; the ruling stores the south-edge value
            boolean s = (boolean) hasAnyWalls.invoke(cut, p, chunk, camZ, (byte) WEST, 0, 7, 7, 7);
            if (s != d.hasCutawayWestWallsOnSouthEdge) {
                d.hasCutawayWestWallsOnSouthEdge = s;
                invalidateChunk.invoke(cut, p, map, wx, wy + 1, camZ);
            }
        }
        return true;
    }

    /** Records a square's flag before this visit first changes it; later sightings keep the first value. */
    private static void track(IdentityHashMap<Object, Integer> index, ArrayList<Object> sqs, ArrayList<Integer> starts,
                              IsoGridSquare sq, int p, long now) {
        if (index.containsKey(sq)) return;
        index.put(sq, sqs.size());
        sqs.add(sq);
        starts.add(sq.getPlayerCutawayFlag(p, now));
    }
}
