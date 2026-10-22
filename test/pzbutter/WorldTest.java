package pzbutter;

/** Plan 5a world checks (Task 1: occlusionSkipLightingOnly, lightInfoOncePerFrame, lightSwitchCheckFrames, lightingFps). Run from SelfTest.main. */
final class WorldTest {
    private WorldTest() {}

    static void run() {
        occlusionIgnoresLightingOnlyDirt();
        stampOnlyNewCounter();
        fpsAppliedOnlyAboveZero();
        switchMemoExpires();
        plan5aKeysOffByDefault();
        gridGateRunsOnDirtyOrMove();
        debounceFlipsAfterN();
        debounceIgnoresShortFlicker();
        debounceSparseScansUseFrames();
    }

    // dirt that is only lighting (32) does not count; any other flag on an on-screen level does
    static void occlusionIgnoresLightingOnlyDirt() {
        assert !LightChecks.anyDirtyIgnoringLighting(new long[] {32}, new boolean[] {true}, new boolean[] {true});
        assert LightChecks.anyDirtyIgnoringLighting(new long[] {32 | 2048}, new boolean[] {true}, new boolean[] {true});
        assert !LightChecks.anyDirtyIgnoringLighting(new long[] {2048}, new boolean[] {true}, new boolean[] {false}); // off screen
        assert !LightChecks.anyDirtyIgnoringLighting(new long[] {2048}, new boolean[] {false}, new boolean[] {true}); // clean
    }

    // a square is stamped once per lighting counter: a new counter stamps, the same counter and no counter do not
    static void stampOnlyNewCounter() {
        assert LightChecks.stampClean(5, 6) && !LightChecks.stampClean(6, 6) && !LightChecks.stampClean(5, -1);
        assert !LightChecks.stampClean(-1, 6);   // never had lighting data: stock invalidates the level first, so no stamp
    }

    // lightingFps is applied at every world start when above 0, never when 0
    static void fpsAppliedOnlyAboveZero() {
        assert LightChecks.applyFps(60) && LightChecks.applyFps(1) && !LightChecks.applyFps(0);
    }

    // an answer is reused for fewer than ttl frames, then asked again; an unknown switch always misses
    static void switchMemoExpires() {
        LightChecks.resetForTest();
        Object sw = new Object();
        assert LightChecks.switchMemo(sw, 100, 15) == null;
        LightChecks.switchStore(sw, 100, true);
        assert LightChecks.switchMemo(sw, 114, 15) == Boolean.TRUE;
        assert LightChecks.switchMemo(sw, 115, 15) == null;     // 15 frames old: ask again
        assert LightChecks.switchMemo(new Object(), 101, 15) == null;
    }

    static void plan5aKeysOffByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        for (String k : new String[] {"occlusionSkipLightingOnly", "lightInfoOncePerFrame", "cutawayInvalidateChanged", "cutawayVisitPrefilter",
                                      "cutawayFast", "rainSplashesFast", "weatherMaskIdleSkip", "puddleEarlyZ", "fogEarlyZ"})
            assert !Settings.flag(k) : k;
        for (String k : new String[] {"lightSwitchCheckFrames", "lightingFps", "gridStackInterval", "roofHideDebounceFrames", "cutawayRadius"})
            assert Settings.num(k, 0, 0, 1000) == 0 : k;
    }

    // the buildings-in-front scan runs on the first frame, after interval frames, or when the camera square, facing or dirty flag changes
    static void gridGateRunsOnDirtyOrMove() {
        GridGate.resetForTest();
        assert GridGate.shouldRun(0, 1, 10, 10, 0, 2, false, 8);       // first frame
        assert !GridGate.shouldRun(0, 2, 10, 10, 0, 2, false, 8);
        assert GridGate.shouldRun(0, 3, 11, 10, 0, 2, false, 8);       // camera square moved
        assert GridGate.shouldRun(0, 4, 11, 10, 0, 2, true, 8);        // dirtyRecalcGridStack
        assert GridGate.shouldRun(0, 5, 11, 10, 0, 3, false, 8);       // facing changed
        assert !GridGate.shouldRun(0, 12, 11, 10, 0, 3, false, 8);
        assert GridGate.shouldRun(0, 13, 11, 10, 0, 3, false, 8);      // 8 frames since the last run
        assert GridGate.shouldRun(1, 13, 11, 10, 0, 3, false, 8);      // other player: own state
    }

    // the roof flag flips once a reading has differed from the held value for n frames (time counts frames, not calls)
    static void debounceFlipsAfterN() {
        GridGate.resetForTest();
        assert !GridGate.debounce(false, 1, 3);
        assert !GridGate.debounce(true, 2, 3);       // first differing reading at frame 2
        assert !GridGate.debounce(true, 4, 3);       // 2 frames: not yet
        assert GridGate.debounce(true, 5, 3);        // 3 frames since it first differed: flips
        assert GridGate.debounce(true, 6, 3);
    }

    // a reading that agrees with the held value clears the pending difference, so a short flicker never flips the flag
    static void debounceIgnoresShortFlicker() {
        GridGate.resetForTest();
        GridGate.debounce(false, 1, 3);
        GridGate.debounce(true, 2, 3);
        assert !GridGate.debounce(false, 3, 3);      // agreement clears it
        assert !GridGate.debounce(true, 4, 3);       // starts again from frame 4
        assert !GridGate.debounce(true, 6, 3);
        assert GridGate.debounce(true, 7, 3);
    }

    // skipped frames make no call, so one real scan after 8 skipped frames is a first difference, not 8 readings
    static void debounceSparseScansUseFrames() {
        GridGate.resetForTest();
        GridGate.debounce(false, 0, 8);
        assert !GridGate.debounce(true, 8, 8);       // one real scan after 8 skipped frames: first differ, no flip
        assert GridGate.debounce(true, 16, 8);       // still differs 8 frames later: flips
    }
}
