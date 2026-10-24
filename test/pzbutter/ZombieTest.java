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
}
