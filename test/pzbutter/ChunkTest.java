package pzbutter;

import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
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
        easeStaysBetweenEnds();
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
        gateDecisionSharedByPair();
        preparedOnlyWhenGranted();
        occlusionCountsGrantedOnly();
        grantResetsHold();
        slotMatchesStockPairs();
        yoffMatchesStockOrder();
        plan6aHoldKeysNeedMaster();
        holdFlowHeadless();
        lightClassesHeadless();
        lateLightDecidedAtGate();
        snapshotReadsOnlyPairLevels();
        rateCountsLastSecond();
        marksOnlyAboveRate();
        releaseAfterQuietSecond();
        treeRateKeyNeedsTreeBake();
        treeMarksFollowArrivalRate();
        treeReleaseReachesRenderSquareChunks();
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

    /** A curve with y outside [0, 1] never takes the zoom outside the start and the target (zooming in and out). */
    static void easeStaysBetweenEnds() {
        float[] wild = ZoomEase.parse("0.5,-20,0.5,20");
        float[] wild2 = ZoomEase.parse("0.5,20,0.5,-20");
        boolean over = false;
        for (float[] c : new float[][] {wild, wild2})
            for (int i = 1; i < 300; i++) {
                long now = i * 1_000_000L;
                float in = ZoomEase.eased(1f, 2f, 0L, now, 300, c), out = ZoomEase.eased(2f, 0.5f, 0L, now, 300, c);
                assert in >= 1f && in <= 2f : "zoom in: " + in;
                assert out >= 0.5f && out <= 2f : "zoom out: " + out;
                over |= Math.abs(ZoomEase.bezier(c, i / 300f) - 0.5f) > 0.5f;
            }
        assert over : "the curves really overshoot (the clamp is what keeps the zoom inside)";
        assert ZoomEase.eased(1f, 2f, 0L, 300_000_000L, 300, wild) == 2f;
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

    // ---- Task 7: bake-hold planner hooks ----

    static void gateDecisionSharedByPair() {
        BakeHold.PairState st = new BakeHold.PairState();
        BakeHold.Budget b = budget(1, 0, 0, 0); b.leftTotal = 0;                      // budget used up by the plan
        boolean first = BakeHold.gateDecision(st, 7, BakeHold.REDRAW, 1000, b);
        boolean second = BakeHold.gateDecision(st, 7, BakeHold.REDRAW, 1000, b);      // the pair's second level, same frame
        assert first && second && st.heldFrames == 1;
        b.leftTotal = 1;
        assert !BakeHold.gateDecision(new BakeHold.PairState(), 7, BakeHold.REDRAW, 1000, b) && b.leftTotal == 0;
        assert !BakeHold.gateDecision(new BakeHold.PairState(), 7, BakeHold.NEW, 1000, b);   // never held
        // the plan's decision stands for the gate, whatever the budget says by then
        BakeHold.PairState planned = new BakeHold.PairState();
        planned.planFrame = 7; planned.held = false;
        b.leftTotal = 0;
        assert !BakeHold.gateDecision(planned, 7, BakeHold.REDRAW, 1000, b) && planned.heldFrames == 0;
        // a stale plan decides again
        assert BakeHold.gateDecision(planned, 8, BakeHold.REDRAW, 1000, b) && planned.planFrame == 8 && planned.heldFrames == 1;
    }

    static void preparedOnlyWhenGranted() {
        BakeHold.PairState st = new BakeHold.PairState();
        st.planFrame = 5; st.held = true;
        assert BakeHold.skipPrepare(st, 5) && !BakeHold.skipPrepare(st, 6);         // a stale plan never skips
        st.held = false;
        assert !BakeHold.skipPrepare(st, 5);
    }

    static void occlusionCountsGrantedOnly() {
        BakeHold.PairState st = new BakeHold.PairState();
        st.planFrame = 5; st.held = true;
        assert !BakeHold.countsForOcclusion(st, 5) && BakeHold.countsForOcclusion(st, 6);
        st.held = false;
        assert BakeHold.countsForOcclusion(st, 5);
    }

    static void grantResetsHold() {
        BakeHold.PairState st = new BakeHold.PairState();
        st.heldFrames = 4; st.dirtySinceMs = 10;
        BakeHold.granted(st, 99);
        assert st.heldFrames == 0 && st.dirtySinceMs == -1 && st.lastBakeMs == 99;
    }

    static void slotMatchesStockPairs() {
        for (int lvl = -32; lvl < 32; lvl++)
            assert BakeHold.slot(lvl) == (zombie.iso.fboRenderChunk.FBORenderLevels.calculateMinLevel(lvl) + 32) / 2 : lvl;
    }

    static void yoffMatchesStockOrder() {
        assert BakeHold.yoff(0, 1, 96, 0) == 192f && BakeHold.yoff(2, 3, 192, 64) == 2 * 192f + 2 * 192f + 64f;
        assert BakeHold.yoff(-2, -1, 96, 0) == 2 * 96f - 2 * 96f;
    }

    static void plan6aHoldKeysNeedMaster() {
        Settings.load(SelfTest.write("enabled=true\nbakeFrameBudget=4\n"));
        BakeHold.init();
        assert !ChunkPatches.holdGuard.on;                                          // numbers alone do nothing
        assert BakeHold.frameBudget == 4;
        Settings.load(SelfTest.write("enabled=false\nbakeScheduler=true\n"));
        BakeHold.init();
        assert !ChunkPatches.holdGuard.on;
        Settings.load(SelfTest.write("enabled=true\nbakeFrameBudget=9999\nlightingStrongDelta=999\nrebakeBudget=0\n"));
        BakeHold.init();
        assert BakeHold.frameBudget == 256 && BakeHold.strongDelta == 255 && BakeHold.rebakeBudget == 0;
    }

    // a chunk with two levels, on screen, with a texture that has been baked once
    static IsoChunk holdChunk(int wx, int wy, boolean baked) { return holdChunk(wx, wy, baked, 0, 1); }

    /** A chunk with levels minLevel..maxLevel, every square lit 0.5, on screen; the pair that holds minLevel has a texture (initialised when baked) and is clean. */
    static IsoChunk holdChunk(int wx, int wy, boolean baked, int minLevel, int maxLevel) {
        IsoChunk c = new IsoChunk((IsoCell) null);
        c.minLevel = minLevel; c.maxLevel = maxLevel; c.wx = wx; c.wy = wy;
        int n = maxLevel - minLevel + 1;
        c.squares = new zombie.iso.IsoGridSquare[n][64];
        for (int z = minLevel; z <= maxLevel; z++)
            for (int i = 0; i < 64; i++) {
                zombie.iso.IsoGridSquare sq = new zombie.iso.IsoGridSquare(null, null, wx * 8 + i % 8, wy * 8 + i / 8, z);
                sq.setLightInfoServerGUIOnly(new zombie.core.textures.ColorInfo(0.5f, 0.5f, 0.5f, 1f));
                c.squares[z - minLevel][i] = sq;
            }
        zombie.iso.fboRenderChunk.FBORenderLevels lv = c.getRenderLevels(0);
        for (int z = minLevel; z <= maxLevel; z++) lv.setOnScreen(z, true);
        zombie.iso.fboRenderChunk.FBORenderChunk rc = lv.getOrCreateFBOForLevel(minLevel, 1f);
        rc.isInit = baked;
        lv.clearDirty(minLevel, 1f);
        return c;
    }

    /** The IsoCamera frame state the headless tests change, so they can put it back. */
    record Cam(int playerIndex, float x, float y) {
        static Cam set(int playerIndex, float x, float y) {
            IsoCamera.FrameState fs = IsoCamera.frameState;
            Cam was = new Cam(fs.playerIndex, fs.camCharacterX, fs.camCharacterY);
            fs.playerIndex = playerIndex; fs.camCharacterX = x; fs.camCharacterY = y;
            return was;
        }
        void restore() { set(playerIndex, x, y); }
    }

    private static void setLight(IsoChunk c, float v) {
        for (int z = 0; z < 2; z++) for (int i = 0; i < 64; i++) c.squares[z][i].getLightInfo(0).r = v;
    }

    /** The planner, the gate, the prepare skip and the occlusion count on real chunk levels (no GL; the textures are never drawn). */
    static void holdFlowHeadless() {
        boolean clock = FrameClock.on;
        long frameNo = FrameClock.frameNo;
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgrWas = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance;
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgr = new zombie.iso.fboRenderChunk.FBORenderChunkManager();
        zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgr;
        Cam cam = Cam.set(0, 4f, 4f);
        try {
            FrameClock.on = true;
            Settings.load(SelfTest.write("enabled=true\nbakeScheduler=true\nbakeFrameBudget=1\nrebakeBudget=0\nrebakeMaxFrames=3\n"));
            LightChecks.init();
            BakeHold.init();
            assert ChunkPatches.holdGuard.on : "handles must resolve headless";
            IsoChunk near = holdChunk(0, 0, true), far = holdChunk(1, 0, true);
            zombie.iso.fboRenderChunk.FBORenderLevels ln = near.getRenderLevels(0), lf = far.getRenderLevels(0);
            java.util.ArrayList<IsoChunk> chunks = new java.util.ArrayList<>(java.util.List.of(far, near));
            ln.invalidateLevel(0, 1024); lf.invalidateLevel(0, 1024);                    // both want a redraw, one fits
            BakeHold.planChunks(chunks, 0, 1f, 100, 1000);
            assert !BakeHold.peek(ln, 0).held && BakeHold.peek(lf, 0).held : "nearest first";
            assert BakeHold.peek(lf, 0).cls == BakeHold.REDRAW;
            // the gate: the held pair answers for both its levels and shows its old texture
            assert BakeHold.gate(mgr, far, 0, 1f, 0, 100, 1000) && BakeHold.gate(mgr, far, 1, 1f, 0, 100, 1000);
            assert mgr.renderChunk == lf.getFBOForLevel(0, 1f);
            assert !BakeHold.gate(mgr, near, 0, 1f, 0, 100, 1000);
            assert BakeHold.peek(ln, 0).lastBakeMs == 1000;                             // a grant is recorded at the pair's min level
            assert !BakeHold.gate(mgr, near, 1, 1f, 0, 100, 1000);
            assert BakeHold.prepareHeld(0, far, 0, 100) && !BakeHold.prepareHeld(0, near, 0, 100) && !BakeHold.prepareHeld(0, far, 0, 101);
            assert !BakeHold.countsLevel(lf, 0, 100) && !BakeHold.countsLevel(lf, 1, 100) && BakeHold.countsLevel(ln, 0, 100);
            // a held pair is bounded: held for rebakeMaxFrames plans, then granted even with the budget spent
            BakeHold.planChunks(chunks, 0, 1f, 101, 1016);
            BakeHold.planChunks(chunks, 0, 1f, 102, 1032);
            assert BakeHold.peek(lf, 0).held && BakeHold.peek(lf, 0).heldFrames == 3;
            BakeHold.planChunks(chunks, 0, 1f, 103, 1048);
            assert !BakeHold.peek(lf, 0).held : "overdue pair goes";
            assert !BakeHold.gate(mgr, far, 0, 1f, 0, 103, 1048) && BakeHold.peek(lf, 0).heldFrames == 0;
            assert !BakeHold.prepareHeld(0, far, 0, 103);
            // clean levels reset their hold
            lf.clearDirty(0, 1f);
            BakeHold.planChunks(chunks, 0, 1f, 104, 1064);
            assert BakeHold.peek(lf, 0).heldFrames == 0 && BakeHold.peek(lf, 0).dirtySinceMs == -1;
            assert !BakeHold.gate(mgr, far, 0, 1f, 0, 104, 1064);                        // not dirty: stock answers
            // never held: object dirt, a new texture, create
            IsoChunk obj = holdChunk(2, 0, true), fresh = holdChunk(3, 0, false), created = holdChunk(0, 1, true);
            obj.getRenderLevels(0).invalidateLevel(0, 64 | 1024);
            fresh.getRenderLevels(0).invalidateLevel(0, 1024);
            created.getRenderLevels(0).invalidateLevel(0, 512);
            chunks = new java.util.ArrayList<>(java.util.List.of(obj, fresh, created, near));
            BakeHold.planChunks(chunks, 0, 1f, 110, 2000);
            assert !BakeHold.peek(obj.getRenderLevels(0), 0).held && !BakeHold.peek(fresh.getRenderLevels(0), 0).held && !BakeHold.peek(created.getRenderLevels(0), 0).held;
            assert BakeHold.peek(obj.getRenderLevels(0), 0).cls == BakeHold.OBJECT && BakeHold.peek(fresh.getRenderLevels(0), 0).cls == BakeHold.NEW;
            // dirt that arrives after the plan is decided at the gate with the budget left; the second level follows
            IsoChunk late = holdChunk(0, 2, true);
            zombie.iso.fboRenderChunk.FBORenderLevels ll = late.getRenderLevels(0);
            ll.invalidateLevel(0, 1024);
            assert BakeHold.gate(mgr, late, 0, 1f, 0, 110, 2000) && BakeHold.gate(mgr, late, 1, 1f, 0, 110, 2000);
            assert BakeHold.peek(ll, 0).planFrame == 110 && BakeHold.peek(ll, 0).held;
            // a hold never leaves xoff / yoff or the texture unset
            assert mgr.renderChunk == ll.getFBOForLevel(0, 1f);
        } finally {
            FrameClock.on = clock;
            FrameClock.frameNo = frameNo;
            zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgrWas;
            ChunkPatches.holdGuard.on = false;
            cam.restore();
        }
    }

    /** The light snapshot at a grant, STRONG from a square's move, and a global move demoting STRONG. */
    static void lightClassesHeadless() {
        boolean clock = FrameClock.on;
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgrWas = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance;
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgr = new zombie.iso.fboRenderChunk.FBORenderChunkManager();
        zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgr;
        Cam cam = Cam.set(0, 4f, 4f);
        try {
            FrameClock.on = true;
            Settings.load(SelfTest.write("enabled=true\nbakeScheduler=true\nlightingStrongDelta=6\nlightingGlobalDeltaPct=2\nlightingRebakeMs=250\n"));
            BakeHold.init();
            IsoChunk a = holdChunk(0, 0, true), b = holdChunk(1, 0, true);
            zombie.iso.fboRenderChunk.FBORenderLevels la = a.getRenderLevels(0), lb = b.getRenderLevels(0);
            java.util.ArrayList<IsoChunk> chunks = new java.util.ArrayList<>(java.util.List.of(a, b));
            la.invalidateLevel(0, 32); lb.invalidateLevel(0, 32);
            BakeHold.planChunks(chunks, 0, 1f, 1, 10_000);
            assert BakeHold.peek(la, 0).cls == BakeHold.LIGHT : "no snapshot yet: not strong";
            assert !BakeHold.gate(mgr, a, 0, 1f, 0, 1, 10_000);                           // granted: the snapshot is taken
            assert BakeHold.peek(la, 0).light != null && BakeHold.peek(la, 0).light.length == 384;
            // the light moves a little: still LIGHT. One square moves a lot: STRONG
            a.squares[0][5].getLightInfo(0).r = 0.5f + 3 / 255f;
            la.invalidateLevel(0, 32);
            BakeHold.planChunks(chunks, 0, 1f, 2, 10_016);
            assert BakeHold.peek(la, 0).cls == BakeHold.LIGHT;
            a.squares[1][9].getLightInfo(0).g = 0.5f + 12 / 255f;
            BakeHold.planChunks(chunks, 0, 1f, 3, 10_032);
            assert BakeHold.peek(la, 0).cls == BakeHold.STRONG : "a torch beam goes first";
            // both chunks move a lot over every square: a global move, so STRONG is spread like LIGHT
            assert !BakeHold.gate(mgr, b, 0, 1f, 0, 3, 10_032);                           // b gets its snapshot too (it was dirty, granted by budget)
            setLight(a, 0.9f); setLight(b, 0.9f);
            la.invalidateLevel(0, 32); lb.invalidateLevel(0, 32);
            BakeHold.planChunks(chunks, 0, 1f, 4, 10_048);
            assert BakeHold.peek(la, 0).cls == BakeHold.LIGHT && BakeHold.peek(lb, 0).cls == BakeHold.LIGHT : "global move";
            // strong delta 0: every light-only pair is strong
            Settings.load(SelfTest.write("enabled=true\nbakeScheduler=true\nlightingStrongDelta=0\n"));
            BakeHold.init();
            BakeHold.planChunks(chunks, 0, 1f, 5, 10_064);
            assert BakeHold.peek(la, 0).cls == BakeHold.STRONG;
        } finally {
            FrameClock.on = clock;
            zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgrWas;
            ChunkPatches.holdGuard.on = false;
            cam.restore();
        }
    }

    /**
     * Light dirt reaches a clean pair after the plan (stock updateChunkLighting runs after prepareChunksForUpdating): the gate
     * decides it. A held-to-be pair with a strong move goes at once as STRONG and its scratch snapshot becomes st.light;
     * a pair that goes anyway is classed LIGHT (no delta) but its snapshot is refreshed.
     */
    static void lateLightDecidedAtGate() {
        boolean clock = FrameClock.on;
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgrWas = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance;
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgr = new zombie.iso.fboRenderChunk.FBORenderChunkManager();
        zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgr;
        Cam cam = Cam.set(0, 4f, 4f);
        try {
            FrameClock.on = true;
            Settings.load(SelfTest.write("enabled=true\nbakeScheduler=true\nlightingStrongDelta=6\nlightingRebakeMs=250\nlightingStrongBudget=1\n"));
            BakeHold.init();
            IsoChunk a = holdChunk(0, 0, true), b = holdChunk(1, 0, true);
            zombie.iso.fboRenderChunk.FBORenderLevels la = a.getRenderLevels(0), lb = b.getRenderLevels(0);
            java.util.ArrayList<IsoChunk> ab = new java.util.ArrayList<>(java.util.List.of(a, b));
            // frame 1: both pairs baked at t = 10 000, each with a snapshot
            la.invalidateLevel(0, 32); lb.invalidateLevel(0, 32);
            BakeHold.planChunks(ab, 0, 1f, 1, 10_000);
            assert !BakeHold.gate(mgr, a, 0, 1f, 0, 1, 10_000) && !BakeHold.gate(mgr, b, 0, 1f, 0, 1, 10_000);
            la.clearDirty(0, 1f); lb.clearDirty(0, 1f);                                  // (stock's endRenderChunkLevel does this after a bake)
            // frame 2, 16 ms later: the plan sees both clean, then the light of one square in each moves a lot (dirt after the plan)
            BakeHold.planChunks(ab, 0, 1f, 2, 10_016);
            a.squares[0][5].getLightInfo(0).r = 0.5f + 40 / 255f;
            b.squares[0][9].getLightInfo(0).g = 0.5f + 40 / 255f;
            la.invalidateLevel(0, 32); lb.invalidateLevel(0, 32);
            assert !BakeHold.gate(mgr, a, 0, 1f, 0, 2, 10_016) : "a strong move goes at once, though LIGHT would wait for lightingRebakeMs";
            assert BakeHold.peek(la, 0).cls == BakeHold.STRONG && !BakeHold.peek(la, 0).held;
            assert !BakeHold.gate(mgr, a, 1, 1f, 0, 2, 10_016) : "the second level follows";
            byte[] snap = BakeHold.peek(la, 0).light;
            assert snap != null && (snap[5 * 3] & 255) == BakeHold.q(0.5f + 40 / 255f) : "the snapshot taken for the decision is the new one";
            // the strong cap (1) is spent: the second strong move in the frame waits
            assert BakeHold.gate(mgr, b, 0, 1f, 0, 2, 10_016) : "the strong cap is kept";
            assert BakeHold.peek(lb, 0).cls == BakeHold.STRONG && BakeHold.peek(lb, 0).held;
            // a small move 16 ms after the last bake stays LIGHT and waits
            IsoChunk c = holdChunk(0, 1, true);
            zombie.iso.fboRenderChunk.FBORenderLevels lc = c.getRenderLevels(0);
            java.util.ArrayList<IsoChunk> cc = new java.util.ArrayList<>(java.util.List.of(c));
            lc.invalidateLevel(0, 32);
            BakeHold.planChunks(cc, 0, 1f, 3, 20_000);
            assert !BakeHold.gate(mgr, c, 0, 1f, 0, 3, 20_000);                         // baked at 20 000 with a snapshot
            lc.clearDirty(0, 1f);
            BakeHold.planChunks(cc, 0, 1f, 4, 20_016);
            c.squares[0][3].getLightInfo(0).b = 0.5f + 3 / 255f;
            lc.invalidateLevel(0, 32);
            assert BakeHold.gate(mgr, c, 0, 1f, 0, 4, 20_016) && BakeHold.peek(lc, 0).cls == BakeHold.LIGHT;
            // 400 ms later the pair is eligible as LIGHT: no STRONG decision is needed, but the snapshot is refreshed on the grant
            lc.clearDirty(0, 1f);
            BakeHold.planChunks(cc, 0, 1f, 5, 20_400);
            c.squares[0][3].getLightInfo(0).b = 0.5f + 50 / 255f;
            lc.invalidateLevel(0, 32);
            assert !BakeHold.gate(mgr, c, 0, 1f, 0, 5, 20_400);
            assert BakeHold.peek(lc, 0).cls == BakeHold.LIGHT : "granted anyway: LIGHT";
            assert (BakeHold.peek(lc, 0).light[3 * 3 + 2] & 255) == BakeHold.q(0.5f + 50 / 255f) : "snapshot refreshed on the grant";
            // a global move found by the plan also keeps late pairs LIGHT
            IsoChunk d = holdChunk(0, 2, true), e = holdChunk(1, 2, true), f = holdChunk(2, 2, true);
            java.util.ArrayList<IsoChunk> def = new java.util.ArrayList<>(java.util.List.of(d, e, f));
            for (IsoChunk x : def) x.getRenderLevels(0).invalidateLevel(0, 32);
            BakeHold.planChunks(def, 0, 1f, 6, 30_000);
            for (IsoChunk x : def) { assert !BakeHold.gate(mgr, x, 0, 1f, 0, 6, 30_000); x.getRenderLevels(0).clearDirty(0, 1f); }
            setLight(d, 0.9f); setLight(e, 0.9f);
            d.getRenderLevels(0).invalidateLevel(0, 32); e.getRenderLevels(0).invalidateLevel(0, 32);
            BakeHold.planChunks(def, 0, 1f, 7, 30_016);
            assert BakeHold.peek(d.getRenderLevels(0), 0).cls == BakeHold.LIGHT && BakeHold.peek(e.getRenderLevels(0), 0).cls == BakeHold.LIGHT : "global move";
            f.squares[0][1].getLightInfo(0).r = 0.5f + 40 / 255f;
            f.getRenderLevels(0).invalidateLevel(0, 32);
            assert BakeHold.gate(mgr, f, 0, 1f, 0, 7, 30_016) && BakeHold.peek(f.getRenderLevels(0), 0).cls == BakeHold.LIGHT : "global move: late pairs spread too";
        } finally {
            FrameClock.on = clock;
            zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgrWas;
            ChunkPatches.holdGuard.on = false;
            cam.restore();
        }
    }

    /** A basement chunk (minLevel -1): the pair (-2, -1) snapshots level -1 only, never the ground floor that belongs to pair (0, 1). */
    static void snapshotReadsOnlyPairLevels() {
        IsoChunk c = new IsoChunk((IsoCell) null);
        c.minLevel = -1; c.maxLevel = 1; c.wx = 0; c.wy = 0;
        c.squares = new zombie.iso.IsoGridSquare[3][64];
        for (int z = -1; z <= 1; z++)
            for (int i = 0; i < 64; i++) {
                zombie.iso.IsoGridSquare sq = new zombie.iso.IsoGridSquare(null, null, i % 8, i / 8, z);
                sq.setLightInfoServerGUIOnly(new zombie.core.textures.ColorInfo(z == -1 ? 0.2f : z == 0 ? 0.6f : 1f, 0f, 0f, 1f));
                c.squares[z + 1][i] = sq;
            }
        zombie.iso.fboRenderChunk.FBORenderLevels lv = c.getRenderLevels(0);
        byte[] out = new byte[BakeHold.SNAP];
        BakeHold.snapshot(c, lv, -1, 0, out);                                           // pair (-2, -1)
        int basement = BakeHold.q(0.2f);
        for (int i = 0; i < 64; i++) {
            assert out[i * 3] == 0 : "level -2 does not exist";
            assert (out[192 + i * 3] & 255) == basement : "level -1";
        }
        BakeHold.snapshot(c, lv, 0, 0, out);                                            // pair (0, 1)
        for (int i = 0; i < 64; i++) {
            assert (out[i * 3] & 255) == BakeHold.q(0.6f) && (out[192 + i * 3] & 255) == BakeHold.q(1f);
        }
        BakeHold.snapshot(c, lv, -2, 0, out);                                           // any level of a pair reads the same pair
        assert (out[192] & 255) == basement && out[0] == 0;
    }

    // ---- Task 8: trees stay per frame while chunks stream in ----

    static void rateCountsLastSecond() {
        ChunkTrees.Rate r = new ChunkTrees.Rate();
        for (int i = 0; i < 12; i++) r.add(i * 50_000_000L);                         // 12 arrivals over 0.55 s
        assert r.perSecond(600_000_000L) == 12;
        assert r.perSecond(1_700_000_000L) == 0;                                     // all older than one second
        // buckets are reused after a second: a late arrival does not see a stale count
        r.add(2_000_000_000L);
        assert r.perSecond(2_000_000_000L) == 1;
        // the window slides: arrivals at 0.0 and 0.5 s, asked at 1.05 s, only the later one is left
        ChunkTrees.Rate q = new ChunkTrees.Rate();
        q.add(0L); q.add(500_000_000L);
        assert q.perSecond(900_000_000L) == 2 && q.perSecond(1_050_000_000L) == 1;
    }

    static void marksOnlyAboveRate() {
        assert ChunkTrees.markNew(9, 8) && !ChunkTrees.markNew(8, 8) && !ChunkTrees.markNew(99, 0);
    }

    static void releaseAfterQuietSecond() {
        assert !ChunkTrees.releaseNow(3, 8, 0L, 900_000_000L) && ChunkTrees.releaseNow(3, 8, 0L, 1_000_000_000L);
        assert !ChunkTrees.releaseNow(9, 8, 0L, 5_000_000_000L) && !ChunkTrees.releaseNow(3, 8, -1L, 5_000_000_000L);
        // quietSinceNs: set when the rate falls to the limit, cleared when it rises above it
        ChunkTrees.Rate r = new ChunkTrees.Rate();
        assert r.quietSinceNs < 0;
        for (int i = 0; i < 9; i++) r.add(i * 10_000_000L);
        r.update(100_000_000L, 8);
        assert r.quietSinceNs < 0;                                                   // 9 a second: not quiet
        r.update(1_500_000_000L, 8);
        assert r.quietSinceNs == 1_500_000_000L;                                     // all older than a second
        r.update(1_700_000_000L, 8);
        assert r.quietSinceNs == 1_500_000_000L;                                     // kept while it stays quiet
        for (int i = 0; i < 9; i++) r.add(1_800_000_000L + i);
        r.update(1_800_000_100L, 8);
        assert r.quietSinceNs < 0;
    }

    static void treeRateKeyNeedsTreeBake() {
        Settings.load(SelfTest.write("enabled=true\ntreeBakeMaxChunksPerSec=8\n"));
        ChunkTrees.init();
        assert !ChunkPatches.treeRateGuard.on && ChunkTrees.maxPerSec == 8;
        Settings.load(SelfTest.write("enabled=true\ntreesInChunkTexture=true\ntreeBakeMaxChunksPerSec=8\n"));
        ChunkTrees.init();
        assert ChunkPatches.treeRateGuard.on;
        Settings.load(SelfTest.write("enabled=true\ntreesInChunkTexture=true\n"));
        ChunkTrees.init();
        assert !ChunkPatches.treeRateGuard.on;                                       // 0 = off
        Settings.load(SelfTest.write("enabled=true\n"));
        ChunkTrees.init();
    }

    /** New textures arriving faster than the limit mark their chunk; a quiet second releases the marks with a tree redraw. */
    static void treeMarksFollowArrivalRate() {
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgrWas = zombie.iso.fboRenderChunk.FBORenderChunkManager.instance;
        zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = new zombie.iso.fboRenderChunk.FBORenderChunkManager();
        boolean fboWas = zombie.core.PerformanceSettings.fboRenderChunk;
        zombie.core.PerformanceSettings.fboRenderChunk = true;                       // invalidateRenderChunkLevel does nothing without it
        Cam cam = Cam.set(0, IsoCamera.frameState.camCharacterX, IsoCamera.frameState.camCharacterY);
        try {
            Settings.load(SelfTest.write("enabled=true\ntreesInChunkTexture=true\ntreeBakeMaxChunksPerSec=2\n"));
            ChunkTrees.init();
            IsoChunk[] cs = new IsoChunk[5];
            for (int i = 0; i < cs.length; i++) {
                cs[i] = holdChunk(i, 0, false);                                        // no initialised texture yet
                cs[i].getRenderLevels(0).invalidateLevel(0, 1024);
            }
            for (int i = 0; i < cs.length; i++) ChunkTrees.atGate(cs[i], 0, 1f, 0, 10_000_000L * i);
            assert !ChunkTrees.isMarked(cs[0]) && !ChunkTrees.isMarked(cs[1]) : "within the limit";
            // the 3rd, 4th and 5th arrival in the same second are over 2 a second
            assert ChunkTrees.isMarked(cs[2]) && ChunkTrees.isMarked(cs[3]) && ChunkTrees.isMarked(cs[4]);
            // the second level of a pair and a pair with a texture are not arrivals
            IsoChunk done = holdChunk(7, 0, true);
            done.getRenderLevels(0).invalidateLevel(0, 1024);
            ChunkTrees.atGate(done, 0, 1f, 0, 60_000_000L);
            ChunkTrees.atGate(cs[0], 1, 1f, 0, 60_000_000L);
            assert !ChunkTrees.isMarked(done) && !ChunkTrees.isMarked(cs[0]);
            // release: nothing while the rate is above the limit, nothing before a quiet second, then every marked chunk is re-baked for its trees
            for (IsoChunk c : cs) c.getRenderLevels(0).clearDirty(0, 1f);
            ChunkTrees.release(0, 500_000_000L);
            assert ChunkTrees.isMarked(cs[3]);
            ChunkTrees.release(0, 1_300_000_000L);                                    // quiet begins
            assert ChunkTrees.isMarked(cs[3]);
            ChunkTrees.release(0, 2_400_000_000L);
            assert !ChunkTrees.isMarked(cs[2]) && !ChunkTrees.isMarked(cs[3]) && !ChunkTrees.isMarked(cs[4]);
            assert cs[3].getRenderLevels(0).isDirty(0, 4096L, 1f) && cs[4].getRenderLevels(0).isDirty(0, 4096L, 1f);
            assert !cs[0].getRenderLevels(0).isDirty(0, 1f) : "an unmarked chunk is left alone";
            // a marked chunk that left the screen is unmarked without a redraw
            for (int i = 1; i <= 3; i++) {
                IsoChunk f = holdChunk(20 + i, 0, false);
                f.getRenderLevels(0).invalidateLevel(0, 1024);
                ChunkTrees.atGate(f, 0, 1f, 0, 3_000_000_000L + i);
            }
            IsoChunk gone = holdChunk(30, 0, false);
            gone.getRenderLevels(0).invalidateLevel(0, 1024);
            ChunkTrees.atGate(gone, 0, 1f, 0, 3_000_000_100L);
            assert ChunkTrees.isMarked(gone);
            ChunkTrees.release(0, 3_100_000_000L);
            gone.getRenderLevels(0).clearDirty(0, 1f);
            gone.getRenderLevels(0).setOnScreen(0, false);
            ChunkTrees.release(0, 4_100_000_000L);
            assert ChunkTrees.isMarked(gone) : "not yet a quiet second";
            ChunkTrees.release(0, 5_200_000_000L);
            assert !ChunkTrees.isMarked(gone) && !gone.getRenderLevels(0).isDirty(0, 4096L, 1f);
        } finally {
            zombie.core.PerformanceSettings.fboRenderChunk = fboWas;
            zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgrWas;
            cam.restore();
            Settings.load(SelfTest.write("enabled=true\n"));
            ChunkTrees.init();
        }
    }

    /**
     * Stock draws a corner jumbo tree into the chunk of its render square (south of local (0, 7), east of local (7, 0)) and skips
     * it in its own chunk's bake: the release re-bakes that chunk too, for all four players. A tree elsewhere asks nothing.
     */
    static void treeReleaseReachesRenderSquareChunks() {
        boolean fboWas = zombie.core.PerformanceSettings.fboRenderChunk;
        zombie.core.PerformanceSettings.fboRenderChunk = true;
        try {
            IsoChunk a = holdChunk(0, 0, true), south = holdChunk(0, 1, true), east = holdChunk(1, 0, true), other = holdChunk(0, 2, true);
            for (IsoChunk c : new IsoChunk[] {a, south, east, other})
                for (int z = 0; z < 2; z++) for (int i = 0; i < 64; i++) c.squares[z][i].chunk = c;
            zombie.iso.objects.IsoTree sTree = new zombie.iso.objects.IsoTree(), eTree = new zombie.iso.objects.IsoTree(), plain = new zombie.iso.objects.IsoTree();
            IsoGridSquare sq07 = a.getGridSquare(0, 7, 0), sq70 = a.getGridSquare(7, 0, 0), sq33 = a.getGridSquare(3, 3, 0);
            sq07.getObjects().add(sTree); sq70.getObjects().add(eTree); sq33.getObjects().add(plain);
            java.util.function.Function<zombie.iso.objects.IsoTree, IsoGridSquare> rs = t ->
                t == sTree ? south.getGridSquare(0, 0, 0) : t == eTree ? east.getGridSquare(0, 0, 0) : (IsoGridSquare) null;
            for (IsoChunk c : new IsoChunk[] {a, south, east, other}) c.getRenderLevels(0).clearDirty(0, 1f);
            ChunkTrees.invalidateTrees(a, rs);
            for (int p = 0; p < 4; p++) {
                assert a.getRenderLevels(p).isDirty(0, 4096L, 1f) : "the marked chunk, player " + p;
                assert south.getRenderLevels(p).isDirty(0, 4096L, 1f) : "the chunk of the (0, 7) tree's render square, player " + p;
                assert east.getRenderLevels(p).isDirty(0, 4096L, 1f) : "the chunk of the (7, 0) tree's render square, player " + p;
            }
            assert !other.getRenderLevels(0).isDirty(0, 1f) : "an unrelated chunk is left alone";
            // a chunk with no corner tree: only itself; a corner tree drawn into its own chunk adds nothing
            for (IsoChunk c : new IsoChunk[] {a, south, east}) c.getRenderLevels(0).clearDirty(0, 1f);
            sq07.getObjects().remove(sTree);
            ChunkTrees.invalidateTrees(a, t -> a.getGridSquare(7, 0, 0));
            assert a.getRenderLevels(0).isDirty(0, 4096L, 1f) && !south.getRenderLevels(0).isDirty(0, 1f) && !east.getRenderLevels(0).isDirty(0, 1f);
        } finally {
            zombie.core.PerformanceSettings.fboRenderChunk = fboWas;
        }
    }
}
