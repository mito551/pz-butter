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
        edgeKeyOffByDefault();
        edgeAnswersMatchStock();
        bakeableOnlyFlagReason();
        glassAndLightRules();
        minusFloorRestOrder();
        translucentKeysDefaults();
        bezierMatchesCssEase();
        parseRejectsBad();
        easeEndsOnTarget();
        easeCancelledByOtherTarget();
        zoomKeyDefaults();
        holdNeverForNewOrObject();
        budgetNearestFirst();
        classOrderBeatsDistance();
        overdueAlwaysGranted();
        lightRateLimitedButOverdueWins();
        bloodCoalesces();
        classCapsApply();
        adaptiveBudget();
        strongCapHalves();
        globalMoveDemotesStrong();
        strongDeltaDetected();
        planKeepsNoStateBetweenCalls();
    }

    static void bakeableOnlyFlagReason() {
        int T = TranslucentBake.TRANS;
        assert TranslucentBake.bakeable(T, true, true);
        assert !TranslucentBake.bakeable(0, true, true);                                  // no Translucent flag: not ours
        for (int r : new int[] {TranslucentBake.FIRE, TranslucentBake.ANIM, TranslucentBake.WIND, TranslucentBake.TREE,
                                TranslucentBake.FX, TranslucentBake.WINDOW, TranslucentBake.DOOR_TRANS, TranslucentBake.SOLID,
                                TranslucentBake.CURTAIN, TranslucentBake.MANNEQUIN})
            assert !TranslucentBake.bakeable(T | r, false, false) : r;
        assert !TranslucentBake.bakeable(T | TranslucentBake.GLASS, true, false) && TranslucentBake.bakeable(T | TranslucentBake.GLASS, false, false);
        assert !TranslucentBake.bakeable(T | TranslucentBake.LIGHT, false, true) && TranslucentBake.bakeable(T | TranslucentBake.LIGHT, false, false);
        // the rest flags are not reasons: a bakeable object may carry any of them
        assert TranslucentBake.bakeable(T | TranslucentBake.FLOORISH | TranslucentBake.SE_WALL | TranslucentBake.BUSHLIKE, true, true);
    }

    static void glassAndLightRules() {
        assert TranslucentBake.isGlass("walls_exterior_glass_01", false, false, false, false);
        assert TranslucentBake.isGlass("fixtures_windows_01", false, false, false, false);
        assert TranslucentBake.isGlass("Walls_Exterior_GLASS_01", false, false, false, false);
        assert TranslucentBake.isGlass("walls_decoration_02", true, false, false, false);
        assert TranslucentBake.isGlass("walls_decoration_02", false, false, false, true);
        assert !TranslucentBake.isGlass("fencing_damaged_01", false, false, false, false);
        assert !TranslucentBake.isGlass(null, false, false, false, false);
        assert TranslucentBake.isLight("lighting_outdoor_01", false, false) && TranslucentBake.isLight("signs_one-off_03_on", false, false);
        assert TranslucentBake.isLight("walls_decoration_02", true, false) && TranslucentBake.isLight("x", false, true);
        assert !TranslucentBake.isLight("signs_one-off_03", false, false) && !TranslucentBake.isLight(null, false, false);
    }

    static void minusFloorRestOrder() {
        assert TranslucentBake.minusFloorRest(0, 0, 0);
        assert !TranslucentBake.minusFloorRest(TranslucentBake.FLOORISH, 0, 0);
        assert TranslucentBake.minusFloorRest(TranslucentBake.FLOORISH | TranslucentBake.CLIMBING_NOT_SOLIDFLOOR, 0, 0);   // stock override
        assert !TranslucentBake.minusFloorRest(TranslucentBake.CLIMBING_NOT_SOLIDFLOOR | TranslucentBake.SE_WALL, 0, 0);    // later rule wins
        assert !TranslucentBake.minusFloorRest(TranslucentBake.BUSHLIKE | TranslucentBake.FLATTEN | TranslucentBake.CLIMBING_NOT_SOLIDFLOOR, 0, 0); // early return
        assert TranslucentBake.minusFloorRest(TranslucentBake.BUSHLIKE, 0, 0) && TranslucentBake.minusFloorRest(TranslucentBake.FLATTEN, 0, 0);
        assert !TranslucentBake.minusFloorRest(TranslucentBake.MANNEQUIN_EACH_FRAME, 0, 0);
        assert !TranslucentBake.minusFloorRest(TranslucentBake.FIRE, 0, 0);
        assert !TranslucentBake.minusFloorRest(0, 1000, 0) && TranslucentBake.minusFloorRest(TranslucentBake.ALWAYS_DRAW, 1000, 0);
        assert !TranslucentBake.minusFloorRest(TranslucentBake.WEST_ROOF, 999, 999) && TranslucentBake.minusFloorRest(TranslucentBake.WEST_ROOF, 999, 0);
        assert TranslucentBake.minusFloorRest(TranslucentBake.WEST_ROOF, 5, 5);
        assert !TranslucentBake.minusFloorRest(TranslucentBake.WINDOWISH, 0, 0);
        assert !TranslucentBake.minusFloorRest(TranslucentBake.CLIMBING_NOT_SOLIDFLOOR | TranslucentBake.WINDOWISH, 0, 0);
    }

    static void translucentKeysDefaults() {
        Settings.load(SelfTest.write("enabled=true\n"));
        TranslucentBake.init();
        assert !ChunkPatches.translucentGuard.on && TranslucentBake.glassPerFrame && TranslucentBake.lightsPerFrame;
        Settings.load(SelfTest.write("enabled=true\ntranslucentTilesInChunkTexture=true\nglassTilesPerFrame=false\n"));
        TranslucentBake.init();
        assert ChunkPatches.translucentGuard.on && !TranslucentBake.glassPerFrame && TranslucentBake.lightsPerFrame;
        Settings.load(SelfTest.write("enabled=false\ntranslucentTilesInChunkTexture=true\n"));
        TranslucentBake.init();
        assert !ChunkPatches.translucentGuard.on;
        // key off: the hook hands back whatever stock answered
        assert ChunkPatches.onObjectLayer(null, zombie.iso.fboRenderChunk.ObjectRenderLayer.Translucent)
            == zombie.iso.fboRenderChunk.ObjectRenderLayer.Translucent;
    }

    static void bezierMatchesCssEase() {
        float[] ease = ZoomEase.parse("0.25,0.1,0.25,1.0");
        assert ZoomEase.bezier(ease, 0f) == 0f && ZoomEase.bezier(ease, 1f) == 1f;
        assert Math.abs(ZoomEase.bezier(ease, 0.5f) - 0.8024f) < 0.002f;           // CSS "ease" at 50 %
        float prev = 0f;
        for (int i = 1; i <= 100; i++) { float y = ZoomEase.bezier(ease, i / 100f); assert y >= prev - 1e-6f; prev = y; }
        float[] linear = ZoomEase.parse("0,0,1,1");
        assert Math.abs(ZoomEase.bezier(linear, 0.3f) - 0.3f) < 0.001f;
        // steep curve (x1 = x2 = 0 / 1 corners) still solves
        float[] steep = ZoomEase.parse("0,0,0,1");
        float y = ZoomEase.bezier(steep, 0.01f);
        assert y >= 0f && y <= 1f;
    }

    static void parseRejectsBad() {
        float[] d = {0.25f, 0.1f, 0.25f, 1.0f};
        assert java.util.Arrays.equals(ZoomEase.parse("1.5,0,0.5,1"), d);           // x outside [0, 1]
        assert java.util.Arrays.equals(ZoomEase.parse("a,b"), d) && java.util.Arrays.equals(ZoomEase.parse(""), d);
        assert java.util.Arrays.equals(ZoomEase.parse("0.1,0.2,0.3"), d) && java.util.Arrays.equals(ZoomEase.parse("0.1,0.2,0.3,0.4,0.5"), d);
        assert java.util.Arrays.equals(ZoomEase.parse("0.1, 0.2, 0.3, 1.5"), new float[] {0.1f, 0.2f, 0.3f, 1.5f});   // y may leave [0, 1]
    }

    static void easeEndsOnTarget() {
        float[] c = ZoomEase.parse("0.25,0.1,0.25,1.0");
        assert ZoomEase.eased(1f, 2f, 0L, 300_000_000L, 300, c) == 2f;
        assert ZoomEase.eased(1f, 2f, 0L, 0L, 300, c) == 1f;
        float mid = ZoomEase.eased(1f, 2f, 0L, 150_000_000L, 300, c);
        assert mid > 1.5f && mid < 2f;
        assert ZoomEase.eased(1f, 2f, 0L, 5L, 0, c) == 2f;                            // off: jump to the target
    }

    static void easeCancelledByOtherTarget() {
        ZoomEase.curve = ZoomEase.parse("0.25,0.1,0.25,1.0");
        ZoomEase.resetForTest();
        ZoomEase.start(0, 1f, 2f, 0L);
        assert ZoomEase.step(0, 2f, 100_000_000L, 300) > 1f;                          // same target: easing
        assert Float.isNaN(ZoomEase.step(0, 1.5f, 120_000_000L, 300));                // target changed under it: ease ends
        assert Float.isNaN(ZoomEase.step(0, 2f, 130_000_000L, 300));                  // and stays ended
        ZoomEase.start(1, 1f, 2f, 0L);
        assert ZoomEase.step(1, 2f, 300_000_000L, 300) == 2f;                         // the last step is the target itself
        assert Float.isNaN(ZoomEase.step(1, 2f, 310_000_000L, 300));                  // then the ease is done
        assert Float.isNaN(ZoomEase.step(2, 2f, 0L, 300));                            // never started
    }

    static void zoomKeyDefaults() {
        Settings.load(SelfTest.write("enabled=true\n"));
        ZoomEase.init();
        assert !ChunkPatches.zoomGuard.on && ZoomEase.ms == 0;
        assert java.util.Arrays.equals(ZoomEase.curve, new float[] {0.25f, 0.1f, 0.25f, 1.0f});
        Settings.load(SelfTest.write("enabled=true\nzoomEaseMs=300\nzoomEase=0,0,1,1\n"));
        ZoomEase.init();
        assert ZoomEase.ms == 300 && java.util.Arrays.equals(ZoomEase.curve, new float[] {0f, 0f, 1f, 1f});
        assert ChunkPatches.zoomGuard.on;                                             // the private zoom / targetZoom handles resolve
        Settings.load(SelfTest.write("enabled=false\nzoomEaseMs=300\n"));
        ZoomEase.init();
        assert !ChunkPatches.zoomGuard.on;
        Settings.load(SelfTest.write("enabled=true\n"));
        ZoomEase.init();
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

    // ---- Task 6: bake-hold planner core ----

    static BakeHold.Budget budget(int total, int rebake, int strong, int light) {
        BakeHold.Budget b = new BakeHold.Budget();
        b.total = total; b.rebake = rebake; b.strong = strong; b.light = light;
        b.rebakeMaxFrames = 3; b.lightMaxFrames = 30; b.lightMs = 250; b.bloodMs = 250;
        return b;
    }

    static boolean[] plan(BakeHold.Budget b, int[] cls, int[] dist, int[] held, long[] since, long[] last, long now) {
        boolean[] g = new boolean[cls.length];
        BakeHold.plan(cls.length, cls, dist, held, since, last, now, b, g);
        return g;
    }

    static void holdNeverForNewOrObject() {
        assert BakeHold.classify(32, false) == BakeHold.NEW && BakeHold.classify(512 | 32, true) == BakeHold.NEW;
        assert BakeHold.classify(64 | 32, true) == BakeHold.OBJECT && BakeHold.classify(0, true) == BakeHold.OBJECT;
        assert BakeHold.classify(2048 | 32, true) == BakeHold.CUTAWAY && BakeHold.classify(1024, true) == BakeHold.REDRAW;
        assert BakeHold.classify(1 | 32, true) == BakeHold.BLOOD && BakeHold.classify(32, true) == BakeHold.LIGHT;
        // every object, item and corpse flag, and every cutaway flag, picks its class even next to the others
        for (long f : new long[] {2, 4, 8, 16, 64, 128, 256})
            assert BakeHold.classify(f | 2048 | 1024 | 1 | 32, true) == BakeHold.OBJECT : f;
        for (long f : new long[] {2048, 4096, 8192, 16384})
            assert BakeHold.classify(f | 1024 | 1 | 32, true) == BakeHold.CUTAWAY : f;
        boolean[] g = plan(budget(0, 0, 0, 0), new int[] {BakeHold.NEW, BakeHold.OBJECT}, new int[] {9, 9}, new int[2], new long[2], new long[2], 0);
        assert g[0] && g[1];
        BakeHold.Budget b = budget(0, 0, 0, 0);
        plan(b, new int[] {BakeHold.NEW, BakeHold.OBJECT}, new int[] {9, 9}, new int[2], new long[2], new long[2], 0);
        assert b.leftTotal == 0;                                                       // not counted
        assert BakeHold.grantOne(BakeHold.NEW, 0, 0, 0, 0, b) && BakeHold.grantOne(BakeHold.OBJECT, 0, 0, 0, 0, b);
    }

    static void budgetNearestFirst() {
        int R = BakeHold.REDRAW;
        boolean[] g = plan(budget(2, 0, 0, 0), new int[] {R, R, R, R, R}, new int[] {3, 1, 2, 5, 4}, new int[5], new long[5], new long[5], 0);
        assert !g[0] && g[1] && g[2] && !g[3] && !g[4];
        // equal class and distance: the lower index goes first
        g = plan(budget(1, 0, 0, 0), new int[] {R, R}, new int[] {2, 2}, new int[2], new long[2], new long[2], 0);
        assert g[0] && !g[1];
    }

    static void classOrderBeatsDistance() {
        boolean[] g = plan(budget(1, 0, 0, 0), new int[] {BakeHold.LIGHT, BakeHold.CUTAWAY}, new int[] {0, 9}, new int[2], new long[2],
                           new long[] {-1_000_000, -1_000_000}, 0);
        assert !g[0] && g[1];
        // CUTAWAY, REDRAW, STRONG, BLOOD, LIGHT in that order
        int[] cls = {BakeHold.LIGHT, BakeHold.BLOOD, BakeHold.STRONG, BakeHold.REDRAW, BakeHold.CUTAWAY};
        long[] since = new long[5], last = {-1_000_000, -1_000_000, -1_000_000, -1_000_000, -1_000_000};
        for (int k = 1; k <= 5; k++) {
            boolean[] got = plan(budget(k, 0, 0, 0), cls, new int[5], new int[5], new long[] {-1_000_000, -1_000_000, -1_000_000, -1_000_000, -1_000_000}, last, 0);
            int n = 0;
            for (boolean x : got) if (x) n++;
            assert n == k : k;
            for (int i = 4; i > 4 - k; i--) assert got[i] : k + " " + i;               // the last k entries are the most urgent
        }
    }

    static void overdueAlwaysGranted() {
        int R = BakeHold.REDRAW;
        BakeHold.Budget b = budget(1, 0, 0, 0);
        boolean[] g = plan(b, new int[] {R, R, R, R}, new int[] {1, 2, 3, 0}, new int[] {3, 3, 3, 0}, new long[4], new long[4], 0);
        assert g[0] && g[1] && g[2] && !g[3] && b.leftTotal == 0;
        // overdue past a class cap: the counts never go below 0 and the others wait
        b = budget(8, 1, 0, 0);
        g = plan(b, new int[] {R, R, R}, new int[] {1, 2, 3}, new int[] {3, 4, 0}, new long[3], new long[3], 0);
        assert g[0] && g[1] && !g[2] && b.leftRebake == 0 && b.leftTotal == 6;
        // each class has its own limit: 3 frames for cutaway and redraw, 30 for light, blood and strong
        int[] oc = {BakeHold.CUTAWAY, BakeHold.REDRAW, BakeHold.LIGHT, BakeHold.BLOOD, BakeHold.STRONG};
        long[] z5 = new long[5], l5 = {990, 990, 990, 990, 990};
        assert BakeHold.plan(5, oc, new int[5], new int[] {2, 2, 29, 29, 29}, z5, l5, 1000, budget(0, 0, 0, 0), new boolean[5]) == 0;
        assert BakeHold.plan(5, oc, new int[5], new int[] {3, 3, 29, 29, 29}, z5, l5, 1000, budget(0, 0, 0, 0), new boolean[5]) == 2;
        assert BakeHold.plan(5, oc, new int[5], new int[] {2, 2, 30, 30, 30}, z5, l5, 1000, budget(0, 0, 0, 0), new boolean[5]) == 3;
    }

    static void lightRateLimitedButOverdueWins() {
        int L = BakeHold.LIGHT;
        boolean[] g = plan(budget(8, 0, 0, 0), new int[] {L, L}, new int[] {0, 0}, new int[] {5, 30}, new long[2], new long[] {900, 900}, 1000);
        assert !g[0] && g[1];                                   // baked 100 ms ago: waits; held 30 frames: goes
        g = plan(budget(8, 0, 0, 0), new int[] {L}, new int[] {0}, new int[] {1}, new long[1], new long[] {700}, 1000);
        assert g[0];                                            // 300 ms since its bake
        BakeHold.Budget b = budget(8, 0, 0, 0);
        g = plan(b, new int[] {L}, new int[] {0}, new int[] {29}, new long[1], new long[] {999}, 1000);
        assert !g[0] && b.leftTotal == 8;
        b.lightMs = 0;                                          // no limit
        g = plan(b, new int[] {L}, new int[] {0}, new int[] {0}, new long[1], new long[] {999}, 1000);
        assert g[0];
    }

    static void bloodCoalesces() {
        int B = BakeHold.BLOOD;
        assert !plan(budget(8, 0, 0, 0), new int[] {B}, new int[] {0}, new int[1], new long[] {900}, new long[1], 1000)[0];
        assert plan(budget(8, 0, 0, 0), new int[] {B}, new int[] {0}, new int[1], new long[] {740}, new long[1], 1000)[0];
        BakeHold.Budget b = budget(8, 0, 0, 0);
        b.bloodMs = 0;
        assert plan(b, new int[] {B}, new int[] {0}, new int[1], new long[] {1000}, new long[1], 1000)[0];
        assert plan(budget(8, 0, 0, 0), new int[] {B}, new int[] {0}, new int[] {30}, new long[] {1000}, new long[1], 1000)[0];   // overdue
    }

    static void classCapsApply() {
        int L = BakeHold.LIGHT;
        boolean[] g = plan(budget(8, 0, 0, 2), new int[] {L, L, L, L}, new int[] {0, 1, 2, 3}, new int[4], new long[4], new long[4], 1000);
        assert g[0] && g[1] && !g[2] && !g[3];
        BakeHold.Budget b = budget(8, 0, 0, 0);
        plan(b, new int[] {L}, new int[] {0}, new int[1], new long[1], new long[1], 1000);
        assert BakeHold.grantOne(BakeHold.REDRAW, 0, 0, 0, 1000, b) && b.leftTotal == 6;   // after the plan, at the gate
        // rebake caps cutaway and redraw together; strong has its own; blood only the total
        b = budget(8, 2, 1, 0);
        g = plan(b, new int[] {BakeHold.CUTAWAY, BakeHold.REDRAW, BakeHold.REDRAW, BakeHold.STRONG, BakeHold.STRONG, BakeHold.BLOOD},
                 new int[] {0, 1, 2, 3, 4, 5}, new int[6], new long[6], new long[6], 1000);
        assert g[0] && g[1] && !g[2] && g[3] && !g[4] && g[5];
        assert b.leftRebake == 0 && b.leftStrong == 0 && b.leftTotal == 4;
        // at the gate: the same limits, decrementing the same counts
        assert !BakeHold.grantOne(BakeHold.REDRAW, 0, 0, 0, 1000, b) && !BakeHold.grantOne(BakeHold.STRONG, 0, 0, 0, 1000, b);
        assert BakeHold.grantOne(BakeHold.REDRAW, 3, 0, 0, 1000, b) && b.leftTotal == 3 && b.leftRebake == 0;   // overdue at the gate
        assert BakeHold.grantOne(BakeHold.BLOOD, 0, 0, 0, 1000, b) && b.leftTotal == 2;
        assert !BakeHold.grantOne(BakeHold.LIGHT, 0, 0, 900, 1000, b);                  // 100 ms since its bake
        // a spent total holds every class that is not overdue
        b.leftTotal = 0;
        assert !BakeHold.grantOne(BakeHold.CUTAWAY, 0, 0, 0, 1000, b) && !BakeHold.grantOne(BakeHold.BLOOD, 0, 0, 0, 1000, b);
        assert BakeHold.grantOne(BakeHold.CUTAWAY, 3, 0, 0, 1000, b) && b.leftTotal == 0;
    }

    static void adaptiveBudget() {
        assert BakeHold.adaptive(8, 8, 15.5, 16.67) == 4 && BakeHold.adaptive(4, 8, 9.0, 16.67) == 5;
        assert BakeHold.adaptive(2, 8, 16.0, 16.67) == 2 && BakeHold.adaptive(8, 8, 9.0, 16.67) == 8;
        assert BakeHold.adaptive(5, 8, 12.0, 16.67) == 5 && BakeHold.adaptive(3, 8, 99, 0) == 8;
        assert BakeHold.adaptive(1, 8, 12.0, 16.67) == 2 && BakeHold.adaptive(1, 1, 99, 16.67) == 1;   // never below min(2, max)
    }

    static void strongCapHalves() {
        assert BakeHold.strongCap(8, 10.0, 9) == 4 && BakeHold.strongCap(8, 8.0, 9) == 8 && BakeHold.strongCap(1, 10.0, 9) == 1;
        assert BakeHold.strongCap(0, 10.0, 9) == 0 && BakeHold.strongCap(8, 10.0, 0) == 8;
    }

    static void globalMoveDemotesStrong() {
        assert BakeHold.globalMove(30, 60, 6.0, 2) && !BakeHold.globalMove(29, 60, 6.0, 2);
        assert !BakeHold.globalMove(30, 60, 5.0, 2) && !BakeHold.globalMove(60, 60, 99, 0);
        assert !BakeHold.globalMove(0, 0, 99, 2);
    }

    static void strongDeltaDetected() {
        assert BakeHold.maxDelta(new byte[] {10, 20, (byte) 200}, new byte[] {10, 27, (byte) 190}, 3) == 10;
        assert BakeHold.maxDelta(new byte[] {(byte) 255}, new byte[] {0}, 1) == 255;
        assert BakeHold.maxDelta(new byte[] {1, 2, 3}, new byte[] {9, 9, 9}, 0) == 0;
        assert BakeHold.q(1.2f) == 255 && BakeHold.q(-0.1f) == 0 && BakeHold.q(0.5f) == 127;
    }

    static void planKeepsNoStateBetweenCalls() {
        // a large plan (past the first scratch size) twice in a row gives the same answers
        int n = 700;
        int[] cls = new int[n], dist = new int[n], held = new int[n];
        long[] since = new long[n], last = new long[n];
        for (int i = 0; i < n; i++) { cls[i] = BakeHold.CUTAWAY + i % 5; dist[i] = (i * 7) % 13; last[i] = -1_000_000; }
        boolean[] a = plan(budget(9, 0, 0, 0), cls, dist, held, since, last, 0), c = plan(budget(9, 0, 0, 0), cls, dist, held, since, last, 0);
        assert java.util.Arrays.equals(a, c);
        int granted = 0;
        for (boolean x : a) if (x) granted++;
        assert granted == 9;
    }
}
