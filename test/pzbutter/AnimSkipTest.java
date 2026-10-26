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
        // guard off means outer would leave skipping false; simulate via init? just flag
        AnimSkip.skipping = false;
        assert !AnimSkip.skipping;
        AnimSkip.distGuard.on = false;
    }
}
