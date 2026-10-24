package pzbutter;

/** Plan 8 checks (zombies and entities; Task 1: keys and template, Task 2: zero-length facing). Run from SelfTest.main. Later tasks append their calls to run(). */
final class ZombieTest {
    private ZombieTest() {}

    static void run() throws Exception {
        plan8KeysDefaults();
        settingsTemplateHasPlan8Keys();
        facingPredicateIsStock();
        facingSkipsOnlyWhatStockThrows();
        facingOtherThreadPassesThrough();
        facingHolderSelectsFloatOverload();
        cullCapCountsGrantsInOrder();
        cullCapNeverBelowBlend();
        modelBudgetDefersFirstTimeAdds();
        blendCountWritten();
        blendZeroWritesNothing();
        lodKeysParse();
        cullNestedAndOutsidePassThrough();
        cullHoldersTargetStock();
        cullShimsOpenAndCloseOnGameThread();
        cullOtherThreadPassesThrough();
        cullCulledCallsPassAndBalance();
    }

    static final String[] PLAN8_FLAGS = {"zeroFacingFix", "playerLosFast", "zombieCullSortFast", "statsNoBox", "popmanFlagsFlyweight", "entityTiming"};

    static void plan8KeysDefaults() {
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        for (String k : PLAN8_FLAGS) assert !Settings.flag(k) : k;
        assert Settings.num("numberZombiesBlended", -1, -1, 99) == 0 && Settings.num("zombieLodFixed3d", -1, -1, 999) == 0;
        assert Settings.num("zombieModelAddBudgetUs", -1, -1, 99999) == 0 && Settings.num("zombieSimLodTiles", -1, -1, 999) == 0;
        assert Settings.num("zombieSimLodSteps", -1, -1, 9) == 1;
    }

    static void settingsTemplateHasPlan8Keys() {
        String t = Settings.render(new java.util.Properties());
        for (String k : new String[] {"zeroFacingFix=false", "numberZombiesBlended=0", "zombieLodFixed3d=0", "zombieModelAddBudgetUs=0",
                "zombieSimLodTiles=0", "zombieSimLodSteps=1", "playerLosFast=false", "zombieCullSortFast=false", "statsNoBox=false",
                "popmanFlagsFlyweight=false", "entityTiming=false"})
            assert t.contains("\n" + k + "\n") : k;
        assert t.indexOf("\nbloodRebakeCoalesceMs=250\n") < t.indexOf("\nzeroFacingFix=false\n") && t.indexOf("\nentityTiming=false\n") < t.indexOf("\ninputLatch=true\n");
        for (String k : new String[] {"zombieLodFixed3d", "zombieModelAddBudgetUs", "zombieSimLodTiles"})
            assert t.contains("# " + k + ": changes the simulation") : k;
    }

    // ---- Task 2: zero-length facing ----

    /** The game's own throw condition: setForwardDirection runs Vector2.normalize, then PZMath.equal(len, 0f). */
    static boolean stockThrows(float x, float y) {
        zombie.iso.Vector2 v = new zombie.iso.Vector2(x, y);
        return zombie.core.math.PZMath.equal(v.normalize(), 0f);
    }

    static void facingPredicateIsStock() {
        float[] v = {0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, 1e-40f, 1e-30f, 1e-23f, 7e-8f, 9.99e-8f, 1e-7f, 1.01e-7f, 1e-6f, 1e-3f,
            0.5f, 0.99999f, 1f, -1f, 1.00001f, 3f, 1e19f, 1e20f, Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float x : v) for (float y : v) assert FacingFix.wouldThrow(x, y) == stockThrows(x, y) : x + "," + y;
        java.util.Random r = new java.util.Random(8);
        for (int i = 0; i < 500_000; i++) {
            float x = i % 2 == 0 ? Float.intBitsToFloat(r.nextInt()) : (float) (r.nextGaussian() * Math.pow(10, -r.nextInt(12)));
            float y = i % 3 == 0 ? 0f : (float) (r.nextGaussian() * Math.pow(10, -r.nextInt(12)));
            assert FacingFix.wouldThrow(x, y) == stockThrows(x, y) : x + "," + y;
        }
    }

    static void facingSkipsOnlyWhatStockThrows() {
        Thread saved = FrameClock.mainThread;
        FrameClock.mainThread = Thread.currentThread();   // this test runs on the game thread's stand-in
        try {
            FacingFix.resetForTest(); FacingFix.guard.on = true;
            assert ZombiePatches.onForward(0f, 0f) && ZombiePatches.onForward(1e-30f, 0f) && FacingFix.kept == 2;
            assert !ZombiePatches.onForward(1e-6f, 0f) && !ZombiePatches.onForward(0f, 1f) && !ZombiePatches.onForward(Float.NaN, 0f) && FacingFix.kept == 2;
            FacingFix.guard.on = false;
            assert !ZombiePatches.onForward(0f, 0f) && FacingFix.kept == 2;
            FacingFix.resetForTest();
        } finally { FrameClock.mainThread = saved; }
    }

