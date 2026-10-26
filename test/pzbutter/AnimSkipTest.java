package pzbutter;

/** AnimSkip checks. */
final class AnimSkipTest {
    private AnimSkipTest() {}

    static void run() throws Exception {
        cadenceStaggered();
        cadenceSpreadsDistinctObjects();
        cadenceEveryOneNeverSkips();
        distantThreshold();
        eligibleFalseInputs();
        skipSetCoversMatrixCallsOnly();
        flagClearedOnThrow();
        offIsStock();
        offscreenOnlyWhenNotOnScreen();
        offscreenAndDistantCompose();
    }

    static void cadenceStaggered() {
        for (int every = 4, e = 0; e < 1; e++) {}
        int every = 4;
        // each id has exactly 4 non-skip frames out of 16
        for (int id = 0; id < 8; id++) {
            int rebuilds = 0;
            for (long f = 0; f < 16; f++) if (!AnimSkip.cadenceSkip(id, f, every)) rebuilds++;
            assert rebuilds == 4 : id + " " + rebuilds;
        }
        // ids 0..3 never share a rebuild frame
        for (long f = 0; f < 16; f++) {
            int sharing = 0;
            for (int id = 0; id < 4; id++) if (!AnimSkip.cadenceSkip(id, f, every)) sharing++;
            assert sharing <= 1 : "frame " + f + " rebuilds " + sharing;
        }
    }

    static void cadenceSpreadsDistinctObjects() {
        Object[] objs = new Object[8];
        for (int i = 0; i < objs.length; i++) objs[i] = new Object();
        for (long f = 0; f < 16; f++) {
            int rebuilds = 0;
            for (int i = 0; i < objs.length; i++) {
                int id = System.identityHashCode(objs[i]) & 0x7fffffff;
                if (!AnimSkip.cadenceSkip(id, f, 4)) rebuilds++;
            }
            assert rebuilds < objs.length : "all rebuild frame " + f;
        }
    }

    static void cadenceEveryOneNeverSkips() {
        for (int id = 0; id < 5; id++)
            for (long f = 0; f < 10; f++)
                assert !AnimSkip.cadenceSkip(id, f, 1);
    }

    static void distantThreshold() {
        assert !AnimSkip.distant(30, 40, 50) : "exactly 50";
        assert AnimSkip.distant(30, 41, 50) : "over 50";
    }

    static void eligibleFalseInputs() {
        assert AnimSkip.eligible(true, false, false, false);
        assert !AnimSkip.eligible(false, false, false, false);
        assert !AnimSkip.eligible(true, true, false, false);
        assert !AnimSkip.eligible(true, false, true, false);
        assert !AnimSkip.eligible(true, false, false, true);
    }

    static void skipSetCoversMatrixCallsOnly() throws Exception {
        java.util.Set<String> got = new java.util.HashSet<>();
        for (Class<?> c : ZombiePatches.class.getDeclaredClasses()) {
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            if (p == null) continue;
            if (!p.className().equals("zombie.core.skinnedmodel.animation.AnimationPlayer")) continue;
            if (p.methodName().equals("updateAnimation_StandardAnimation")) continue;
            got.add(p.methodName());
        }
        java.util.Set<String> want = java.util.Set.of(
            "updateMultiTrackBoneTransforms", "copyBoneTransformsFromParentPlayer", "updateTwistBone",
            "applyBoneReParenting", "updateModelTransforms", "UpdateSkinTransforms");
        assert got.equals(want) : got;
        assert !got.contains("DoAngles") && !got.contains("calculateDeferredMovement");
    }

    static void flagClearedOnThrow() throws Exception {
        Class<?> c = null;
        for (Class<?> x : ZombiePatches.class.getDeclaredClasses())
            if (x.getSimpleName().equals("AnimStandard")) c = x;
        assert c != null;
        boolean ok = false;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            me.zed_0xff.zombie_buddy.Patch.OnExit x = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
            if (x != null) ok = x.onThrowable() == Throwable.class;
        }
        assert ok;
    }

    static void offIsStock() {
        AnimSkip.distGuard.on = false;
        AnimSkip.skipping = true;
        ZombiePatches.onAnimExit();
        assert !AnimSkip.skipping;
        AnimSkip.distGuard.on = false;
    }

    static void offscreenOnlyWhenNotOnScreen() {
        // off-screen contributes only with one local player
        assert AnimSkip.every(false, true, false, false, 1, 4, 8) == 8;
        assert AnimSkip.every(false, true, false, false, 2, 4, 8) == 0;
        assert AnimSkip.every(false, true, false, true, 1, 4, 8) == 0;
    }

    static void offscreenAndDistantCompose() {
        // on screen -> only the distant cadence
        assert AnimSkip.every(true, false, true, true, 1, 4, 8) == 4;
        // both -> the larger
        assert AnimSkip.every(true, true, true, false, 1, 4, 8) == 8;
        // both guards off -> 0
        assert AnimSkip.every(false, false, false, false, 1, 4, 8) == 0;
    }
}
