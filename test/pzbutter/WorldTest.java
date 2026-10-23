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
        changedChunksOnlyWhereFlagsDiffer();
        prefilterKeepsPossibleCuts();
        radiusInChunks();
        cutawayHandlesResolve();
        restoreOnFailureKeepsSets();
        lossyMatchesStockIntShift();
        replayMatchesHandWrittenStock();
        replayBoxDecidesSquares();
        replayNeedsFreshSameBox();
        replayNeedsStoredMatchingTruth();
        splashIdleRateMatches();
        splashEveryIdleWhenNAtMostOne();
        splashTriggerSetsFlagBit();
        splashOverloadsAgree();
        skipCountClamps();
        splashActiveAdvancesAndResets();
        splashResetOnStop();
        maskIdleOnlyWithoutWeather();
    }

    // stock's int-shifted store: square i sets bit (i & 31) of an int, which sign-extends into the long
    static long stockStored(int[] squares) {
        long acc = 0;
        for (int i : squares) acc |= (long) (1 << (i % 8 + (i / 8) * 8));
        return acc;
    }

    // lossy(true mask) is the stored field stock writes: rows 4-7 alias rows 0-3, and bit 31 spreads into 31..63
    static void lossyMatchesStockIntShift() {
        assert Cutaways.lossy(1L << 0) == 1L;
        assert Cutaways.lossy(1L << 32) == 1L;                       // square (0,4) aliases (0,0)
        assert Cutaways.lossy(1L << 31) == 0xFFFFFFFF80000000L;      // square (7,3)
        assert Cutaways.lossy(1L << 63) == 0xFFFFFFFF80000000L;      // square (7,7)
        int[] sq = {0, 32, 31, 63};
        assert Cutaways.lossy((1L << 0) | (1L << 32) | (1L << 31) | (1L << 63)) == stockStored(sq);
        java.util.Random r = new java.util.Random(5);
        for (int n = 0; n < 2000; n++) {
            long t = r.nextLong();
            assert Cutaways.lossy(t) == stockStored(java.util.stream.IntStream.range(0, 64).filter(i -> (t & (1L << i)) != 0).toArray());
        }
    }

    // a hand-written copy of stock's loop over the squares that pass its tests writes the same cells as the replay of their mask
    static void replayMatchesHandWrittenStock() {
        int wx = 2, wy = 3, z = 1, gx1 = 0, gy1 = 0, gx2 = 39, gy2 = 39, w = gx2 - gx1 + 1;
        int[] pass = {0, 9, 32, 45, 31, 63, 39};                    // includes rows 4-7 and square 63
        int[] stock = new int[w * 40]; java.util.Arrays.fill(stock, -32);
        long truth = 0;
        for (int i : pass) {
            int dx = wx * 8 + i % 8 - 3 * z - gx1, dy = wy * 8 + i / 8 - 3 * z - gy1;
            if (dx < 0 || dy < 0 || dx > gx2 - gx1 || dy > gy2 - gy1) continue;
            stock[dx + dy * w] = Math.max(stock[dx + dy * w], z);
            truth |= 1L << i;
        }
        int[] got = new int[w * 40]; java.util.Arrays.fill(got, -32);
        Cutaways.replay(truth, wx, wy, z, gx1, gy1, gx2, gy2, w, got);
        assert java.util.Arrays.equals(stock, got);
        int set = 0; for (int v : got) if (v != -32) set++;
        assert set == pass.length;
        Cutaways.replay(1L, 2, 3, 0, 0, 0, 39, 39, w, got);  // a lower level never lowers a cell
        assert got[(16) + (24) * w] == 0 && got[(16 - 3) + (24 - 3) * w] == 1;
    }

    // the stored field replays only when it is the lossy form of the true mask (removeFromWorld zeroes it)
    static void replayNeedsStoredMatchingTruth() {
        Cutaways.Fast f = new Cutaways.Fast();
        Cutaways.beginCompute(f, 1, 0, 0, 39, 39);
        Cutaways.endCompute(f, 1);
        f.truth[1] = (1L << 63) | (1L << 5);
        assert Cutaways.replayable(f, 1, 0, 0, 39, 39, Cutaways.lossy(f.truth[1]));
        assert !Cutaways.replayable(f, 1, 0, 0, 39, 39, 0L);         // removeFromWorld zeroed the field
        assert !Cutaways.replayable(f, 1, 0, 0, 39, 39, 1L << 5);    // the unaliased bits only
    }

    // a square outside the box (stock's check on x - 3z - gx1 and y - 3z - gy1) writes nothing
    static void replayBoxDecidesSquares() {
        int w = 22; int[] grid = new int[w * 22]; java.util.Arrays.fill(grid, -32);
        Cutaways.replay(1L, 2, 3, 1, 17, 0, 38, 21, w, grid);   // square (16,24) at z=1 maps to x-3-17 = -4: outside
        int set = 0; for (int v : grid) if (v != -32) set++;
        assert set == 0;
    }

    // a stored mask replays only while its player's bit is fresh and only for the box it was computed with
    static void replayNeedsFreshSameBox() {
        Cutaways.Fast f = new Cutaways.Fast();
        assert !Cutaways.canReplay(f, 1, 0, 0, 39, 39);       // never computed
        Cutaways.beginCompute(f, 1, 0, 0, 39, 39);
        assert !Cutaways.canReplay(f, 1, 0, 0, 39, 39);       // still computing
        Cutaways.endCompute(f, 1);
        assert Cutaways.canReplay(f, 1, 0, 0, 39, 39);
        assert !Cutaways.canReplay(f, 1, 1, 0, 39, 39);       // other box
        assert !Cutaways.canReplay(f, 0, 0, 0, 39, 39);       // other player
        Cutaways.markStale(f, 1);
        assert !Cutaways.canReplay(f, 1, 0, 0, 39, 39);
    }

    // squares in chunks 0,0,1,2: chunk 0 same flags, chunk 1 north->west, chunk 2 new cut square
    static void changedChunksOnlyWhereFlagsDiffer() {
        boolean[] c = Cutaways.changedChunks(new int[] {0, 0, 1, 2}, new int[] {1, 2, 1, 0}, new int[] {1, 2, 2, 1}, 3);
        assert !c[0] && c[1] && c[2];
    }

    // a wall is visited unless it cannot cut: outdoors with nothing to collapse, in a room with no occluding room and no peeking
    static void prefilterKeepsPossibleCuts() {
        assert !Cutaways.mayCut(true, false, false, false);
        assert Cutaways.mayCut(true, true, false, false);
        assert !Cutaways.mayCut(false, false, false, false);
        assert Cutaways.mayCut(false, false, true, false);
        assert Cutaways.mayCut(false, false, false, true);
    }

    static void radiusInChunks() {
        assert Cutaways.inRadius(10, 10, 16, 10, 6) && !Cutaways.inRadius(10, 10, 17, 10, 6) && Cutaways.inRadius(10, 10, 99, 99, 0);
    }

    // the private helpers and fields the rewrite reaches exist in the game jar (needs the game classes on the classpath)
    static void cutawayHandlesResolve() {
        assert Cutaways.resolveHandles();
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

    // a throw in the visit's work restores the sets to their saved contents and the throw still reaches the caller
    static void restoreOnFailureKeepsSets() {
        java.util.HashSet<Object> north = new java.util.HashSet<>(java.util.List.of("a"));
        java.util.HashSet<Object> west = new java.util.HashSet<>(java.util.List.of("b"));
        java.util.ArrayList<Object> snapN = new java.util.ArrayList<>(north), snapW = new java.util.ArrayList<>(west);
        boolean threw = false;
        try {
            Cutaways.withRestore(() -> { north.clear(); west.clear(); throw new IllegalStateException("boom"); },
                                 () -> { Cutaways.restore(north, snapN); Cutaways.restore(west, snapW); });
        } catch (IllegalStateException e) {
            threw = true;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        assert threw;
        assert north.equals(java.util.Set.of("a")) && west.equals(java.util.Set.of("b"));
        // a clean run leaves the work's changes alone
        north.clear();
        try {
            Cutaways.withRestore(() -> north.add("c"), () -> { throw new AssertionError("restore must not run"); });
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
        assert north.equals(java.util.Set.of("c"));
    }

    // Task 5 (rainSplashesFast, weatherMaskIdleSkip)
    static void splashIdleRateMatches() {
        java.util.SplittableRandom r = new java.util.SplittableRandom(7);
        long triggered = 0, idle = 0;
        for (int f = 0; f < 20000; f++) {
            float[] frame = new float[64]; java.util.Arrays.fill(frame, -1f);
            byte[] flags = new byte[64];
            Weather.updateSplashes(frame, flags, 50, 0.08f, r::nextDouble);
            for (float v : frame) { idle++; if (v == 0f) triggered++; }
        }
        double rate = triggered / (double) idle;
        assert Math.abs(rate - 1.0 / 50) < 0.00025 : rate;
    }

    // n <= 1 (and n = 0 or negative): every idle square triggers, and the generator is never called
    static void splashEveryIdleWhenNAtMostOne() {
        for (int n : new int[] {1, 0, -5}) {
            float[] frame = new float[64]; java.util.Arrays.fill(frame, -1f); frame[9] = 0.3f;
            byte[] flags = new byte[64];
            int[] calls = {0};
            Weather.updateSplashes(frame, flags, n, 0.08f, () -> { calls[0]++; return 0.5; });
            assert calls[0] == 0 : n;
            for (int k = 0; k < 64; k++) {
                if (k == 9) assert Math.abs(frame[k] - 0.38f) < 1e-6 : n;   // active square advances, not triggered
                else { assert frame[k] == 0f : n + " " + k; assert flags[k] == 2 : n + " " + k; }
            }
        }
    }

    // a trigger sets flags |= 2 and keeps the other bits; squares that do not trigger keep their flags
    static void splashTriggerSetsFlagBit() {
        float[] frame = new float[64]; java.util.Arrays.fill(frame, -1f); frame[5] = 0.1f;
        byte[] flags = new byte[64]; java.util.Arrays.fill(flags, (byte) 1);
        Weather.updateSplashes(frame, flags, 1, 0.08f, () -> 0.5);
        assert flags[0] == 3 && flags[63] == 3;          // triggered: bit 1 added, bit 0 kept
        assert flags[5] == 1;                             // active: flags untouched
        float[] none = new float[64]; java.util.Arrays.fill(none, 0.2f);
        byte[] f2 = new byte[64]; java.util.Arrays.fill(f2, (byte) 1);
        Weather.updateSplashes(none, f2, 1, 0.08f, () -> 0.5);
        for (byte b : f2) assert b == 1;                  // no idle square: nothing set
    }

    // the SplittableRandom overload draws in the same order as the DoubleSupplier overload
    static void splashOverloadsAgree() {
        float[] a = new float[64], b = new float[64];
        java.util.Arrays.fill(a, -1f); java.util.Arrays.fill(b, -1f);
        byte[] fa = new byte[64], fb = new byte[64];
        java.util.SplittableRandom r1 = new java.util.SplittableRandom(3), r2 = new java.util.SplittableRandom(3);
        java.util.function.DoubleSupplier ds = r2::nextDouble;
        for (int f = 0; f < 50; f++) {
            Weather.updateSplashes(a, fa, 7, 0.08f, r1);
            Weather.updateSplashes(b, fb, 7, 0.08f, ds);
            assert java.util.Arrays.equals(a, b) && java.util.Arrays.equals(fa, fb) : f;
        }
    }

    // skipCount: 0 at u = 0, clamped to Integer.MAX_VALUE at u = 1 (never negative), finite for n = Integer.MAX_VALUE
    static void skipCountClamps() {
        double logQ = Math.log1p(-1.0 / 50);
        assert Weather.skipCount(1 / logQ, 0.0) == 0;
        assert Weather.skipCount(1 / logQ, 1.0) == Integer.MAX_VALUE;
        int near = Weather.skipCount(1 / logQ, 0.999999999999);
        assert near >= 0 && near < Integer.MAX_VALUE;
        double logHuge = Math.log1p(-1.0 / Integer.MAX_VALUE);
        assert Double.isFinite(logHuge) && logHuge < 0;
        int big = Weather.skipCount(1 / logHuge, 0.5);
        assert big > 0 && big < Integer.MAX_VALUE : big;
        assert Weather.skipCount(1 / logHuge, 0.999999) >= 0;
        assert Weather.skipCount(1 / logHuge, 1.0) == Integer.MAX_VALUE;
    }

    static void splashActiveAdvancesAndResets() {
        float[] frame = new float[64]; java.util.Arrays.fill(frame, -1f); frame[3] = 0.95f; frame[4] = 0.5f;
        Weather.updateSplashes(frame, new byte[64], Integer.MAX_VALUE, 0.08f, () -> 0.999);
        assert frame[3] == -1f && Math.abs(frame[4] - 0.58f) < 1e-6;
    }

    static void splashResetOnStop() {
        // covered by the hook: not raining → stock fill with -1 (asserted on the helper Weather.stopSplashes(float[]))
        float[] frame = {0.2f, -1f, 0.7f};
        Weather.stopSplashes(frame);
        assert frame[0] == -1f && frame[2] == -1f;
    }

    // precipitation draws only while its option is 2 or lower (IsoWeatherFX.render: option > 2 skips rain and snow)
    static void maskIdleOnlyWithoutWeather() {
        assert Weather.maskIdle(false, false, 1, false);
        assert Weather.maskIdle(false, false, 3, true);          // option 3: render() skips precipitation
        assert Weather.maskIdle(false, false, 9, true);
        assert !Weather.maskIdle(false, false, 2, true);         // option 2 still draws
        assert !Weather.maskIdle(false, false, 0, true);
        assert !Weather.maskIdle(true, false, 3, false);
        assert !Weather.maskIdle(false, true, 3, false);
    }
}