    // another thread: the zero-length call runs stock (no skip) and nothing is counted; the join is bounded
    static void facingOtherThreadPassesThrough() throws InterruptedException {
        Thread saved = FrameClock.mainThread;
        FrameClock.mainThread = Thread.currentThread();
        try {
            FacingFix.resetForTest(); FacingFix.guard.on = true;
            boolean[] skipped = {true};
            Thread t = new Thread(() -> skipped[0] = ZombiePatches.onForward(0f, 0f));
            t.start(); t.join(5000);
            assert !t.isAlive() && !skipped[0] && FacingFix.kept == 0;
            FacingFix.resetForTest();
        } finally { FrameClock.mainThread = saved; }
    }

    static void facingHolderSelectsFloatOverload() throws Exception {
        java.lang.reflect.Method m = ZombiePatches.ForwardDirection.class.getMethod("enter", float.class, float.class);
        me.zed_0xff.zombie_buddy.Patch p = ZombiePatches.ForwardDirection.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
        assert p.className().equals("zombie.characters.IsoGameCharacter") && p.methodName().equals("setForwardDirection");
        assert m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnEnter.class).skipOn();
    }

    // ---- Task 3: blend count, 3D cap and model budget ----

    static void cullCapCountsGrantsInOrder() {
        ZombieLod.resetForTest(); ZombieLod.capGuard.on = true; ZombieLod.cap = 50;
        ZombieLod.beginCull(20);
        for (int i = 0; i < 50; i++) assert !ZombieLod.decide(false, true) : i;
        assert ZombieLod.decide(false, true) && ZombieLod.decide(false, false);   // the 51st and 52nd grants become sprites
        assert !ZombieLod.decide(true, true) && ZombieLod.grants == 52;           // stock's own culled calls pass, uncounted
        assert ZombieLod.capped == 2 && ZombieLod.deferred == 0;
        ZombieLod.endCull();
        ZombieLod.beginCull(20);
        assert !ZombieLod.decide(false, true) && ZombieLod.grants == 1;            // a new cull counts from 0
        ZombieLod.endCull(); ZombieLod.resetForTest();
    }

    static void cullCapNeverBelowBlend() {
        ZombieLod.resetForTest(); ZombieLod.capGuard.on = true; ZombieLod.cap = 50;
        ZombieLod.beginCull(64);
        for (int i = 0; i < 64; i++) assert !ZombieLod.decide(false, true) : i;
        assert ZombieLod.decide(false, true);
        assert ZombieLod.effectiveCap(0, 64) == 0 && ZombieLod.effectiveCap(300, 20) == 300;
        ZombieLod.endCull(); ZombieLod.resetForTest();
    }

    static void modelBudgetDefersFirstTimeAdds() {
        ZombieLod.resetForTest(); ZombieLod.budgetGuard.on = true; ZombieLod.budgetNs = 1_000_000L;
        ZombieLod.beginCull(20);
        assert !ZombieLod.decide(false, false); ZombieLod.granted(false, 2_000_000L);   // the first add of a cull always passes
        assert ZombieLod.decide(false, false);                                          // budget spent: the next first-time add waits
        assert !ZombieLod.decide(false, true);                                          // a zombie that has its model keeps it
        ZombieLod.endCull();
        ZombieLod.beginCull(20);
        assert !ZombieLod.decide(false, false); ZombieLod.granted(false, 300_000L);
        assert !ZombieLod.decide(false, false); ZombieLod.granted(false, 300_000L);
        assert !ZombieLod.decide(false, false); ZombieLod.granted(false, 500_000L);     // 0.6 ms < 1 ms: passes; now 1.1 ms
        assert ZombieLod.decide(false, false) && ZombieLod.deferred == 2;
        ZombieLod.endCull(); ZombieLod.resetForTest();
    }

    static void blendCountWritten() {
        ZombieLod.resetForTest(); ZombieLod.blendGuard.on = true; ZombieLod.blended = 10;
        ZombieLod.enterCull();
        assert zombie.core.PerformanceSettings.numberZombiesBlended == 10 && !ZombieLod.inCull;   // blend alone does not open a cull
        ZombieLod.exitCull();
        zombie.core.PerformanceSettings.numberZombiesBlended = 20; ZombieLod.resetForTest();
    }

    static void blendZeroWritesNothing() {
        ZombieLod.resetForTest();
        zombie.core.PerformanceSettings.numberZombiesBlended = 20;
        ZombieLod.enterCull(); ZombieLod.exitCull();
        assert zombie.core.PerformanceSettings.numberZombiesBlended == 20 && !ZombieLod.inCull;
    }

    static void lodKeysParse() {
        Settings.load(SelfTest.write("enabled=true\nzombieLodFixed3d=10\nnumberZombiesBlended=99\nzombieModelAddBudgetUs=500\n"));
        ZombieLod.init();
        assert ZombieLod.cap == 50 && ZombieLod.blended == 64 && ZombieLod.budgetNs == 500_000L;
        assert ZombieLod.capGuard.on && ZombieLod.blendGuard.on && ZombieLod.budgetGuard.on;
        Settings.load(SelfTest.write("enabled=false\nzombieLodFixed3d=300\n"));
        ZombieLod.init();
        assert ZombieLod.cap == 0 && !ZombieLod.capGuard.on;
        ZombieLod.resetForTest();
    }

    // ---- Task 4: cull hooks ----

    static void cullNestedAndOutsidePassThrough() {
        ZombieLod.resetForTest();
        assert !ZombiePatches.onCulledEnter(null, false) && ZombiePatches.onCulledEnter(null, true);   // no cull open: arguments untouched, self never read
        ZombiePatches.onCulledExit(null);
        ZombieLod.capGuard.on = true; ZombieLod.cap = 50; ZombieLod.beginCull(20);
        ZombieLod.depth = 1;                                                                          // a call made from inside a setSceneCulled
        assert !ZombiePatches.onCulledEnter(null, false) && ZombieLod.grants == 0 && ZombieLod.depth == 2;
        ZombiePatches.onCulledExit(null);
        assert ZombieLod.depth == 1;
        ZombieLod.endCull(); ZombieLod.resetForTest();
    }

    static void cullHoldersTargetStock() {
        java.util.Map<String, String> want = java.util.Map.of("SceneCull", "zombie.iso.IsoWorld.sceneCullZombies",
            "SceneCulled", "zombie.characters.IsoGameCharacter.setSceneCulled");
        for (java.util.Map.Entry<String, String> e : want.entrySet()) {
            Class<?> c = null;
            for (Class<?> x : ZombiePatches.class.getDeclaredClasses()) if (x.getSimpleName().equals(e.getKey())) c = x;
            assert c != null : e.getKey();
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            assert (p.className() + "." + p.methodName()).equals(e.getValue());
            boolean exitOk = false;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                me.zed_0xff.zombie_buddy.Patch.OnExit x = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
                if (x != null) exitOk = x.onThrowable() == Throwable.class && x.suppress() == Throwable.class;
            }
            assert exitOk : e.getKey();
        }
    }

    // mine: the entry shim opens a cull only on the game thread with a key on, the exit shim closes it whatever the keys say by then
    static void cullShimsOpenAndCloseOnGameThread() {
        Thread saved = FrameClock.mainThread;
        FrameClock.mainThread = Thread.currentThread();
        try {
            ZombieLod.resetForTest();
            ZombiePatches.onCullEnter();
            assert !ZombieLod.inCull;                                                  // every key off: nothing opened
            ZombieLod.capGuard.on = true; ZombieLod.cap = 50;
            FrameClock.mainThread = null;
            ZombiePatches.onCullEnter();
            assert !ZombieLod.inCull;                                                  // not the game thread
            FrameClock.mainThread = Thread.currentThread();
            ZombiePatches.onCullEnter();
            assert ZombieLod.inCull && ZombieLod.cullThread == Thread.currentThread() && ZombieLod.effCap == 50;
            ZombieLod.capGuard.on = false;                                             // switched off mid-cull: the cull is still closed
            ZombiePatches.onCullExit();
            assert !ZombieLod.inCull;
            ZombiePatches.onCullExit();                                                // no cull open: nothing happens
            ZombieLod.resetForTest();
        } finally { FrameClock.mainThread = saved; ZombieLod.resetForTest(); }
    }

    // mine: a setSceneCulled call from another thread during a cull keeps its argument and leaves the counters alone
    static void cullOtherThreadPassesThrough() throws InterruptedException {
        ZombieLod.resetForTest();
        ZombieLod.capGuard.on = true; ZombieLod.cap = 50; ZombieLod.beginCull(20);
        boolean[] r = {false, true};
        Thread t = new Thread(() -> {
            r[0] = ZombiePatches.onCulledEnter(null, false);
            r[1] = ZombiePatches.onCulledEnter(null, true);
            ZombiePatches.onCulledExit(null);
            ZombiePatches.onCullExit();                                                // does not close the main thread's cull
        });
        t.start(); t.join(5000);
        assert !t.isAlive() && !r[0] && r[1] && ZombieLod.grants == 0 && ZombieLod.depth == 0 && ZombieLod.inCull;
        ZombieLod.endCull(); ZombieLod.resetForTest();
    }

    // mine: stock's own culled calls pass, count a depth and balance at the exit; self is never read for them
    static void cullCulledCallsPassAndBalance() {
        ZombieLod.resetForTest();
        ZombieLod.capGuard.on = true; ZombieLod.cap = 50; ZombieLod.beginCull(20);
        for (int i = 0; i < 100; i++) {
            assert ZombiePatches.onCulledEnter(null, true) && ZombieLod.depth == 1 : i;
            ZombiePatches.onCulledExit(null);
            assert ZombieLod.depth == 0 : i;
        }
        assert ZombieLod.grants == 0 && !ZombieLod.inflightRewrite && ZombieLod.inflightT0 == 0;
        ZombieLod.endCull(); ZombieLod.resetForTest();
    }
}
