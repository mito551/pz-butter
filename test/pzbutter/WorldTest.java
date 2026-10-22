package pzbutter;

/** Plan 5a world checks (Task 1: occlusionSkipLightingOnly, lightInfoOncePerFrame, lightSwitchCheckFrames, lightingFps). Run from SelfTest.main. */
final class WorldTest {
    private WorldTest() {}

    static void run() {
        occlusionIgnoresLightingOnlyDirt();
        stampOnlyNewCounter();
        switchMemoExpires();
        plan5aKeysOffByDefault();
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
}
