package pzbutter;

import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.GridSquareEdge;
import zombie.iso.objects.GridSquareEdgeFacingDirection;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;

/** Plan 6a chunk-texture checks (Task 1: tree keys; Task 2: edge test; Task 3: small keys). Run from SelfTest.main. */
final class ChunkTest {
    private ChunkTest() {}

    static void run() {
        treePerFrameRules();
        stencilTestMatchesStock();
        drivingRule();
        plan6aKeysDefaults();
        settingsTemplateHasPlan6Keys();
        fadingSplatsDropped();
        mipRules();
        smallKeysOffByDefault();
        edgeOrderMatchesStock();
        edgeKeyOffByDefault();
        edgeAnswersMatchStock();
    }

    static void fadingSplatsDropped() {
        java.util.List<Object> fade = new java.util.ArrayList<>(java.util.List.of(new Object(), new Object()));
        BakeMisc.dropFading(fade);
        assert fade.isEmpty();
    }

    static void mipRules() {
        assert BakeMisc.mipApplies(3, true, false) && BakeMisc.maxLevel(3) == 2;
        assert !BakeMisc.mipApplies(0, true, false) && !BakeMisc.mipApplies(3, false, false) && !BakeMisc.mipApplies(3, true, true);
    }

    static void smallKeysOffByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        BakeMisc.init();
        assert !ChunkPatches.bloodFadeGuard.on && !ChunkPatches.mipGuard.on;
        Settings.load(SelfTest.write("enabled=true\nbakeMipLevels=3\n"));
        BakeMisc.init();
        assert ChunkPatches.mipGuard.on && BakeMisc.mipLevels == 3;
    }

    static void treePerFrameRules() {
        assert ChunkTrees.treePerFrame(true, false, false) && !ChunkTrees.treePerFrame(false, false, false);   // key off: stock
        assert !ChunkTrees.treePerFrame(true, true, false);                                                     // baked
        assert ChunkTrees.treePerFrame(true, true, true) && !ChunkTrees.treePerFrame(false, true, true);        // marked chunk: per frame
    }

    static void stencilTestMatchesStock() {
        assert ChunkTrees.testStencil(true, 0, 0, 10, 10, false);                    // cutting: always ask the stencil
        assert ChunkTrees.testStencil(false, 10, 10, 10, 10, true);                  // on or south-east of the camera square
        assert !ChunkTrees.testStencil(false, 9, 12, 10, 10, true) && !ChunkTrees.testStencil(false, 12, 9, 10, 10, true);
        assert !ChunkTrees.testStencil(false, 12, 12, 10, 10, false);                // no camera square
    }

    static void drivingRule() {
        assert ChunkTrees.cutForDriving(false, true, true) && !ChunkTrees.cutForDriving(false, true, false);
        assert ChunkTrees.cutForDriving(true, false, false) && !ChunkTrees.cutForDriving(false, false, true);
    }

    static void plan6aKeysDefaults() {
        Settings.load(SelfTest.write("enabled=true\n"));
        for (String k : new String[] {"treesInChunkTexture", "edgeTestFast", "translucentTilesInChunkTexture", "bloodFadeFix",
                                      "bakeScheduler", "bakeBudgetAdaptive", "occlusionGrantedOnly"})
            assert !Settings.flag(k) : k;
        for (String k : new String[] {"driveTreeCutaway", "glassTilesPerFrame", "translucentLightsPerFrame"})
            assert Settings.flag(k) : k;
        for (String k : new String[] {"treeBakeMaxChunksPerSec", "bakeMipLevels", "zoomEaseMs"})
            assert Settings.num(k, 0, 0, 100000) == 0 : k;
        ChunkTrees.init();
        assert ChunkPatches.treeBakeGuard.on == false && ChunkPatches.driveCutGuard.on == false;
        Settings.load(SelfTest.write("enabled=true\ndriveTreeCutaway=false\ntreesInChunkTexture=true\n"));
        ChunkTrees.init();
        assert ChunkPatches.driveCutGuard.on && ChunkPatches.treeBakeGuard.on;
        Settings.load(SelfTest.write("enabled=false\ndriveTreeCutaway=false\ntreesInChunkTexture=true\n"));
        ChunkTrees.init();
        assert !ChunkPatches.driveCutGuard.on && !ChunkPatches.treeBakeGuard.on;
    }

    static void settingsTemplateHasPlan6Keys() {
        String t = Settings.render(new java.util.Properties());
        for (String k : new String[] {"treesInChunkTexture=false", "driveTreeCutaway=true", "zoomEase=0.25,0.1,0.25,1.0",
                                      "bakeFrameBudget=8", "bloodRebakeCoalesceMs=250"})
            assert t.contains("\n" + k + "\n") : k;
    }

    static int step(int who, int edge) { return who << 2 | edge; }

    static void edgeOrderMatchesStock() {
        final int N = 0, W = 1, S = 2, E = 3, SELF = 0, OTHER = 1;
        assert java.util.Arrays.equals(EdgeTest.order(1, 0), new int[] {step(SELF, W), step(OTHER, E)});
        assert java.util.Arrays.equals(EdgeTest.order(-1, 0), new int[] {step(OTHER, W), step(SELF, E)});
        assert java.util.Arrays.equals(EdgeTest.order(0, 1), new int[] {step(SELF, N), step(OTHER, S)});
        assert java.util.Arrays.equals(EdgeTest.order(0, -1), new int[] {step(OTHER, N), step(SELF, S)});
        assert java.util.Arrays.equals(EdgeTest.order(1, -1), new int[] {step(SELF, W), step(OTHER, E), step(OTHER, N), step(SELF, S)});
        assert java.util.Arrays.equals(EdgeTest.order(-1, 1), new int[] {step(OTHER, W), step(SELF, E), step(SELF, N), step(OTHER, S)});
        assert EdgeTest.order(0, 0).length == 0;                                     // same x/y, other level: only the stair test
        assert EdgeTest.order(1, 1) == EdgeTest.order(1, 1);                         // precomputed, no allocation
        assert EdgeTest.order(7, -3) == EdgeTest.order(1, -1);                       // signs only
    }

    static void edgeKeyOffByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        EdgeTest.init();
        assert !ChunkPatches.edgeGuard.on;
        Settings.load(SelfTest.write("enabled=true\nedgeTestFast=true\n"));
        EdgeTest.init();
        assert ChunkPatches.edgeGuard.on;
        Settings.load(SelfTest.write("enabled=false\nedgeTestFast=true\n"));
        EdgeTest.init();
        assert !ChunkPatches.edgeGuard.on;
    }

    /** A window or door object whose facing and blocked state are plain fields (no sprite is needed headless). */
    static final class FakeWindow extends IsoWindow {
        boolean north, blocked;
        FakeWindow() { super((IsoCell) null); }
        @Override public boolean getNorth() { return north; }
        @Override public boolean isBlocked() { return blocked; }
    }

    static final class FakeDoor extends IsoDoor {
        boolean north, blocked;
        FakeDoor() { super((IsoCell) null); }
        @Override public boolean getNorth() { return north; }
        @Override public boolean isBlocked() { return blocked; }
    }

    /** One edge test as stock's isEdgeElementTo passes it: the square, the edge and the neighbour lookup. */
    interface EdgePred { boolean test(IsoGridSquare sq, GridSquareEdge edge, IsoGridSquare.GetSquare gs); }

    /** The per-facing test stock's isEdgeElement is given (wall, window or door on one facing). */
    interface FacingPred { boolean test(IsoGridSquare sq, GridSquareEdgeFacingDirection dir); }

    /** Stock isEdgeElement (bytecode 9689-9750): NORTH and WEST test the square, SOUTH and EAST its neighbour. */
    static boolean stockEdge(IsoGridSquare sq, GridSquareEdge edge, IsoGridSquare.GetSquare gs, FacingPred p) {
        switch (edge) {
            case NORTH: return p.test(sq, GridSquareEdgeFacingDirection.NORTH_SOUTH);
            case WEST: return p.test(sq, GridSquareEdgeFacingDirection.EAST_WEST);
            case SOUTH: { IsoGridSquare s = sq.getSouth(gs); return s != null && p.test(s, GridSquareEdgeFacingDirection.NORTH_SOUTH); }
            default: { IsoGridSquare e = sq.getEast(gs); return e != null && p.test(e, GridSquareEdgeFacingDirection.EAST_WEST); }
        }
    }

    /**
     * Stock isEdgeElementTo (bytecode 9842-9900): null or the same square is false; then four separate tests (not
     * else-if): x greater (self WEST, other EAST), x less (other WEST, self EAST), y greater (self NORTH, other SOUTH),
     * y less (other NORTH, self SOUTH).
     */
    static boolean stockEdgeTo(IsoGridSquare self, IsoGridSquare o, IsoGridSquare.GetSquare gs, EdgePred p) {
        if (o == null || o == self) return false;
        if (self.isEastOf(o) && (p.test(self, GridSquareEdge.WEST, gs) || p.test(o, GridSquareEdge.EAST, gs))) return true;
        if (self.isWestOf(o) && (p.test(o, GridSquareEdge.WEST, gs) || p.test(self, GridSquareEdge.EAST, gs))) return true;
        if (self.isSouthOf(o) && (p.test(self, GridSquareEdge.NORTH, gs) || p.test(o, GridSquareEdge.SOUTH, gs))) return true;
        if (self.isNorthOf(o) && (p.test(o, GridSquareEdge.NORTH, gs) || p.test(self, GridSquareEdge.SOUTH, gs))) return true;
        return false;
    }

    static final EdgePred WALL = (sq, e, gs) -> stockEdge(sq, e, gs, (s, d) -> s.isWall(d));
    static final EdgePred WINDOW = (sq, e, gs) -> stockEdge(sq, e, gs, (s, d) -> s.hasBlockedWindow(d));
    static final EdgePred DOOR = (sq, e, gs) -> stockEdge(sq, e, gs, (s, d) -> s.hasBlockedDoor(d));

    /** Stock isBlockedTo(o, gs): wall, window, door, then the stair test (bytecode 2045-2070). */
    static boolean oracleBlocked(IsoGridSquare a, IsoGridSquare b, IsoGridSquare.GetSquare gs) {
        return stockEdgeTo(a, b, gs, WALL) || stockEdgeTo(a, b, gs, WINDOW) || stockEdgeTo(a, b, gs, DOOR) || a.isStairBlockedTo(b);
    }

    /**
     * EdgeTest.blockedTo equals stock for every pair of squares in a 3 x 3 patch on two levels (null included), over
     * random walls, windows (flags and objects, both facings), doors and plain objects. The oracle is stock's own
     * isBlockedTo (run directly, with the same GetSquare) and the stock logic written from the bytecode above.
     */
    static void edgeAnswersMatchStock() {
        // the property names used below must set the flags the stock checks read
        IsoGridSquare probe = new IsoGridSquare(null, null, 0, 0, 0);
        probe.getProperties().set("collideN", "true");
        probe.getProperties().set("collideW", "true");
        probe.getProperties().set("windowN", "true");
        probe.getProperties().set("windowW", "true");
        assert probe.hasProperty(IsoFlagType.collideN) && probe.hasProperty(IsoFlagType.collideW)
            && probe.hasProperty(IsoFlagType.windowN) && probe.hasProperty(IsoFlagType.windowW);
        int checks = 0, blocked = 0;
        for (long seed = 1; seed <= 40; seed++) {
            java.util.Random r = new java.util.Random(seed);
            IsoGridSquare[][][] g = new IsoGridSquare[3][3][2];
            java.util.List<IsoGridSquare> all = new java.util.ArrayList<>();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 2; z++) {
                g[x][y][z] = new IsoGridSquare(null, null, x, y, z);
                all.add(g[x][y][z]);
            }
            for (IsoGridSquare s : all) {
                if (r.nextInt(3) == 0) s.getProperties().set("collideN", "true");
                if (r.nextInt(3) == 0) s.getProperties().set("collideW", "true");
                if (r.nextInt(4) == 0) s.getProperties().set("windowN", "true");
                if (r.nextInt(4) == 0) s.getProperties().set("windowW", "true");
                if (r.nextInt(3) == 0) { FakeWindow w = new FakeWindow(); w.north = r.nextBoolean(); w.blocked = r.nextInt(4) != 0; s.getObjects().add(w); }
                if (r.nextInt(4) == 0) { FakeDoor d = new FakeDoor(); d.north = r.nextBoolean(); d.blocked = r.nextInt(4) != 0; s.getObjects().add(d); }
                if (r.nextInt(4) == 0) s.getObjects().add(new IsoObject((IsoCell) null));
            }
            IsoGridSquare.GetSquare gs = (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < 3 && y < 3 && z < 2 ? g[x][y][z] : null;
            java.util.List<IsoGridSquare> others = new java.util.ArrayList<>(all);
            others.add(null);
            for (IsoGridSquare a : all) {
                for (IsoGridSquare b : others) {
                    boolean want = oracleBlocked(a, b, gs);
                    assert a.isBlockedTo(b, gs) == want : "stock vs oracle " + seed + " " + a.x + "," + a.y + "," + a.z;
                    assert EdgeTest.blockedTo(a, b, gs) == want : "edge vs stock " + seed + " " + a.x + "," + a.y + "," + a.z
                        + " -> " + (b == null ? "null" : b.x + "," + b.y + "," + b.z);
                    checks++;
                    if (want) blocked++;
                }
            }
        }
        // the check must see both answers, or it proves nothing
        assert blocked > 0 && blocked < checks : blocked + " of " + checks;
    }
}
