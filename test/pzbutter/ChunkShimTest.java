package pzbutter;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.textures.MultiTextureFBO2;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderLevels;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;

/**
 * Plan 6 shim checks on real game objects, built without a game world: the held gate's preamble against stock, the occlusion
 * and prepare composition, the translucent layer shim, the tree translucency shim against stock, and the zoom ease hooks.
 * Objects that have no public constructor for a headless build (a player) are allocated without one (jdk.unsupported) and given
 * the few fields the code under test reads. Run from SelfTest.main after ChunkTest.
 */
final class ChunkShimTest {
    private ChunkShimTest() {}

    static void run() {
        heldGatePreambleMatchesStock();
        occlusionCompositionOverChunkList();
        dirtyHolderMapsGuards();
        prepareStaleOnlyWhenPrepared();
        translucentLayerShim();
        translucentTreeMatchesStock();
        zoomEaseHooks();
    }

    // ---- headless construction helpers ----

    private static Object unsafe;
    private static Method allocate;

    /** An instance of {@code c} without running a constructor. */
    static Object allocate(Class<?> c) {
        try {
            if (allocate == null) {
                Class<?> uc = Class.forName("sun.misc.Unsafe");
                Field f = uc.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                unsafe = f.get(null);
                allocate = uc.getMethod("allocateInstance", Class.class);
            }
            return allocate.invoke(unsafe, c);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) { /* look in the superclass */ }
        }
        throw new AssertionError("no field " + name + " in " + c);
    }

    static void set(Object o, Class<?> c, String name, Object v) {
        try { field(c, name).set(o, v); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }

    static Object get(Object o, Class<?> c, String name) {
        try { return field(c, name).get(o); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }

    /** A player the code can ask about aiming and driving: no ECS components (no aim key), a vehicle only when given. */
    static IsoPlayer fakePlayer(boolean inVehicle) {
        IsoPlayer pl = (IsoPlayer) allocate(IsoPlayer.class);
        set(pl, IsoObject.class, "ecsComponentMap", new java.util.HashMap<>());
        if (inVehicle) set(pl, IsoGameCharacter.class, "vehicle", allocate(zombie.vehicles.BaseVehicle.class));
        return pl;
    }

    private static IsoSprite sprite(int depthFlags, String tileset) {
        IsoSprite sp = new IsoSprite();
        sp.depthFlags = depthFlags;
        sp.tilesetName = tileset;
        return sp;
    }

    // ---- Minor 6: a held gate leaves xoff / yoff as stock's preamble does ----

    static void heldGatePreambleMatchesStock() {
        boolean clock = FrameClock.on;
        FBORenderChunkManager mgrWas = FBORenderChunkManager.instance;
        ChunkTest.Cam cam = ChunkTest.Cam.set(0, 4f, 4f);
        try {
            FrameClock.on = true;
            Settings.load(SelfTest.write("enabled=true\nbakeScheduler=true\n"));
            LightChecks.init();
            BakeHold.init();
            assert ChunkPatches.holdGuard.on;
            VarHandle xoff = Game.field(FBORenderChunkManager.class, "xoff", float.class);
            VarHandle yoff = Game.field(FBORenderChunkManager.class, "yoff", float.class);
            // chunk shapes: two levels, a basement chunk (its lowest pair has one level), and a tall chunk (its top pair)
            int[][] shapes = {{0, 1, 0}, {-1, 1, -1}, {-1, 1, 0}, {0, 5, 4}};
            for (int[] sh : shapes) {
                int level = sh[2];
                IsoChunk held = ChunkTest.holdChunk(0, 0, true, sh[0], sh[1]);
                IsoChunk twin = ChunkTest.holdChunk(1, 0, true, sh[0], sh[1]);   // clean, the same shape: stock runs only its preamble
                FBORenderLevels lv = held.getRenderLevels(0);
                for (IsoChunk c : new IsoChunk[] {held, twin}) {                     // a texture for the pair under test
                    c.getRenderLevels(0).getOrCreateFBOForLevel(level, 1f).isInit = true;
                    c.getRenderLevels(0).clearDirty(level, 1f);
                }
                lv.invalidateLevel(level, 1024);
                BakeHold.PairState st = BakeHold.state(lv, level);
                st.planFrame = 7; st.held = true; st.cls = BakeHold.REDRAW;       // the plan holds this pair
                FBORenderChunkManager mine = new FBORenderChunkManager(), stock = new FBORenderChunkManager();
                assert BakeHold.gate(mine, held, level, 1f, 0, 7, 1000) : "held";
                boolean stockAnswer = stock.beginRenderChunkLevel(twin, level, 1f, false, false);
                assert !stockAnswer : "a clean level: stock answers no";
                assert mine.renderChunk != null && stock.renderChunk != null;
                assert (float) xoff.get(mine) == (float) xoff.get(stock) && (float) xoff.get(mine) != 0f : "xoff, shape " + java.util.Arrays.toString(sh);
                assert (float) yoff.get(mine) == (float) yoff.get(stock) : "yoff " + yoff.get(mine) + " vs " + yoff.get(stock) + ", shape " + java.util.Arrays.toString(sh);
                if (level >= 0) assert (float) yoff.get(mine) != 0f;   // (a one-level basement pair, level -1, is genuinely 0: (1 - 1) * 96 + extra)
                assert mine.renderChunk.w == stock.renderChunk.w && mine.renderChunk.getMinLevel() == stock.renderChunk.getMinLevel();
            }
        } finally {
            FrameClock.on = clock;
            FBORenderChunkManager.instance = mgrWas;
            ChunkPatches.holdGuard.on = false;
            cam.restore();
            Settings.load(SelfTest.write("enabled=true\n"));
            BakeHold.init();
        }
    }

    // ---- Minor 7: occlusion composition, prepare ----

    /** occlusionSkipLightingOnly (mask) and occlusionGrantedOnly (skipHeld): neither / either / both over a chunk list. */
    static void occlusionCompositionOverChunkList() {
        IsoChunk lightOnly = ChunkTest.holdChunk(0, 0, true), heldRedraw = ChunkTest.holdChunk(1, 0, true);
        lightOnly.getRenderLevels(0).invalidateLevel(0, 32);                           // lighting dirt, not held
        FBORenderLevels hl = heldRedraw.getRenderLevels(0);
        hl.invalidateLevel(0, 1024);                                                   // redraw dirt, held this frame
        BakeHold.PairState st = BakeHold.state(hl, 0);
        st.planFrame = 50; st.held = true;
        ArrayList<IsoChunk> a = new ArrayList<>(List.of(lightOnly)), b = new ArrayList<>(List.of(heldRedraw)),
            ab = new ArrayList<>(List.of(lightOnly, heldRedraw)), none = new ArrayList<>();
        long nonLight = LightChecks.NON_LIGHT;
        // lighting-only dirt is ignored by the mask; a held pair is ignored by skipHeld
        assert !LightChecks.anyDirty(a, 0, nonLight, false, 1f, 50) && LightChecks.anyDirty(a, 0, -1L, true, 1f, 50) && !LightChecks.anyDirty(a, 0, nonLight, true, 1f, 50);
        assert LightChecks.anyDirty(b, 0, nonLight, false, 1f, 50) && !LightChecks.anyDirty(b, 0, -1L, true, 1f, 50) && !LightChecks.anyDirty(b, 0, nonLight, true, 1f, 50);
        // both lists: the mask leaves the held redraw, skipHeld leaves the lighting pair, both together leave nothing
        assert LightChecks.anyDirty(ab, 0, nonLight, false, 1f, 50);
        assert LightChecks.anyDirty(ab, 0, -1L, true, 1f, 50);
        assert !LightChecks.anyDirty(ab, 0, nonLight, true, 1f, 50);
        // stock asks any dirt (-1) with every pair counted: both chunks count; the hold is over on the next frame
        assert LightChecks.anyDirty(b, 0, -1L, false, 1f, 50) && LightChecks.anyDirty(b, 0, -1L, true, 1f, 51);
        assert !LightChecks.anyDirty(none, 0, -1L, true, 1f, 50);
        // a level that is not on screen never counts
        hl.setOnScreen(0, false); hl.setOnScreen(1, false);
        assert !LightChecks.anyDirty(b, 0, -1L, false, 1f, 50);
        // neither key on: the holder answers null (stock runs) without reading anything
        boolean lightingWas = WorldPatches.occlusionGuard.on, holdWas = ChunkPatches.holdGuard.on;
        WorldPatches.occlusionGuard.on = false; ChunkPatches.holdGuard.on = false;
        try { assert WorldPatches.onDirtyEnter(null, 0) == null; }
        finally { WorldPatches.occlusionGuard.on = lightingWas; ChunkPatches.holdGuard.on = holdWas; }
    }

    /**
     * WorldPatches.onDirtyEnter on the real cell's on-screen list: the guards pick the mask and skipHeld. The chunk list is
     * [lighting-only dirt, a held redraw]; each key alone and both together leave a different answer.
     */
    static void dirtyHolderMapsGuards() {
        boolean lightingWas = WorldPatches.occlusionGuard.on, holdWas = ChunkPatches.holdGuard.on, clock = FrameClock.on, grantedWas = BakeHold.occlusionGranted;
        long frameNo = FrameClock.frameNo;
        FBORenderCell cell = FBORenderCell.instance;
        ArrayList<IsoChunk> list = null;
        ArrayList<IsoChunk> saved = null;
        try {
            Settings.load(SelfTest.write("enabled=true\n"));
            LightChecks.init();
            assert LightChecks.hasChunkHandles();
            @SuppressWarnings("unchecked")
            ArrayList<IsoChunk> l = (ArrayList<IsoChunk>) LightChecks.onScreen(cell, 0);
            list = l;
            saved = new ArrayList<>(list);
            IsoChunk lightOnly = ChunkTest.holdChunk(0, 0, true), heldRedraw = ChunkTest.holdChunk(1, 0, true);
            lightOnly.getRenderLevels(0).invalidateLevel(0, 32);
            heldRedraw.getRenderLevels(0).invalidateLevel(0, 1024);
            BakeHold.PairState st = BakeHold.state(heldRedraw.getRenderLevels(0), 0);
            FrameClock.on = true; FrameClock.frameNo = 50;
            st.planFrame = 50; st.held = true;
            BakeHold.occlusionGranted = true;
            float zoom = zombie.core.Core.getInstance().getZoom(0);
            assert zoom == 1f : "the chunk textures are made at zoom 1";
            boolean[][] cases = {{false, false}, {true, false}, {false, true}, {true, true}};   // {lighting key, granted key}
            // {lightOnly alone, heldRedraw alone} expected, null = stock runs
            Boolean[][] want = {{null, null}, {Boolean.FALSE, Boolean.TRUE}, {Boolean.TRUE, Boolean.FALSE}, {Boolean.FALSE, Boolean.FALSE}};
            for (int k = 0; k < cases.length; k++) {
                WorldPatches.occlusionGuard.on = cases[k][0];
                ChunkPatches.holdGuard.on = cases[k][1];
                for (int which = 0; which < 2; which++) {
                    list.clear();
                    list.add(which == 0 ? lightOnly : heldRedraw);
                    Boolean got = WorldPatches.onDirtyEnter(cell, 0);
                    assert java.util.Objects.equals(got, want[k][which]) : "lighting " + cases[k][0] + ", granted " + cases[k][1] + ", chunk " + which + ": " + got;
                }
            }
            // the granted key is off when the master switch of the hold is off (occlusionGranted false), whatever the guard says
            BakeHold.occlusionGranted = false;
            ChunkPatches.holdGuard.on = true; WorldPatches.occlusionGuard.on = false;
            assert WorldPatches.onDirtyEnter(cell, 0) == null;
        } finally {
            if (list != null) { list.clear(); list.addAll(saved); }
            WorldPatches.occlusionGuard.on = lightingWas; ChunkPatches.holdGuard.on = holdWas;
            FrameClock.on = clock; FrameClock.frameNo = frameNo; BakeHold.occlusionGranted = grantedWas;
        }
    }

    /** ChunkPrepared: a held pair is not prepared and its cutaway masks stay fresh; a prepared chunk's masks go stale. */
    static void prepareStaleOnlyWhenPrepared() {
        boolean fastWas = WorldPatches.fastGuard.on, holdWas = ChunkPatches.holdGuard.on, clock = FrameClock.on;
        long frameNo = FrameClock.frameNo;
        IsoChunk c = ChunkTest.holdChunk(0, 0, true);
        zombie.iso.fboRenderChunk.FBORenderCutaways.ChunkLevelData d = c.getCutawayDataForLevel(0);
        Cutaways.Fast f = new Cutaways.Fast();
        Cutaways.fastTable.put(d, f);
        try {
            WorldPatches.fastGuard.on = true;
            ChunkPatches.holdGuard.on = true;
            FrameClock.on = true; FrameClock.frameNo = 60;
            BakeHold.PairState st = BakeHold.state(c.getRenderLevels(0), 0);
            // held this frame: the advice skips the body (true) and its exit sees the marker, so nothing goes stale
            st.planFrame = 60; st.held = true;
            f.fresh = 1;
            boolean skipped = WorldPatches.ChunkPrepared.enter(0, c, 0, null);
            assert skipped : "a held pair is not prepared";
            WorldPatches.ChunkPrepared.exit(0, c, skipped ? Boolean.TRUE : null);
            assert f.fresh == 1 : "not prepared: the stored masks are still current";
            // not held: the body runs, and the exit marks the chunk's levels stale for that player only
            st.held = false;
            skipped = WorldPatches.ChunkPrepared.enter(0, c, 0, null);
            assert !skipped;
            f.fresh = 0b11;
            WorldPatches.ChunkPrepared.exit(0, c, skipped ? Boolean.TRUE : null);
            assert f.fresh == 0b10 : "prepared: player 0 stale, player 1 untouched";
            // another frame: the old hold does not carry over
            st.held = true; f.fresh = 1;
            FrameClock.frameNo = 61;
            skipped = WorldPatches.ChunkPrepared.enter(0, c, 0, null);
            assert !skipped;
            WorldPatches.ChunkPrepared.exit(0, c, null);
            assert f.fresh == 0;
        } finally {
            Cutaways.fastTable.remove(d);
            WorldPatches.fastGuard.on = fastWas;
            ChunkPatches.holdGuard.on = holdWas;
            FrameClock.on = clock; FrameClock.frameNo = frameNo;
        }
    }

    // ---- Minor 8: the shims end to end ----

    /** TranslucentBake.layer / reasons through the holder entry on real objects. */
    static void translucentLayerShim() {
        IsoPlayer pl = fakePlayer(false);
        Object camWas = get(null, IsoCamera.class, "isoCameraGameCharacter");
        boolean glassWas = TranslucentBake.glassPerFrame, lightsWas = TranslucentBake.lightsPerFrame;
        try {
            set(null, IsoCamera.class, "isoCameraGameCharacter", pl);
            Settings.load(SelfTest.write("enabled=true\ntranslucentTilesInChunkTexture=true\nglassTilesPerFrame=true\ntranslucentLightsPerFrame=true\n"));
            TranslucentBake.init();
            assert ChunkPatches.translucentGuard.on;
            IsoGridSquare sq = new IsoGridSquare(null, null, 3, 3, 0);
            IsoObject plain = new IsoObject(), flat = new IsoObject(), glass = new IsoObject(), lamp = new IsoObject();
            IsoWindow window = new IsoWindow((IsoCell) null);
            IsoTree tree = new IsoTree();
            plain.sprite = sprite(2, "walls_01"); flat.sprite = sprite(0, "walls_01");
            glass.sprite = sprite(2, "fixtures_glass_01"); lamp.sprite = sprite(2, "lighting_indoor_01");
            window.sprite = sprite(2, "fixtures_windows_01"); tree.sprite = sprite(2, "vegetation_trees_01");
            for (IsoObject o : new IsoObject[] {plain, flat, glass, lamp, window, tree}) o.square = sq;
            // a translucent tile with no other reason to stay per frame is baked; a window, a tree and a non-translucent sprite are not
            assert ChunkPatches.onObjectLayer(plain, ObjectRenderLayer.Translucent) == ObjectRenderLayer.MinusFloor;
            assert ChunkPatches.onObjectLayer(plain, ObjectRenderLayer.TranslucentSE) == ObjectRenderLayer.MinusFloorSE;
            assert ChunkPatches.onObjectLayer(window, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent : "windows stay per frame";
            assert ChunkPatches.onObjectLayer(window, ObjectRenderLayer.TranslucentSE) == ObjectRenderLayer.TranslucentSE;
            assert ChunkPatches.onObjectLayer(tree, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent : "trees stay per frame";
            assert ChunkPatches.onObjectLayer(flat, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent : "no Translucent depth flag";
            assert ChunkPatches.onObjectLayer(plain, ObjectRenderLayer.MinusFloor) == ObjectRenderLayer.MinusFloor : "other layers pass through";
            assert (TranslucentBake.reasons(window, window.sprite) & TranslucentBake.WINDOW) != 0 && (TranslucentBake.reasons(tree, tree.sprite) & TranslucentBake.TREE) != 0;
            assert TranslucentBake.reasons(plain, plain.sprite) == TranslucentBake.TRANS;
            // glass and lit fixtures follow their keys
            assert (TranslucentBake.reasons(glass, glass.sprite) & TranslucentBake.GLASS) != 0 && (TranslucentBake.reasons(lamp, lamp.sprite) & TranslucentBake.LIGHT) != 0;
            assert ChunkPatches.onObjectLayer(glass, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent;
            assert ChunkPatches.onObjectLayer(lamp, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent;
            Settings.load(SelfTest.write("enabled=true\ntranslucentTilesInChunkTexture=true\nglassTilesPerFrame=false\ntranslucentLightsPerFrame=false\n"));
            TranslucentBake.init();
            assert ChunkPatches.onObjectLayer(glass, ObjectRenderLayer.Translucent) == ObjectRenderLayer.MinusFloor;
            assert ChunkPatches.onObjectLayer(lamp, ObjectRenderLayer.Translucent) == ObjectRenderLayer.MinusFloor;
            // a window stays per frame under the new keys too (the window rule is not one of the keys)
            assert ChunkPatches.onObjectLayer(window, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent;
            // a square on the camera's level above the object's own floor: the climbing and floor rules of minusFloorRest are read from the square
            plain.square = new IsoGridSquare(null, null, 3, 3, 1000);
            assert ChunkPatches.onObjectLayer(plain, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent : "z >= 1000 without alwaysDraw stays";
            // key off: whatever stock answered
            Settings.load(SelfTest.write("enabled=true\n"));
            TranslucentBake.init();
            assert ChunkPatches.onObjectLayer(plain, ObjectRenderLayer.Translucent) == ObjectRenderLayer.Translucent;
        } finally {
            set(null, IsoCamera.class, "isoCameraGameCharacter", camWas);
            Settings.load(SelfTest.write("enabled=true\n"));
            TranslucentBake.init();
            TranslucentBake.glassPerFrame = glassWas; TranslucentBake.lightsPerFrame = lightsWas;
        }
    }

    /** ChunkTrees.translucentTree answers as stock FBORenderCell.isTranslucentTree for the same camera, player and stencil. */
    static void translucentTreeMatchesStock() {
        IsoWorld world = IsoWorld.instance;
        IsoCell cellWas = world.currentCell;
        IsoPlayer[] playersWas = IsoPlayer.players.clone();
        IsoCamera.FrameState fs = IsoCamera.frameState;
        int pIdx = fs.playerIndex; float camX = fs.camCharacterX, camY = fs.camCharacterY, offX = fs.offX, offY = fs.offY;
        IsoGridSquare camSq = fs.camCharacterSquare;
        boolean drivingWas = ChunkTrees.stockDriving;
        try {
            IsoCell cell = (IsoCell) allocate(IsoCell.class);
            set(cell, IsoCell.class, "stencilAreas", new ArrayList<>());
            world.currentCell = cell;
            fs.playerIndex = 0; fs.offX = 10f; fs.offY = 20f;
            FBORenderCell stock = FBORenderCell.instance;
            IsoGridSquare someSquare = new IsoGridSquare(null, null, 1, 1, 0);
            int checked = 0, driveDiffers = 0;
            for (boolean inVehicle : new boolean[] {false, true}) {
                IsoPlayer.players[0] = fakePlayer(inVehicle);
                for (int treeX : new int[] {2, 5, 9}) {                                       // west of / on / south-east of camera x = 5
                    for (int treeY : new int[] {2, 5, 9}) {
                        for (boolean camSquare : new boolean[] {false, true}) {
                            for (boolean inStencil : new boolean[] {false, true}) {
                                fs.camCharacterX = 5.5f; fs.camCharacterY = 5.5f;
                                fs.camCharacterSquare = camSquare ? someSquare : null;
                                IsoTree tree = new IsoTree();
                                tree.square = new IsoGridSquare(null, null, treeX, treeY, 0);
                                tree.square.IsOnScreen();                                      // may refresh the cached screen position
                                List<IsoCell.StencilArea> areas = cell.getStencilAreas();
                                areas.clear();
                                if (inStencil) {
                                    int sx = (int) (tree.square.cachedScreenX - fs.offX), sy = (int) (tree.square.cachedScreenY - fs.offY);
                                    areas.add(new IsoCell.StencilArea(sx - 50, sy - 50, sx + 50, sy + 50, 0, 0, 100, 100, 0, 0));
                                }
                                ChunkTrees.stockDriving = true;                                // the game's own rule: driving cuts
                                boolean want = stock.isTranslucentTree(tree);
                                assert Boolean.valueOf(want).equals(ChunkTrees.translucentTree(tree))
                                    : "tree " + treeX + "," + treeY + " vehicle " + inVehicle + " camSquare " + camSquare + " stencil " + inStencil;
                                checked++;
                                ChunkTrees.stockDriving = false;                               // driveTreeCutaway=false: Build 42.20, driving does not cut
                                Boolean old = ChunkTrees.translucentTree(tree);
                                IsoPlayer.players[0] = fakePlayer(false);
                                assert Boolean.valueOf(stock.isTranslucentTree(tree)).equals(old) : "42.20 rule is stock without the vehicle";
                                IsoPlayer.players[0] = fakePlayer(inVehicle);
                                if (old != null && old != want) driveDiffers++;
                            }
                        }
                    }
                }
            }
            assert checked == 72 && driveDiffers > 0 : "the driving rule changes some answers (" + driveDiffers + ")";
            // not a tree: false; a tree with no square: stock throws, the shim answers null
            assert ChunkTrees.translucentTree(new IsoObject()) == Boolean.FALSE && !stock.isTranslucentTree(new IsoObject());
            IsoTree bare = new IsoTree();
            boolean threw = false;
            try { stock.isTranslucentTree(bare); } catch (NullPointerException e) { threw = true; }
            assert threw && ChunkTrees.translucentTree(bare) == null;
        } finally {
            ChunkTrees.stockDriving = drivingWas;
            world.currentCell = cellWas;
            System.arraycopy(playersWas, 0, IsoPlayer.players, 0, playersWas.length);
            fs.playerIndex = pIdx; fs.camCharacterX = camX; fs.camCharacterY = camY; fs.offX = offX; fs.offY = offY; fs.camCharacterSquare = camSq;
        }
    }

    /** ZoomEase.scrollExit / updateExit on a real MultiTextureFBO2: a wheel step starts an ease, the update follows it to the target. */
    static void zoomEaseHooks() {
        IsoPlayer[] playersWas = IsoPlayer.players.clone();
        try {
            Settings.load(SelfTest.write("enabled=true\nzoomEaseMs=2000\n"));
            ZoomEase.init();
            assert ChunkPatches.zoomGuard.on : "handles must resolve headless";
            MultiTextureFBO2 fbo = new MultiTextureFBO2();
            fbo.setZoomLevels(0.5, 1.0, 2.0);
            fbo.setZoomAndTargetZoom(0, 1f);
            // a wheel step: stock writes the target; the exit starts an ease from the zoom shown
            Object before = ZoomEase.scrollEnter(fbo, 0);
            fbo.setTargetZoom(0, 2f);
            ZoomEase.scrollExit(fbo, 0, before);
            ZoomEase.updateExit(fbo);                                                       // stock has stepped zoom toward the target; the ease overwrites
            float z = fbo.getZoom(0);
            assert z >= 1f && z < 2f : "the ease has begun: " + z;
            // another writer moves the target: the ease ends and the zoom is left to stock
            fbo.setTargetZoom(0, 0.5f);
            fbo.setZoom(0, 1.25f);
            ZoomEase.updateExit(fbo);
            assert fbo.getZoom(0) == 1.25f : "cancelled ease writes nothing";
            // no wheel change (target unchanged): no ease
            before = ZoomEase.scrollEnter(fbo, 0);
            ZoomEase.scrollExit(fbo, 0, before);
            fbo.setZoom(0, 1.25f);
            ZoomEase.updateExit(fbo);
            assert fbo.getZoom(0) == 1.25f;
            // a 1 ms ease is over after a short sleep: the update lands exactly on the target and the ease is done
            Settings.load(SelfTest.write("enabled=true\nzoomEaseMs=1\n"));
            ZoomEase.init();
            fbo.setZoomAndTargetZoom(0, 1f);
            before = ZoomEase.scrollEnter(fbo, 0);
            fbo.setTargetZoom(0, 2f);
            ZoomEase.scrollExit(fbo, 0, before);
            SelfTest.sleep(5);
            ZoomEase.updateExit(fbo);
            assert fbo.getZoom(0) == 2f;
            fbo.setZoom(0, 1.5f);
            ZoomEase.updateExit(fbo);
            assert fbo.getZoom(0) == 1.5f : "finished ease writes nothing";
            // a bad handle or player: nothing happens, nothing throws
            ZoomEase.scrollExit(fbo, 0, null);
            ZoomEase.scrollExit(fbo, 0, "not an array");
        } finally {
            System.arraycopy(playersWas, 0, IsoPlayer.players, 0, playersWas.length);
            Settings.load(SelfTest.write("enabled=true\n"));
            ZoomEase.init();
        }
    }
}
