package pzbutter;

/** Plan 8 checks (zombies and entities; Task 1: keys and template, Task 2: zero-length facing). Run from SelfTest.main. Later tasks append their calls to run(). */
final class ZombieTest {
    private ZombieTest() {}

    static void run() throws Exception {
        try { runAll(); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new Exception(t); }
    }

    static void runAll() throws Throwable {
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
        simLodSteps();
        simLodOffReturnsStock();
        simLodPassesThroughWhenItShould();
        simLodHolderTargetsStock();
        spottedStackMatchesStack();
        spottedAddKeepsSetFresh();
        spottedCopiesAndIsAStack();
        spottedViewWriteAfterRebuild();
        spottedCloneHasItsOwnSet();
        losShimOffTouchesNothing();
        losShimSwapsOnce();
        losHolderTargetsStock();
        cullSortOrderIsStock();
        cullSortCompareArithmetic();
        cullSortLeavesOtherSortsAlone();
        cullSortShimPicksStockOnly();
        cullSortInitFindsGameClass();
        cullSortHolderTargetsStock();
        statsGetMatchesStock();
        statsGetFallsBackToStock();
        statsShimOffTouchesNothing();
        flagsFlyweightSameFlags();
        smallHoldersTargetStock();
        entityTimingSumsOutermost();
        entityTimingMinuteLine();
        entityTimingIgnoresIdleFrames();
        entityTimingUnbalancedEndsIgnored();
        entityTimingMinuteRolls();
        entityTimingWritesEntityOut();
        entityTimingShimsCountPhases();
        timingHoldersTargetStock();
        frameHolderEndsEntityTiming();
        zombieShimsOffTouchNothing();
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

    // ---- Task 5: one more update tier ----

    static void simLodSteps() {
        zombie.UpdateSchedulerSimulationLevel FULL = zombie.UpdateSchedulerSimulationLevel.FULL, HALF = zombie.UpdateSchedulerSimulationLevel.HALF,
            QUARTER = zombie.UpdateSchedulerSimulationLevel.QUARTER, SIXTEENTH = zombie.UpdateSchedulerSimulationLevel.SIXTEENTH;
        assert ZombieLod.lower(FULL, 19.9f, 20, 1) == FULL && ZombieLod.lower(FULL, 20f, 20, 1) == FULL && ZombieLod.lower(FULL, 20.01f, 20, 1) == HALF;
        assert ZombieLod.lower(FULL, 39f, 20, 2) == HALF && ZombieLod.lower(FULL, 41f, 20, 2) == QUARTER;
        assert ZombieLod.lower(HALF, 100f, 20, 3) == SIXTEENTH && ZombieLod.lower(SIXTEENTH, 1000f, 20, 3) == SIXTEENTH;
        assert ZombieLod.lower(FULL, Float.NaN, 20, 3) == FULL;
    }

    static void simLodOffReturnsStock() {
        ZombieLod.resetForTest();
        zombie.UpdateSchedulerSimulationLevel r = zombie.UpdateSchedulerSimulationLevel.HALF;
        assert ZombiePatches.onSimLevel(null, null, r) == r;          // key off: nothing read
        Settings.load(SelfTest.write("enabled=true\nzombieSimLodTiles=20\nzombieSimLodSteps=7\n"));
        ZombieLod.init();
        assert ZombieLod.simGuard.on && ZombieLod.simTiles == 20 && ZombieLod.simSteps == 3;
        Settings.load(SelfTest.write("enabled=false\nzombieSimLodTiles=20\n"));
        ZombieLod.init();
        assert !ZombieLod.simGuard.on && ZombieLod.simTiles == 0;
        ZombieLod.resetForTest();
    }

    // mine: with the key on the shim still hands back the game's level off the game thread, for a null level and for the lowest level,
    // without touching the scheduler or the object (both null here)
    static void simLodPassesThroughWhenItShould() throws InterruptedException {
        Thread saved = FrameClock.mainThread;
        ZombieLod.resetForTest();
        ZombieLod.simGuard.on = true; ZombieLod.simTiles = 20; ZombieLod.simSteps = 3;
        try {
            zombie.UpdateSchedulerSimulationLevel half = zombie.UpdateSchedulerSimulationLevel.HALF;
            FrameClock.mainThread = Thread.currentThread();
            assert ZombiePatches.onSimLevel(null, null, null) == null;
            assert ZombiePatches.onSimLevel(null, null, zombie.UpdateSchedulerSimulationLevel.SIXTEENTH) == zombie.UpdateSchedulerSimulationLevel.SIXTEENTH;
            FrameClock.mainThread = null;
            assert ZombiePatches.onSimLevel(null, null, half) == half;                    // not the game thread: a null scheduler is never read
            FrameClock.mainThread = Thread.currentThread();
            zombie.UpdateSchedulerSimulationLevel[] got = {null};
            Thread t = new Thread(() -> got[0] = ZombiePatches.onSimLevel(null, null, half));
            t.start(); t.join(5000);
            assert !t.isAlive() && got[0] == half;
            // a failure on the game thread (null scheduler) hands back the game's level and counts against the guard
            assert ZombiePatches.onSimLevel(null, null, half) == half;
        } finally { FrameClock.mainThread = saved; ZombieLod.resetForTest(); }
    }

    static void simLodHolderTargetsStock() throws Exception {
        me.zed_0xff.zombie_buddy.Patch p = ZombiePatches.SimLevel.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
        assert p.className().equals("zombie.MovingObjectUpdateScheduler") && p.methodName().equals("getUpdateSchedulerSimulationLevelForObject");
        boolean exit = false;
        for (java.lang.reflect.Method m : ZombiePatches.SimLevel.class.getDeclaredMethods()) {
            me.zed_0xff.zombie_buddy.Patch.OnExit x = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
            if (x != null) exit = x.onThrowable() != Throwable.class;
        }
        assert exit;                                                                     // no onThrowable: the classifier's own exception passes
    }

    // ---- Task 6: player sight memo ----

    static void spottedStackMatchesStack() {
        java.util.Random r = new java.util.Random(88);
        Object[] pool = new Object[40];
        for (int i = 0; i < pool.length; i++) pool[i] = new Object();
        for (int run = 0; run < 200; run++) {
            java.util.Stack<Object> a = new java.util.Stack<>();
            SpottedStack<Object> b = new SpottedStack<>();
            for (int step = 0; step < 400; step++) {
                Object o = pool[r.nextInt(pool.length)];
                int n = a.size();
                switch (r.nextInt(17)) {
                    case 0 -> { assert a.add(o) == b.add(o); }
                    case 1 -> { a.push(o); b.push(o); }
                    case 2 -> { a.addElement(o); b.addElement(o); }
                    case 3 -> { assert a.remove(o) == b.remove(o); }
                    case 4 -> { if (n > 0) { int i = r.nextInt(n); assert a.remove(i) == b.remove(i); } }
                    case 5 -> { if (n > 0) assert a.pop() == b.pop(); }
                    case 6 -> { if (n > 0) { int i = r.nextInt(n); assert a.set(i, o) == b.set(i, o); } }
                    case 7 -> { if (n > 0) { int i = r.nextInt(n); a.setElementAt(o, i); b.setElementAt(o, i); } }
                    case 8 -> { int i = r.nextInt(n + 1); a.insertElementAt(o, i); b.insertElementAt(o, i); }
                    case 9 -> { java.util.List<Object> l = java.util.List.of(o, pool[r.nextInt(pool.length)]); assert a.addAll(l) == b.addAll(l); }
                    case 10 -> { java.util.Set<Object> s = java.util.Set.of(o); assert a.removeAll(s) == b.removeAll(s); }
                    case 11 -> { assert a.removeIf(x -> x == o) == b.removeIf(x -> x == o); }
                    case 12 -> { if (r.nextInt(20) == 0) { a.clear(); b.clear(); } }
                    case 13 -> { java.util.Iterator<Object> ia = a.iterator(), ib = b.iterator(); while (ia.hasNext()) { Object x = ia.next(); ib.next(); if (x == o) { ia.remove(); ib.remove(); } } }
                    case 14 -> { if (n > 0) { int i = r.nextInt(n); a.subList(i, n).clear(); b.subList(i, n).clear(); } }
                    case 15 -> { if (n > 0) { java.util.ListIterator<Object> la = a.listIterator(), lb = b.listIterator(); la.next(); lb.next(); la.set(o); lb.set(o); } }
                    default -> { if (r.nextInt(30) == 0) { a.removeAllElements(); b.removeAllElements(); } }
                }
                assert a.equals(b) : run + "/" + step;
                for (Object p : pool) assert a.contains(p) == b.contains(p) && a.indexOf(p) == b.indexOf(p) && a.lastIndexOf(p) == b.lastIndexOf(p);
            }
        }
    }

    static void spottedAddKeepsSetFresh() {
        SpottedStack<Object> b = new SpottedStack<>();
        long r0 = SpottedStack.rebuilds;
        for (int i = 0; i < 1000; i++) { Object o = new Object(); assert !b.contains(o); b.add(o); assert b.contains(o) && b.indexOf(o) == i; }
        b.clear();
        assert !b.contains(new Object()) && SpottedStack.rebuilds == r0;           // updateLOS's own operations never rebuild
    }

    static void spottedCopiesAndIsAStack() {
        java.util.Stack<Object> s = new java.util.Stack<>();
        Object x = new Object(), y = new Object();
        s.add(x); s.add(y); s.add(x);
        SpottedStack<Object> c = new SpottedStack<>(s);
        assert c.equals(s) && c.contains(x) && c.contains(y) && c.size() == 3 && c instanceof java.util.Stack;
        assert c.remove(x) && c.contains(x) && c.remove(x) && !c.contains(x);       // duplicates: present until the last copy goes
    }

    // mine: a view made before a rebuild writes after it; the set still follows (the modCount check, and the overridden set())
    static void spottedViewWriteAfterRebuild() {
        Object x = new Object(), y = new Object(), z = new Object();
        SpottedStack<Object> b = new SpottedStack<>();
        b.add(x); b.add(y);
        java.util.Iterator<Object> it = b.iterator();
        assert b.contains(x) && b.contains(y);                                      // rebuilds: the views predate it
        it.next(); it.remove();
        assert !b.contains(x) && b.contains(y) && b.size() == 1;
        java.util.List<Object> sub = b.subList(0, 1);
        assert b.contains(y);
        sub.set(0, z);
        assert !b.contains(y) && b.contains(z) && b.indexOf(z) == 0;
        sub.clear();
        assert !b.contains(z) && b.isEmpty();
    }

    // mine: a clone does not share the set
    static void spottedCloneHasItsOwnSet() {
        Object x = new Object(), y = new Object();
        SpottedStack<Object> b = new SpottedStack<>();
        b.add(x);
        @SuppressWarnings("unchecked") SpottedStack<Object> c = (SpottedStack<Object>) b.clone();
        c.add(y);
        assert c.contains(x) && c.contains(y) && b.contains(x) && !b.contains(y) && b.size() == 1 && c.size() == 2;
        b.clear();
        assert c.contains(x) && !b.contains(x);
    }

    static void losShimOffTouchesNothing() {
        ZombiePatches.losGuard.on = false;
        ZombiePatches.onLosEnter(null); ZombiePatches.onLosExit();                   // no throw, no player read
    }

    // mine: with the key on, a non-game thread and a failing call leave the player alone and never throw; the guard counts the failure
    static void losShimSwapsOnce() throws InterruptedException {
        Thread saved = FrameClock.mainThread;
        ZombiePatches.losGuard.on = true;
        try {
            FrameClock.mainThread = Thread.currentThread();
            ZombiePatches.onLosEnter(null);                                           // a null player: caught, stock path
            FrameClock.mainThread = null;
            ZombiePatches.onLosEnter(new Object());                                   // not the game thread: the player is never read
            assert ZombiePatches.losGuard.on;
        } finally { FrameClock.mainThread = saved; ZombiePatches.losGuard.on = false; }
    }

    static void losHolderTargetsStock() throws Exception {
        me.zed_0xff.zombie_buddy.Patch p = ZombiePatches.UpdateLos.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
        assert p.className().equals("zombie.characters.IsoPlayer") && p.methodName().equals("updateLOS");
        boolean exit = false;
        for (java.lang.reflect.Method m : ZombiePatches.UpdateLos.class.getDeclaredMethods()) {
            me.zed_0xff.zombie_buddy.Patch.OnExit x = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
            if (x != null) exit = x.onThrowable() == Throwable.class && x.suppress() == Throwable.class;
        }
        assert exit;
    }

    // ---- Task 7: cull sort memo ----

    static void cullSortOrderIsStock() throws Throwable {
        java.util.Random r = new java.util.Random(808);
        int threw = 0;
        for (int run = 0; run < 300; run++) {
            int n = run < 12 ? run : r.nextInt(3000);
            Object[] items = new Object[n];
            java.util.IdentityHashMap<Object, Float> sc = new java.util.IdentityHashMap<>();
            for (int i = 0; i < n; i++) {
                items[i] = new Object();
                float f = switch (r.nextInt(8)) { case 0 -> Float.NaN; case 1 -> 0f; case 2 -> -0f; case 3 -> (float) r.nextInt(5); default -> r.nextFloat() * 100f; };
                sc.put(items[i], f);
            }
            if (run % 7 == 0) java.util.Arrays.sort(items, (x, y) -> Float.compare(sc.get(x), sc.get(y)));   // long ascending runs
            if (run % 11 == 0) java.util.Collections.reverse(java.util.Arrays.asList(items));
            int lo = n > 20 && run % 5 == 0 ? 5 : 0, hi = n > 20 && run % 5 == 0 ? n - 3 : n;
            Object[] a = items.clone(), b = items.clone();
            // with NaN scores (equal to everything) the game's TimSort may itself reject the comparator: then ours must reject it the same way
            // and leave the array in the same half-sorted state, since the comparison sequence is the same
            String stockEnd = null, fastEnd = null;
            try { new zombie.entity.util.TimSort<Object>().doSort(a, (x, y) -> CullSort.compareScores(sc.get(x), sc.get(y)), lo, hi); }
            catch (IllegalArgumentException e) { stockEnd = e.getMessage(); threw++; }
            int[] calls = {0};
            java.util.Comparator<Object> fast = CullSort.prepare(b, lo, hi, z -> { calls[0]++; return sc.get(z); });
            try { new zombie.entity.util.TimSort<Object>().doSort(b, fast, lo, hi); }
            catch (IllegalArgumentException e) { fastEnd = e.getMessage(); }
            CullSort.done(fast);
            assert java.util.Objects.equals(stockEnd, fastEnd) : run;
            assert java.util.Arrays.equals(a, b) && calls[0] == hi - lo : run;
        }
        assert threw > 0 && threw < 300 : threw;                                           // both the accepted and the rejected case ran
    }

    static void cullSortCompareArithmetic() {
        assert CullSort.compareScores(1f, 2f) == 1 && CullSort.compareScores(2f, 1f) == -1 && CullSort.compareScores(1f, 1f) == 0;
        assert CullSort.compareScores(Float.NaN, 1f) == 0 && CullSort.compareScores(1f, Float.NaN) == 0 && CullSort.compareScores(0f, -0f) == 0;
    }

    static void cullSortLeavesOtherSortsAlone() {
        CullSort.resetForTest(); CullSort.guard.on = true;
        java.util.Comparator<Object> other = (x, y) -> 0;
        assert ZombiePatches.onSortEnter(new Object[0], other, 0, 0) == other;
        CullSort.guard.on = false;
        assert ZombiePatches.onSortEnter(new Object[0], other, 0, 0) == other;
        ZombiePatches.onSortExit(other);
        CullSort.resetForTest();
    }

    // mine: the shim hands a stored-score comparator back for the game's comparator only (game thread, two or more elements); a score
    // that throws leaves the game's comparator in place; the exit shim releases the table; elements outside the range cost a miss
    static void cullSortShimPicksStockOnly() throws Throwable {
        Thread saved = FrameClock.mainThread;
        CullSort.resetForTest();
        java.util.Comparator<Object> game = (x, y) -> 0, other = (x, y) -> 0;
        Object[] a = {new Object(), new Object(), new Object()};
        try {
            FrameClock.mainThread = Thread.currentThread();
            CullSort.guard.on = true; CullSort.stock = game;
            int[] calls = {0};
            CullSort.score = z -> { calls[0]++; return 1f; };
            assert ZombiePatches.onSortEnter(a, other, 0, 3) == other && calls[0] == 0;
            assert ZombiePatches.onSortEnter(a, game, 0, 1) == game && ZombiePatches.onSortEnter(a, game, 2, 2) == game && calls[0] == 0;
            java.util.Comparator c = ZombiePatches.onSortEnter(a, game, 0, 3);
            assert c instanceof CullSort.Prepared && c != game && calls[0] == 3;
            assert c.compare(a[0], a[1]) == 0;
            long m0 = CullSort.misses;
            c.compare(new Object(), a[1]);                                                  // not in the range: scored again, counted
            assert CullSort.misses == m0 + 1 && calls[0] == 4;
            ZombiePatches.onSortExit(c);
            c = ZombiePatches.onSortEnter(a, game, 1, 3);                                    // the table is reusable after done
            assert c instanceof CullSort.Prepared && calls[0] == 6;
            ZombiePatches.onSortExit(c);
            // a nested prepare while one is open gets a table of its own
            java.util.Comparator c1 = CullSort.prepare(a, 0, 3, CullSort.score), c2 = CullSort.prepare(a, 0, 3, CullSort.score);
            assert c1 != c2;
            CullSort.done(c1); CullSort.done(c2);
            // a throwing score: the game's comparator stays, and the table is free again
            CullSort.score = z -> { throw new IllegalStateException("score"); };
            assert ZombiePatches.onSortEnter(a, game, 0, 3) == game && CullSort.guard.on;
            CullSort.score = z -> 2f;
            c = ZombiePatches.onSortEnter(a, game, 0, 3);
            assert c instanceof CullSort.Prepared;
            ZombiePatches.onSortExit(c);
            // another thread
            java.util.Comparator[] got = new java.util.Comparator[1];
            Thread t = new Thread(() -> got[0] = ZombiePatches.onSortEnter(a, game, 0, 3));
            t.start(); t.join(5000);
            assert !t.isAlive() && got[0] == game;
        } finally { FrameClock.mainThread = saved; CullSort.resetForTest(); }
    }

    // mine: with the key on, init finds the game's comparator class and getScore without starting IsoWorld; with it off nothing is looked up
    static void cullSortInitFindsGameClass() throws Throwable {
        Settings.load(SelfTest.write("enabled=true\nzombieCullSortFast=true\n"));
        CullSort.resetForTest();
        CullSort.init();
        try {
            assert CullSort.guard.on && CullSort.scoreClass != null && CullSort.getScore != null && CullSort.stock == null;
            assert CullSort.scoreClass.getName().equals("zombie.iso.IsoWorld$CompScoreToPlayer");
            java.lang.reflect.Constructor<?> k = CullSort.scoreClass.getDeclaredConstructor();
            k.setAccessible(true);
            @SuppressWarnings("rawtypes") java.util.Comparator game = (java.util.Comparator) k.newInstance();
            CullSort.bind(game);
            assert CullSort.stock == game && CullSort.score != null;
            Settings.load(SelfTest.write("enabled=true\n"));
            CullSort.resetForTest();
            CullSort.init();
            assert !CullSort.guard.on && CullSort.scoreClass == null;
        } finally {
            Settings.load(SelfTest.write("enabled=true\n"));
            CullSort.resetForTest();
        }
    }

    static void cullSortHolderTargetsStock() throws Exception {
        me.zed_0xff.zombie_buddy.Patch p = ZombiePatches.CullSortHook.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
        assert p.className().equals("zombie.entity.util.TimSort") && p.methodName().equals("doSort");
        boolean exit = false;
        for (java.lang.reflect.Method m : ZombiePatches.CullSortHook.class.getDeclaredMethods()) {
            me.zed_0xff.zombie_buddy.Patch.OnExit x = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
            if (x != null) exit = x.onThrowable() == Throwable.class && x.suppress() == Throwable.class;
        }
        assert exit;
    }

    // ---- Task 8: stat reads and state flags ----

    static void statsGetMatchesStock() {
        Settings.load(SelfTest.write("enabled=true\nstatsNoBox=true\n"));
        ZombieMisc.init();
        try {
            assert ZombieMisc.statsGuard.on;
            zombie.characters.Stats s = new zombie.characters.Stats();
            java.util.Random r = new java.util.Random(4);
            zombie.characters.CharacterStat[] all = zombie.characters.CharacterStat.ORDERED_STATS;
            assert all.length > 0;
            for (int round = 0; round < 50; round++) {
                for (zombie.characters.CharacterStat c : all) {
                    if (r.nextInt(3) == 0) s.set(c, r.nextFloat() * 2f - 0.5f);
                    long v = ZombieMisc.statsGet(s, c);
                    assert v >= 0 && Float.intBitsToFloat((int) v) == s.get(c) : c;
                    long w = ZombiePatches.onStatsGet(s, c);                      // the shim, guard on
                    assert w == v : c;
                }
            }
            ZombieMisc.statsGuard.on = false;
            assert ZombiePatches.onStatsGet(null, null) == -1;
        } finally { ZombieMisc.resetForTest(); }
    }

    /** Mine: a stored null (stock throws), a null stat and a failing read leave the stock path to run. */
    static void statsGetFallsBackToStock() {
        Settings.load(SelfTest.write("enabled=true\nstatsNoBox=true\n"));
        ZombieMisc.init();
        try {
            zombie.characters.Stats s = new zombie.characters.Stats();
            zombie.characters.CharacterStat c = zombie.characters.CharacterStat.HUNGER;
            java.util.Map<Object, Object> m = ZombieMisc.statsMapOf(s);
            m.put(c, null);                                                           // a key mapped to null: stock's getOrDefault gives null, then NPE
            assert ZombieMisc.statsGet(s, c) == -1;
            boolean threw = false;
            try { s.get(c); } catch (NullPointerException e) { threw = true; }
            assert threw;
            assert ZombieMisc.statsGet(s, null) == -1;
            m.remove(c);
            long v = ZombieMisc.statsGet(s, c);
            assert v >= 0 && Float.intBitsToFloat((int) v) == c.getDefaultValue() && Float.intBitsToFloat((int) v) == s.get(c);
            // -0f and NaN survive the packing bit for bit
            m.put(c, -0f);
            assert Float.floatToRawIntBits(Float.intBitsToFloat((int) ZombieMisc.statsGet(s, c))) == Float.floatToRawIntBits(-0f);
            m.put(c, Float.NaN);
            assert Float.isNaN(Float.intBitsToFloat((int) ZombieMisc.statsGet(s, c)));
            // an object that is not a Stats: the shim catches, counts one failure and answers stock
            assert ZombiePatches.onStatsGet(new Object(), c) == -1 && ZombieMisc.statsGuard.on;
        } finally { ZombieMisc.resetForTest(); Settings.load(SelfTest.write("enabled=true\n")); }
    }

    static void statsShimOffTouchesNothing() {
        ZombieMisc.resetForTest();
        assert ZombiePatches.onStatsGet(null, null) == -1 && ZombiePatches.onFromInt(3) == null;
        Settings.load(SelfTest.write("enabled=false\nstatsNoBox=true\npopmanFlagsFlyweight=true\n"));
        ZombieMisc.init();
        assert !ZombieMisc.statsGuard.on && !ZombieMisc.flagsGuard.on;
        Settings.load(SelfTest.write("enabled=true\n"));
    }

    static void flagsFlyweightSameFlags() {
        ZombieMisc.resetForTest();
        ZombieMisc.flagsGuard.on = true;
        try {
            for (int v = 0; v < 64; v++) {
                zombie.popman.ZombieStateFlags shared = ZombieMisc.flags(v), stock = new zombie.popman.ZombieStateFlags(v);
                assert shared == ZombieMisc.flags(v) && shared != stock && shared.asInt() == stock.asInt() && shared.toString().equals(stock.toString());
                assert ZombiePatches.onFromInt(v) == shared;
                assert shared.isInitialized() == stock.isInitialized() && shared.isCrawling() == stock.isCrawling() && shared.isCanWalk() == stock.isCanWalk()
                    && shared.isFakeDead() == stock.isFakeDead() && shared.isCanCrawlUnderVehicle() == stock.isCanCrawlUnderVehicle()
                    && shared.isReanimatedForGrappleOnly() == stock.isReanimatedForGrappleOnly();
            }
            assert ZombieMisc.flags(64) == null && ZombieMisc.flags(-1) == null && ZombieMisc.flags(Integer.MIN_VALUE) == null && ZombieMisc.flags(Integer.MAX_VALUE) == null;
            ZombieMisc.flagsGuard.on = false;
            assert ZombieMisc.flags(3) == null && ZombiePatches.onFromInt(3) == null;
        } finally { ZombieMisc.resetForTest(); }
    }

    static void smallHoldersTargetStock() throws Exception {
        me.zed_0xff.zombie_buddy.Patch p = ZombiePatches.StatsGet.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
        assert p.className().equals("zombie.characters.Stats") && p.methodName().equals("get");
        p = ZombiePatches.FlagsFromInt.class.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
        assert p.className().equals("zombie.popman.ZombieStateFlags") && p.methodName().equals("fromInt");
        for (Class<?> h : new Class<?>[] {ZombiePatches.StatsGet.class, ZombiePatches.FlagsFromInt.class}) {
            boolean enterSkips = false, exitReturns = false;
            for (java.lang.reflect.Method m : h.getDeclaredMethods()) {
                me.zed_0xff.zombie_buddy.Patch.OnEnter e = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnEnter.class);
                if (e != null) enterSkips = e.skipOn();
                if (m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class) != null) exitReturns = true;
            }
            assert enterSkips && exitReturns : h;
        }
    }

    // ---- Task 9: entity phase timing and the off-path check ----

    static void entityTimingSumsOutermost() {
        EntityTiming.resetForTest(); EntityTiming.guard.on = true;
        EntityTiming.begin(EntityTiming.SIGHT, 1_000); EntityTiming.end(EntityTiming.SIGHT, 3_000);    // player 1
        EntityTiming.begin(EntityTiming.SIGHT, 5_000); EntityTiming.begin(EntityTiming.SIGHT, 6_000);   // player 2, nested call inside
        EntityTiming.end(EntityTiming.SIGHT, 7_000); EntityTiming.end(EntityTiming.SIGHT, 9_000);
        assert EntityTiming.frameTotal(EntityTiming.SIGHT) == 6_000;
        EntityTiming.resetForTest();
    }

    static void entityTimingMinuteLine() {
        long[][] s = new long[EntityTiming.PHASES][];
        for (int p = 0; p < EntityTiming.PHASES; p++) { s[p] = new long[100]; for (int i = 0; i < 100; i++) s[p][i] = (i + 1) * 10_000L * (p + 1); }
        String l = EntityTiming.minuteLine(100, 1234.56, 2000, s, 100);
        assert l.equals("[PZButter] entityTiming: 100 frames, zombies 1234.6/2000; ms p50/p95/max: classify 0.500/0.950/1.000, "
            + "update 1.000/1.900/2.000, postupdate 1.500/2.850/3.000, cull 2.000/3.800/4.000, sight 2.500/4.750/5.000") : l;
        // nearest rank on an unsorted prefix; entries past count are not read
        long[][] u = new long[EntityTiming.PHASES][];
        for (int p = 0; p < EntityTiming.PHASES; p++) u[p] = new long[] {3_000_000, 1_000_000, 2_000_000, 999_000_000};
        String m = EntityTiming.minuteLine(3, 2.0, 5, u, 3);
        assert m.startsWith("[PZButter] entityTiming: 3 frames, zombies 2.0/5; ms p50/p95/max: classify 2.000/3.000/3.000, update 2.000/3.000/3.000") : m;
        assert EntityTiming.minuteLine(0, 0, 0, u, 0).contains("classify 0.000/0.000/0.000");
    }

    static void entityTimingIgnoresIdleFrames() {
        EntityTiming.resetForTest(); EntityTiming.guard.on = true;
        EntityTiming.frameEnd(1_000_000_000L, 1L, 1L);                  // a menu frame: no phase ran
        assert EntityTiming.samples() == 0;
        EntityTiming.begin(EntityTiming.UPDATE, 0); EntityTiming.end(EntityTiming.UPDATE, 500_000);
        EntityTiming.frameEnd(1_016_000_000L, 2L, 2L);
        assert EntityTiming.samples() == 1;
        assert EntityTiming.frameTotal(EntityTiming.UPDATE) == 0;       // the frame was cleared
        EntityTiming.frameEnd(1_033_000_000L, 3L, 3L);                  // idle again
        assert EntityTiming.samples() == 1;
        EntityTiming.resetForTest();
    }

    /** Mine: an end without its begin (the key went on inside the call) adds nothing and does not unbalance the next pair. */
    static void entityTimingUnbalancedEndsIgnored() {
        EntityTiming.resetForTest(); EntityTiming.guard.on = true;
        EntityTiming.end(EntityTiming.CULL, 9_000);
        assert EntityTiming.frameTotal(EntityTiming.CULL) == 0;
        EntityTiming.begin(EntityTiming.CULL, 10_000); EntityTiming.end(EntityTiming.CULL, 12_000);
        assert EntityTiming.frameTotal(EntityTiming.CULL) == 2_000;
        EntityTiming.resetForTest();
    }

    /** Mine: the minute line goes out once 60 s have passed since the first frame, from the sampled frames, then the samples restart. */
    static void entityTimingMinuteRolls() {
        EntityTiming.resetForTest(); EntityTiming.guard.on = true;
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(cap, true));
        try {
            long t = 5_000_000_000L;
            EntityTiming.frameEnd(t, 0, 1);                                              // the first frame starts the minute
            for (int i = 0; i < 3; i++) {
                EntityTiming.begin(EntityTiming.UPDATE, 0); EntityTiming.end(EntityTiming.UPDATE, (i + 1) * 1_000_000L);
                EntityTiming.zombies(100 * (i + 1));
                EntityTiming.frameEnd(t + 1_000_000_000L * (i + 1), 0, i + 2);
            }
            assert cap.size() == 0 && EntityTiming.samples() == 3;
            EntityTiming.frameEnd(t + EntityTiming.MINUTE_NS, 0, 9);                      // idle frame at 60 s: the line still comes
            System.out.flush();
            String l = cap.toString();
            assert l.startsWith("[PZButter] entityTiming: 3 frames, zombies 200.0/300; ms p50/p95/max: classify 0.000/0.000/0.000, update 2.000/3.000/3.000") : l;
            assert EntityTiming.samples() == 0;
            EntityTiming.frameEnd(t + EntityTiming.MINUTE_NS + 1, 0, 10);
            assert cap.toString().equals(l);                                             // nothing more until the next minute
        } finally { System.setOut(out); EntityTiming.resetForTest(); }
    }

    /** Mine: one entity.out line per sampled frame, epoch ms first, three decimals. */
    static void entityTimingWritesEntityOut() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("pzb-entity").toFile();
        EntityTiming.resetForTest(); EntityTiming.guard.on = true;
        try {
            EntityTiming.log = new LogSink(new java.io.File(dir, "entity.out"), 1 << 20);
            EntityTiming.begin(EntityTiming.CLASSIFY, 0); EntityTiming.end(EntityTiming.CLASSIFY, 1_500_000);
            EntityTiming.begin(EntityTiming.POSTUPDATE, 0); EntityTiming.end(EntityTiming.POSTUPDATE, 2_250_000);
            EntityTiming.zombies(42);
            EntityTiming.frameEnd(1_000_000_000L, 1700000000123L, 77);
            EntityTiming.frameEnd(1_016_000_000L, 1700000000140L, 78);                   // idle: no line
            EntityTiming.log.close();
            String got = java.nio.file.Files.readString(new java.io.File(dir, "entity.out").toPath());
            assert got.equals("1700000000123 77 42 1.500 0.000 2.250 0.000 0.000\n") : got;
        } finally {
            EntityTiming.resetForTest();
            for (java.io.File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    /** Mine: the shims time only on the game thread, with the key on; the scheduler exit leaves the zombie count to the cell (none headless). */
    static void entityTimingShimsCountPhases() throws Exception {
        Thread saved = FrameClock.mainThread;
        FrameClock.mainThread = Thread.currentThread();
        EntityTiming.resetForTest(); EntityTiming.guard.on = true;
        try {
            ZombiePatches.onSchedEnter(EntityTiming.UPDATE);
            ZombiePatches.onSchedExit(EntityTiming.UPDATE);
            ZombiePatches.onSchedEnter(EntityTiming.POSTUPDATE);
            ZombiePatches.onSchedExit(EntityTiming.POSTUPDATE);
            assert EntityTiming.frameTotal(EntityTiming.UPDATE) >= 0 && EntityTiming.frameTotal(EntityTiming.POSTUPDATE) >= 0;
            ZombiePatches.onFrameEnd();
            assert EntityTiming.samples() == 1;
            // another thread: nothing is recorded
            Thread t = new Thread(() -> { ZombiePatches.onSchedEnter(EntityTiming.UPDATE); ZombiePatches.onSchedExit(EntityTiming.UPDATE); ZombiePatches.onFrameEnd(); });
            t.start(); t.join(5000);
            assert !t.isAlive() && EntityTiming.samples() == 1;
            // cull and sight timing through their own shims, with every other cull key off
            ZombieLod.resetForTest();
            ZombiePatches.onCullEnter(); assert !ZombieLod.inCull;
            ZombiePatches.onCullExit();
            ZombiePatches.onLosEnter(null); ZombiePatches.onLosExit();                     // a null player with playerLosFast off is not read
            ZombiePatches.onFrameEnd();
            assert EntityTiming.samples() == 2;
            // key off in the middle: the exit of a call whose entry was timed leaves no stray depth
            ZombiePatches.onSchedEnter(EntityTiming.UPDATE);
            EntityTiming.guard.on = false;
            ZombiePatches.onSchedExit(EntityTiming.UPDATE);
            EntityTiming.guard.on = true;
            ZombiePatches.onFrameEnd();                                                     // clears the open phase
            ZombiePatches.onSchedEnter(EntityTiming.UPDATE); ZombiePatches.onSchedExit(EntityTiming.UPDATE);
            ZombiePatches.onFrameEnd();
            assert EntityTiming.samples() == 4;
        } finally { FrameClock.mainThread = saved; EntityTiming.resetForTest(); ZombieLod.resetForTest(); }
    }

    static void timingHoldersTargetStock() throws Exception {
        java.util.Map<String, String> want = java.util.Map.of("SchedStart", "zombie.MovingObjectUpdateScheduler.startFrame",
            "SchedUpdate", "zombie.MovingObjectUpdateScheduler.update", "SchedPost", "zombie.MovingObjectUpdateScheduler.postupdate");
        for (java.util.Map.Entry<String, String> e : want.entrySet()) {
            Class<?> c = null;
            for (Class<?> x : ZombiePatches.class.getDeclaredClasses()) if (x.getSimpleName().equals(e.getKey())) c = x;
            assert c != null : e.getKey();
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            assert (p.className() + "." + p.methodName()).equals(e.getValue());
            boolean exitOk = false, enter = false;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                me.zed_0xff.zombie_buddy.Patch.OnExit x = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
                if (x != null) exitOk = x.onThrowable() == Throwable.class && x.suppress() == Throwable.class;
                if (m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnEnter.class) != null) enter = true;
            }
            assert exitOk && enter : e.getKey();
        }
        for (String k : new String[] {"ZombiePatches.SchedStart", "ZombiePatches.SchedUpdate", "ZombiePatches.SchedPost"})
            assert java.util.Arrays.asList(ModCompat.KEYS.get(k)).contains("entityTiming") : k;
        assert java.util.Arrays.asList(ModCompat.KEYS.get("Patches.Frame")).contains("entityTiming");
    }

    /** Mine: the frame holder's exit ends with the entityTiming call (the class file calls it; the order is the source's). */
    static void frameHolderEndsEntityTiming() throws Exception {
        String src = java.nio.file.Files.readString(new java.io.File(System.getProperty("user.dir"), "src/pzbutter/Patches.java").toPath());
        int exit = src.indexOf("public static void exit()");
        assert exit > 0 && src.indexOf("ZombiePatches.onFrameEnd();", exit) > 0;
        String line = src.substring(exit, src.indexOf('\n', exit));
        assert line.trim().endsWith("ZombiePatches.onFrameEnd(); }") : line;
    }

    static void zombieShimsOffTouchNothing() {
        FacingFix.resetForTest(); ZombieLod.resetForTest(); CullSort.resetForTest(); EntityTiming.resetForTest(); ZombieMisc.resetForTest();
        ZombiePatches.losGuard.on = false;
        assert !ZombiePatches.onForward(0f, 0f);
        ZombiePatches.onCullEnter(); ZombiePatches.onCullExit();
        assert !ZombiePatches.onCulledEnter(null, false); ZombiePatches.onCulledExit(null);
        assert ZombiePatches.onSimLevel(null, null, zombie.UpdateSchedulerSimulationLevel.FULL) == zombie.UpdateSchedulerSimulationLevel.FULL;
        ZombiePatches.onLosEnter(null); ZombiePatches.onLosExit();
        java.util.Comparator<Object> c = (x, y) -> 0;
        assert ZombiePatches.onSortEnter(null, c, 0, 0) == c; ZombiePatches.onSortExit(c);
        assert ZombiePatches.onStatsGet(null, null) == -1 && ZombiePatches.onFromInt(1) == null;
        ZombiePatches.onSchedEnter(0); ZombiePatches.onSchedExit(0); ZombiePatches.onFrameEnd();
        assert EntityTiming.samples() == 0 && EntityTiming.frameTotal(0) == 0;
        assert zombie.core.PerformanceSettings.numberZombiesBlended == 20;
        // enabled=false answers the same through the inits
        Settings.load(SelfTest.write("enabled=false\nzeroFacingFix=true\nplayerLosFast=true\nzombieCullSortFast=true\nstatsNoBox=true\n"
            + "popmanFlagsFlyweight=true\nentityTiming=true\nnumberZombiesBlended=9\nzombieLodFixed3d=99\nzombieModelAddBudgetUs=9\nzombieSimLodTiles=9\n"));
        ZombiePatches.init();
        assert !FacingFix.guard.on && !ZombiePatches.losGuard.on && !CullSort.guard.on && !ZombieMisc.statsGuard.on && !ZombieMisc.flagsGuard.on
            && !EntityTiming.guard.on && !ZombieLod.blendGuard.on && !ZombieLod.capGuard.on && !ZombieLod.budgetGuard.on && !ZombieLod.simGuard.on;
        Settings.load(SelfTest.write("enabled=true\n"));
        ZombiePatches.init();
        assert !EntityTiming.guard.on && EntityTiming.log == null;
    }
}
