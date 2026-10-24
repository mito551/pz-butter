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
}
