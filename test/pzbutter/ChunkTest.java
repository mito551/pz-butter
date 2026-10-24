package pzbutter;

/** Plan 6a chunk-texture checks (Task 1: tree keys; Task 3: small keys). Run from SelfTest.main. */
final class ChunkTest {
    private ChunkTest() {}

    static void run() {
        treePerFrameRules();
        stencilTestMatchesStock();
        drivingRule();
        plan6aKeysDefaults();
        settingsTemplateHasPlan6Keys();
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
}
