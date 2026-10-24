package pzbutter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;

import se.krka.kahlua.converter.KahluaConverterManager;
import se.krka.kahlua.integration.LuaCaller;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaThread;

/** Plan 7 checks (Task 1: keys and template; Tasks 2-3: the Lua token and its hooks). Run from SelfTest.main. Later tasks append their calls to run(). */
final class LuaGateTest {
    private LuaGateTest() {}

    static void run() throws Exception {
        plan7KeysDefaults();
        settingsTemplateHasPlan7Keys();
        gateOffPassesThrough();
        ownerAndOthersPassThrough();
        workersSerialiseOnOneStack();
        workerImpersonatesGameThread();
        reentrantCallSwapsOnce();
        exceptionReleasesAndRestores();
        yieldPointRunsWaitersOnly();
        frameYieldServesStray();
        frameYieldSkipsWhenLoaderOwnsLua();
        workerWaitsBeforeClaim();
        availableNeedsKeyAndClaim();
        gateKeyOffByDefault();
        gateInitResolvesSlot();
        awaitYieldingWakesAndRetakes();
        workerThatEndsHoldingFreesTheToken();
        failedRestoreStillReleases();
        failedEnterStillReleases();
        awaitYieldingKeepsTokenWhileLoaderOwnsLua();
        awaitYieldingFreesTokenWhenLoaderHandsLuaBack();
        yieldPointKeepsTokenWhileLoaderOwnsLua();
        interruptFlagKeptAcrossWaits();
        longWaitsLogOnce();
        triggerEventNeverCountsStrays();
        statLinesAreDeltas();
        frameYieldPrintsStatOnlyOnChange();
        gateHoldersBalanced();
        gateShimOffReadsNothing();
        gateShimRoutesWorker();
        replayInLoopOrder();
        sameOrderKeepsCaptureOrder();
        throwingFireContinues();
        replayKeepsOneThreadsTiesAcrossGrowth();
        replayEmptiesAndPrunesEndedThreads();
        heartbeatNameRule();
        keyAndPathRules();
        rewriteBurstLastWins();
        heartbeatAppendsKept();
        stockAppendFlushesHeldFirst();
        stockRewriteSupersedesHeld();
        readBackFlushesAndExempts();
        heldWriterIsStockClass();
        fileThrottleOffPassesThrough();
        tickCountsAsAcceptedOpen();
        createFalseHeldOnlyWithPendingText();
        unknownNameAndSizeCapPassThrough();
        heldTextCapFlushesEarly();
        readsOfUnwrittenNamesMakeNoEntries();
        windowsKeysFoldCase();
        fileKeyOffByDefault();
        fileHookShimsStockWhenOff();
        fileHoldersTargetStock();
        fileHoldersShape();
        firstPrintedRepeatsDropped();
        argsLinesNeverDropped();
        otherStreamOrSeverityPrinted();
        summaryPassesThrough();
        keyCapPassesThrough();
        concurrentCountsExact();
        dedupeOffDropsNothing();
        dedupeKeyOffByDefault();
        summariesInFirstSeenOrder();
        lateRepeatAfterRollPrints();
        concurrentRollLosesNoCount();
        logShimPrintsSummaryOnStream();
        logShimOffAndErrors();
        logShimSkipsFilteredLines();
        onceLinesPassThroughUntouched();
        summaryKeepsSeverityAndStream();
        onLineNeverPrintsSummaries();
        logHoldersShape();
        scanFindsPatchAnnotations();
        scanReadsEveryReplacingForm();
        nameMatchesZbRules();
        conflictOnlyWhenOneReplaces();
        profileSetsTheTwoKeys();
        forcedOffAnswersStock();
        forcedOffStockTrueKeys();
        otherJarsNeverThrows();
        jarsFromKeepsLoadedAndDropsOwn();
        keysTableCoversEveryHolder();
        forcedKeysAnswerStock();
        oursReadsEveryHolder();
        autoForcesOnlyConflictingKeys();
        applyOffAndUnknownHolder();
        autoFeedsSettings();
        modCompatInitOffDoesNothing();
        gpuQueueForcedOff();
    }

    static void plan7KeysDefaults() {
        // the written template itself, master on: every Plan 7 key at its shipped value
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        for (String k : new String[] {"luaWorkerGate", "luaFileWriteThrottle", "logDedupe"}) assert !Settings.flag(k) : k;
        assert Settings.text("modProfile", "?").equals("custom") && Settings.text("modCompat", "?").equals("off");
        assert Settings.flag("uiRetainedMods");
    }

    static void settingsTemplateHasPlan7Keys() {
        String t = Settings.render(new java.util.Properties());
        for (String k : new String[] {"luaWorkerGate=false", "luaFileWriteThrottle=false", "logDedupe=false", "modProfile=custom", "modCompat=off"})
            assert t.contains("\n" + k + "\n") : k;
        assert t.indexOf("\nluaInternConstants=true\n") < t.indexOf("\nluaWorkerGate=false\n");
    }

    // ---- Tasks 2-3: the Lua token ----

    static volatile Thread fakeGame;
    static final VarHandle FAKE;
    static final Thread FINAL_SLOT = null;
    /** A slot whose set() throws (a final field): a restore or an enter that fails half way. */
    static final VarHandle BAD_SLOT;
    static {
        try {
            FAKE = MethodHandles.lookup().findStaticVarHandle(LuaGateTest.class, "fakeGame", Thread.class);
            BAD_SLOT = MethodHandles.lookup().findStaticVarHandle(LuaGateTest.class, "FINAL_SLOT", Thread.class);
        }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    /** A real Kahlua thread and caller, no game (research Surprise 7: a bare table as the environment). */
    static final class Lua {
        final KahluaTableImpl env = new KahluaTableImpl(new HashMap<>());
        final KahluaThread kt = new KahluaThread(J2SEPlatform.getInstance(), env);
        final LuaCaller caller = new LuaCaller(new KahluaConverterManager());

        Lua(String src) {
            kt.debugOwnerThread = Thread.currentThread();
            try { caller.pcall(kt, LuaCompiler.loadstring(src, "t", env), new Object[0]); }
            catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
    }

    static void setUp(Lua l) {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.gameThreadSlot = FAKE; fakeGame = Thread.currentThread();
        LuaGate.luaThreads = () -> new KahluaThread[] {l.kt};
        LuaGate.claim();
    }

    static void gated(Lua l, Runnable call) {
        boolean g = LuaGate.enter(l.kt);
        try { call.run(); } finally { if (g) LuaGate.exit(); }
    }

    /** n gate workers run body; the test thread waits in awaitYielding (the token is free) and joins them. */
    static void runWorkers(int n, Runnable body) {
        Thread[] w = new Thread[n];
        Throwable[] failed = new Throwable[1];
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < n; i++) {
            w[i] = LuaGate.worker(() -> {
                try { body.run(); }
                catch (Throwable t) { synchronized (failed) { if (failed[0] == null) failed[0] = t; } }
                finally { finished.incrementAndGet(); LuaGate.wakeOwner(); }
            }, "gate-worker-" + i);
            w[i].start();
        }
        long t0 = System.nanoTime();
        LuaGate.awaitYielding(() -> finished.get() == n || System.nanoTime() - t0 > 20_000_000_000L);
        for (Thread t : w) {
            try { t.join(5000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            assert !t.isAlive() : "worker still running: " + t.getName();
        }
        synchronized (failed) {
            if (failed[0] instanceof Error e) throw e;
            if (failed[0] != null) throw new AssertionError(failed[0]);
        }
    }

    /**
     * Runs body on a fresh thread that is the game thread (claims the token, owns Lua and fakeGame) and joins it with a bound, so a
     * token that is never freed fails the test with a message instead of hanging the build. Failures in body are rethrown here.
     */
    static void onGameThread(Lua l, Runnable body) throws Exception {
        Throwable[] failed = new Throwable[1];
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.gameThreadSlot = FAKE;
        LuaGate.luaThreads = () -> new KahluaThread[] {l.kt};
        Thread g = new Thread(() -> {
            try { fakeGame = Thread.currentThread(); l.kt.debugOwnerThread = Thread.currentThread(); LuaGate.claim(); body.run(); }
            catch (Throwable t) { failed[0] = t; }
        }, "game-thread");
        g.setDaemon(true);
        g.start();
        g.join(15_000);
        assert !g.isAlive() : "game thread stuck: the token was never freed";
        if (failed[0] instanceof Error e) throw e;
        if (failed[0] != null) throw new AssertionError(failed[0]);
    }

    static void waitFor(java.util.function.BooleanSupplier c, String what) {
        long t0 = System.nanoTime();
        while (!c.getAsBoolean()) { assert System.nanoTime() - t0 < 5_000_000_000L : what; Thread.onSpinWait(); }
    }

    static void gateOffPassesThrough() {
        Lua l = new Lua("function f() end"); setUp(l); LuaGate.guard.on = false;
        boolean[] took = {true};
        runWorkers(1, () -> took[0] = LuaGate.enter(l.kt));
        assert !took[0] && LuaGate.gatedCalls == 0;
        LuaGate.resetForTest();
    }

    static void ownerAndOthersPassThrough() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        assert !LuaGate.enter(l.kt) && LuaGate.strays == 0;                           // the game thread itself
        boolean[] took = {true};
        Thread other = new Thread(() -> took[0] = LuaGate.enter(l.kt), "probe-thread");  // not a worker
        other.start(); other.join(5000);
        assert !took[0] && LuaGate.strays == 1 && LuaGate.statLine(60_000_000_000L).contains("probe-thread");
        LuaGate.resetForTest();
    }

    static void workersSerialiseOnOneStack() {
        Lua l = new Lua("n = 0; function inc() n = n + 1 end"); setUp(l);
        Object inc = l.env.rawget("inc");
        int top = l.kt.currentCoroutine.getTop();
        runWorkers(8, () -> { for (int i = 0; i < 2000; i++) gated(l, () -> l.caller.pcallvoid(l.kt, inc, (Object) null)); });
        assert ((Double) l.env.rawget("n")) == 16000.0 && l.kt.currentCoroutine.getTop() == top;
        assert LuaGate.gatedCalls == 16000;
        LuaGate.resetForTest();
    }

    static void workerImpersonatesGameThread() {
        Lua l = new Lua("function f() probe() end"); setUp(l);
        boolean[] seen = new boolean[2];
        l.env.rawset("probe", (se.krka.kahlua.vm.JavaFunction) (frame, nargs) -> {
            seen[0] = l.kt.debugOwnerThread == Thread.currentThread(); seen[1] = fakeGame == Thread.currentThread(); return 0; });
        Thread main = Thread.currentThread();
        runWorkers(1, () -> gated(l, () -> l.caller.pcallvoid(l.kt, l.env.rawget("f"), (Object) null)));
        assert seen[0] && seen[1] && l.kt.debugOwnerThread == main && fakeGame == main;
        LuaGate.resetForTest();
    }

    static void reentrantCallSwapsOnce() {
        Lua l = new Lua("function f() end"); setUp(l);
        Thread main = Thread.currentThread();
        boolean[] ok = new boolean[2];
        runWorkers(1, () -> {
            assert LuaGate.enter(l.kt) && LuaGate.enter(l.kt);
            LuaGate.exit(); ok[0] = fakeGame == Thread.currentThread();
            LuaGate.exit(); ok[1] = fakeGame == main;
        });
        assert ok[0] && ok[1] && LuaGate.gatedCalls == 1;
        LuaGate.resetForTest();
    }

    static void exceptionReleasesAndRestores() {
        Lua l = new Lua("function f() error('x') end"); setUp(l);
        Thread main = Thread.currentThread();
        runWorkers(2, () -> {
            try { gated(l, () -> { l.caller.pcallvoid(l.kt, l.env.rawget("f"), (Object) null); throw new IllegalStateException("after"); }); }
            catch (IllegalStateException expected) { }
        });
        assert LuaGate.gatedCalls == 2 && fakeGame == main && l.kt.debugOwnerThread == main;
        LuaGate.resetForTest();
    }

    static void yieldPointRunsWaitersOnly() throws Exception {
        Lua l = new Lua("n = 0; function inc() n = n + 1 end"); setUp(l);
        Object inc = l.env.rawget("inc");
        Thread[] w = new Thread[3];
        for (int i = 0; i < 3; i++) (w[i] = LuaGate.worker(() -> gated(l, () -> l.caller.pcallvoid(l.kt, inc, (Object) null)), "w" + i)).start();
        waitFor(() -> LuaGate.waiting() == 3, "three waiters");
        assert LuaGate.yieldPoint() == 3 && ((Double) l.env.rawget("n")) == 3.0;
        for (Thread t : w) t.join(5000);
        assert LuaGate.yieldPoint() == 0;
        LuaGate.resetForTest();
    }

    static void frameYieldServesStray() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        Thread w = LuaGate.worker(() -> gated(l, () -> l.caller.pcallvoid(l.kt, l.env.rawget("f"), (Object) null)), "stray");
        w.start();
        waitFor(() -> LuaGate.waiting() >= 1, "stray waiter");
        LuaGate.frameYield();
        w.join(5000);
        assert !w.isAlive() && LuaGate.gatedCalls == 1;
        LuaGate.resetForTest();
    }

    static void frameYieldSkipsWhenLoaderOwnsLua() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        Thread loader = new Thread(() -> { }, "loader");
        l.kt.debugOwnerThread = loader;                                              // a world load owns Lua
        Thread w = LuaGate.worker(() -> gated(l, () -> { }), "w");
        w.start();
        waitFor(() -> LuaGate.waiting() >= 1, "waiter");
        LuaGate.frameYield();
        assert w.isAlive() && LuaGate.gatedCalls == 0 && LuaGate.waiting() == 1;     // frameYield returned; nothing was handed over
        l.kt.debugOwnerThread = Thread.currentThread();
        LuaGate.frameYield(); w.join(5000);
        assert !w.isAlive() && LuaGate.gatedCalls == 1;
        LuaGate.resetForTest();
    }

    static void workerWaitsBeforeClaim() throws Exception {
        Lua l = new Lua("function f() end");
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.gameThreadSlot = FAKE; fakeGame = Thread.currentThread();
        LuaGate.luaThreads = () -> new KahluaThread[] {l.kt};
        Thread w = LuaGate.worker(() -> gated(l, () -> { }), "early");
        w.start();
        waitFor(() -> LuaGate.waiting() >= 1, "early waiter");
        assert LuaGate.gatedCalls == 0 && !LuaGate.available();
        LuaGate.claim();
        assert LuaGate.yieldPoint() == 1;
        w.join(5000);
        assert !w.isAlive() && LuaGate.gatedCalls == 1;
        LuaGate.resetForTest();
    }

    static void availableNeedsKeyAndClaim() {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.gameThreadSlot = FAKE;
        assert !LuaGate.available();
        assert LuaGate.claim() && LuaGate.claim();
        assert LuaGate.available();
        LuaGate.guard.on = false;
        assert !LuaGate.available();
        LuaGate.resetForTest();
    }

    static void gateKeyOffByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        LuaGate.init();
        assert !LuaGate.guard.on;
        LuaGate.resetForTest();
    }

    /** With the key on, init resolves GameWindow.gameThread (a handle, no class initialisation) and builds the supplier. */
    static void gateInitResolvesSlot() {
        Settings.load(SelfTest.write("enabled=true\nluaWorkerGate=true\n"));
        LuaGate.init();
        assert LuaGate.guard.on && LuaGate.gameThreadSlot != null && LuaGate.luaThreads != null;
        LuaGate.resetForTest();
    }

    /** The holder frees the token in awaitYielding, a waiting worker runs, wakeOwner ends the park, the holder takes it back. */
    static void awaitYieldingWakesAndRetakes() {
        Lua l = new Lua("n = 0; function inc() n = n + 1 end"); setUp(l);
        Object inc = l.env.rawget("inc");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread w = LuaGate.worker(() -> { gated(l, () -> l.caller.pcallvoid(l.kt, inc, (Object) null)); done.set(true); LuaGate.wakeOwner(); }, "w");
        w.start();
        long t0 = System.nanoTime();
        LuaGate.awaitYielding(() -> done.get() || System.nanoTime() - t0 > 20_000_000_000L);
        assert done.get() && ((Double) l.env.rawget("n")) == 1.0;
        assert LuaGate.yieldPoint() == 0;                                            // the holder owns the token again
        // a thread that is not the holder only waits
        java.util.concurrent.atomic.AtomicBoolean ok = new java.util.concurrent.atomic.AtomicBoolean();
        Thread o = new Thread(() -> { boolean[] f = {false}; LuaGate.awaitYielding(() -> f[0] = true); ok.set(f[0]); }, "not-holder");
        o.start();
        try { o.join(5000); } catch (InterruptedException e) { throw new RuntimeException(e); }
        assert ok.get() && !o.isAlive();
        LuaGate.resetForTest();
    }

    // ---- review fixes: a holder that ends, restore/enter that fail, loader, interrupts, logs ----

    /** A worker whose body ends (or dies) with a hold gives it back: Lua owner fields and gameThread restored, the token free. */
    static void workerThatEndsHoldingFreesTheToken() throws Exception {
        Lua l = new Lua("n = 0; function inc() n = n + 1 end");
        Object inc = l.env.rawget("inc");
        onGameThread(l, () -> {                                                                  // bounded: a broken abandon fails instead of hanging in the retake
            Thread main = Thread.currentThread();
            runWorkers(1, () -> { LuaGate.enter(l.kt); LuaGate.enter(l.kt); });                   // nested, never exited
            assert fakeGame == main && l.kt.debugOwnerThread == main;
            Thread dying = LuaGate.worker(() -> { LuaGate.enter(l.kt); throw new IllegalStateException("died holding"); }, "dying");
            dying.setUncaughtExceptionHandler((t, e) -> { });                                      // really dies: the exception leaves run()
            dying.start();
            long t0 = System.nanoTime();
            LuaGate.awaitYielding(() -> !dying.isAlive() || System.nanoTime() - t0 > 10_000_000_000L);
            try { dying.join(5000); } catch (InterruptedException e) { throw new RuntimeException(e); }
            assert !dying.isAlive();
            assert fakeGame == main && l.kt.debugOwnerThread == main;
            runWorkers(1, () -> gated(l, () -> l.caller.pcallvoid(l.kt, inc, (Object) null)));    // would queue forever behind a dead holder
            assert ((Double) l.env.rawget("n")) == 1.0 && LuaGate.yieldPoint() == 0;
        });
        LuaGate.resetForTest();
    }

    /** exit() whose restore throws still releases; the Lua owner fields go back even though the slot would not. */
    static void failedRestoreStillReleases() {
        Lua l = new Lua("function f() end"); setUp(l);
        Thread main = Thread.currentThread();
        boolean[] threw = new boolean[1];
        runWorkers(1, () -> {
            assert LuaGate.enter(l.kt);
            LuaGate.gameThreadSlot = BAD_SLOT;
            try { LuaGate.exit(); } catch (UnsupportedOperationException expected) { threw[0] = true; }
            LuaGate.gameThreadSlot = FAKE;
        });
        assert threw[0] && l.kt.debugOwnerThread == main;
        runWorkers(1, () -> gated(l, () -> { }));                                              // the token was released
        assert LuaGate.gatedCalls == 2;
        LuaGate.resetForTest();
    }

    /** enter() failing after the token was taken releases it and puts the fields back. */
    static void failedEnterStillReleases() {
        Lua l = new Lua("function f() end"); setUp(l);
        Thread main = Thread.currentThread();
        boolean[] threw = new boolean[1];
        LuaGate.gameThreadSlot = BAD_SLOT;
        runWorkers(1, () -> { try { LuaGate.enter(l.kt); } catch (UnsupportedOperationException expected) { threw[0] = true; } });
        LuaGate.gameThreadSlot = FAKE;
        assert threw[0] && l.kt.debugOwnerThread == main && LuaGate.gatedCalls == 0;
        runWorkers(1, () -> gated(l, () -> { }));
        assert LuaGate.gatedCalls == 1;
        LuaGate.resetForTest();
    }

    /** While a loader owns Lua, awaitYielding keeps the token: no worker runs on the loader's stack. */
    static void awaitYieldingKeepsTokenWhileLoaderOwnsLua() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        Thread loader = new Thread(() -> { }, "loader");
        l.kt.debugOwnerThread = loader;
        Thread w = LuaGate.worker(() -> gated(l, () -> { }), "w");
        w.start();
        waitFor(() -> LuaGate.waiting() >= 1, "waiter");
        int[] polls = {0};
        LuaGate.awaitYielding(() -> ++polls[0] > 200);
        assert w.isAlive() && LuaGate.gatedCalls == 0 && LuaGate.waiting() == 1;
        l.kt.debugOwnerThread = Thread.currentThread();
        assert LuaGate.yieldPoint() == 1;
        w.join(5000);
        assert !w.isAlive() && LuaGate.gatedCalls == 1;
        LuaGate.resetForTest();
    }

    /**
     * The loader owns Lua when the holder starts waiting and a worker queues behind the token. When the loader hands Lua back, the
     * wait must free the token so the worker runs and done comes true (it stayed held for ever before). Bounded: fails, never hangs.
     */
    static void awaitYieldingFreesTokenWhenLoaderHandsLuaBack() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        Thread me = Thread.currentThread();
        Thread loader = new Thread(() -> { }, "loader");
        l.kt.debugOwnerThread = loader;
        java.util.concurrent.atomic.AtomicBoolean workerDone = new java.util.concurrent.atomic.AtomicBoolean();
        Thread w = LuaGate.worker(() -> { gated(l, () -> { }); workerDone.set(true); LuaGate.wakeOwner(); }, "w");
        w.start();
        waitFor(() -> LuaGate.waiting() >= 1, "waiter");
        Thread giver = new Thread(() -> {                                             // the load ends: Lua goes back to the game thread
            waitFor(() -> LuaGate.waiting() >= 1, "waiter seen by giver");
            l.kt.debugOwnerThread = me;
            LuaGate.wakeOwner();
        }, "loader-end");
        giver.start();
        long t0 = System.nanoTime();
        LuaGate.awaitYielding(() -> workerDone.get() || System.nanoTime() - t0 > 5_000_000_000L);
        assert workerDone.get() : "the worker never got the token after the loader handed Lua back";
        w.join(5000); giver.join(5000);
        assert !w.isAlive() && !giver.isAlive() && LuaGate.gatedCalls == 1 && LuaGate.yieldPoint() == 0;   // the holder has the token again
        LuaGate.resetForTest();
    }

    static void yieldPointKeepsTokenWhileLoaderOwnsLua() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        l.kt.debugOwnerThread = new Thread(() -> { }, "loader");
        Thread w = LuaGate.worker(() -> gated(l, () -> { }), "w");
        w.start();
        waitFor(() -> LuaGate.waiting() >= 1, "waiter");
        assert LuaGate.yieldPoint() == 0 && w.isAlive() && LuaGate.gatedCalls == 0;
        l.kt.debugOwnerThread = Thread.currentThread();
        assert LuaGate.yieldPoint() == 1;
        w.join(5000);
        assert !w.isAlive();
        LuaGate.resetForTest();
    }

    /** Interrupts never end a wait for the token and the flag is still set afterwards (holder and worker alike). */
    static void interruptFlagKeptAcrossWaits() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        // holder: interrupted inside awaitYielding, which neither spins on it nor drops it. After the interrupt it keeps polling for 20 ms:
        // parked in slices of PARK_SLICE_NS that is about 200 polls at most; with the flag left set every park returns at once (100000s of polls).
        Thread me = Thread.currentThread();
        int[] polls = {0};
        long[] tInt = {0};
        LuaGate.awaitYielding(() -> {
            if (tInt[0] == 0) { if (polls[0]++ == 3) { me.interrupt(); tInt[0] = System.nanoTime(); } return false; }
            polls[0]++;
            return System.nanoTime() - tInt[0] > 20_000_000L;
        });
        assert polls[0] < 2000 : "the interrupted holder spun: " + polls[0] + " polls in 20 ms";
        assert Thread.interrupted() && LuaGate.yieldPoint() == 0;                              // flag kept (and cleared here); token retaken
        // worker waiting for the token is interrupted: it still gets the token, and keeps the flag
        boolean[] kept = new boolean[2];
        Thread w = LuaGate.worker(() -> { gated(l, () -> kept[0] = true); kept[1] = Thread.currentThread().isInterrupted(); }, "w-int");
        w.start();
        waitFor(() -> LuaGate.waiting() == 1, "waiter");
        w.interrupt();
        assert LuaGate.waiting() == 1 && w.isAlive();
        assert LuaGate.yieldPoint() == 1;
        w.join(5000);
        assert !w.isAlive() && kept[0] && kept[1];
        LuaGate.resetForTest();
    }

    /** A thread that waits past warnNs logs once (workers: for the game thread; the game thread: for a frame worker), outside the monitor. */
    static void longWaitsLogOnce() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(cap, true));
            LuaGate.warnNs = 20_000_000L;
            // worker side: it waits for the (never yielding) game thread
            Thread w = LuaGate.worker(() -> gated(l, () -> { }), "slow-worker");
            w.start();
            waitFor(() -> cap.toString().contains("slow-worker waited 2 s for the game thread"), "worker line");
            Thread.sleep(60);                                                                  // an absence check, not an ordering: three more warn periods, still one line
            assert LuaGate.yieldPoint() == 1;
            w.join(5000);
            assert !w.isAlive();
            // game-thread side: a worker holds the token and does not let go until the game thread's line is out (round 0), or the
            // wait is five warn periods old (round 1: the game thread waits past warnNs again and must not repeat its line)
            for (int round = 0; round < 2; round++) {
                java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
                Thread h = LuaGate.worker(() -> gated(l, () -> {
                    holding.countDown();
                    try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }), "holding-worker");
                h.start();
                boolean first = round == 0;
                Thread opener = new Thread(() -> {
                    long t0 = System.nanoTime();
                    if (first) while (!cap.toString().contains("waited 2 s for a frame worker") && System.nanoTime() - t0 < 5_000_000_000L) Thread.onSpinWait();
                    else { while (holding.getCount() > 0 && System.nanoTime() - t0 < 5_000_000_000L) Thread.onSpinWait(); t0 = System.nanoTime(); while (System.nanoTime() - t0 < 100_000_000L) Thread.onSpinWait(); }
                    release.countDown();
                }, "opener");
                opener.start();
                LuaGate.awaitYielding(() -> holding.getCount() == 0);                          // frees the token; once the worker holds it, retakes it behind that worker
                h.join(5000); opener.join(5000);
                assert !h.isAlive() && !opener.isAlive();
            }
        } finally { System.setOut(out); }
        String log = cap.toString();
        assert count(log, "slow-worker waited 2 s for the game thread") == 1 : log;
        assert count(log, " waited 2 s for a frame worker") == 1 : log;                      // once per thread, not once per long retake
        LuaGate.resetForTest();
    }

    static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) n++;
        return n;
    }

    /** triggerEvent off the owner thread is safe in stock (it queues the event): not a stray, and nothing of LuaManager read. */
    static void triggerEventNeverCountsStrays() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        boolean[] took = {true};
        Thread other = new Thread(() -> took[0] = LuaPatches.gateEnterEvent(), "network-thread");
        other.start(); other.join(5000);
        assert !took[0] && LuaGate.strays == 0;
        Thread hook = new Thread(() -> took[0] = LuaPatches.gateEnter(l.kt), "hook-thread");   // the keyed entries keep counting
        hook.start(); hook.join(5000);
        assert !took[0] && LuaGate.strays == 1;
        LuaGate.guard.on = false;
        assert !LuaPatches.gateEnterEvent();
        LuaGate.resetForTest();
    }

    /** The minute line reports the minute, and the frame hook prints only when something happened since the last one. */
    static void statLinesAreDeltas() {
        Lua l = new Lua("function f() end"); setUp(l);
        runWorkers(1, () -> { gated(l, () -> { }); gated(l, () -> { }); });
        String a = LuaGate.statLine(60_000_000_000L), b = LuaGate.statLine(60_000_000_000L);
        assert a.contains("in 60 s: 2 gated calls") && b.contains("0 gated calls") && b.contains("0 stray") : a + " / " + b;
        runWorkers(1, () -> gated(l, () -> { }));
        assert LuaGate.statLine(1_000_000_000L).contains("in 1 s: 1 gated calls");
        LuaGate.resetForTest();
    }

    /** frameYield prints the minute line only when calls or strays changed since the previous one (statNs shortened to 1 ms). */
    static void frameYieldPrintsStatOnlyOnChange() throws Exception {
        Lua l = new Lua("function f() end"); setUp(l);
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(cap, true));
            LuaGate.statNs = 1_000_000L;
            Runnable frames = () -> { long t0 = System.nanoTime(); while (System.nanoTime() - t0 < 6_000_000L) LuaGate.frameYield(); };   // several periods
            frames.run();
            assert count(cap.toString(), "luaWorkerGate: in ") == 0 : "printed with nothing to report: " + cap;
            runWorkers(1, () -> gated(l, () -> { }));
            frames.run(); frames.run();
            String one = cap.toString();
            assert count(one, "luaWorkerGate: in ") == 1 && one.contains(" 1 gated calls") : one;   // once, then quiet while nothing changes
            runWorkers(1, () -> gated(l, () -> { }));
            frames.run();
            assert count(cap.toString(), "luaWorkerGate: in ") == 2 : cap;
        } finally { System.setOut(out); }
        LuaGate.resetForTest();
    }

    // ---- Task 3: the hooks ----

    static void gateHoldersBalanced() throws Exception {
        java.util.Set<String> want = java.util.Set.of(
            "se.krka.kahlua.integration.LuaCaller.pcallvoid", "se.krka.kahlua.integration.LuaCaller.protectedCallVoid",
            "se.krka.kahlua.integration.LuaCaller.pcallBoolean", "se.krka.kahlua.integration.LuaCaller.pcall",
            "se.krka.kahlua.integration.LuaCaller.protectedCallBoolean", "se.krka.kahlua.integration.LuaCaller.protectedCall",
            "zombie.Lua.LuaEventManager.triggerEvent", "zombie.Lua.LuaHookManager.TriggerHook");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Class<?> c : LuaPatches.class.getDeclaredClasses()) {
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            if (p == null || !want.contains(p.className() + "." + p.methodName())) continue;
            assert seen.add(p.className() + "." + p.methodName()) : "two holders for " + p.methodName();
            boolean exitOk = false, localOk = false;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                me.zed_0xff.zombie_buddy.Patch.OnExit x = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnExit.class);
                if (x != null) {
                    boolean exitLocal = false;
                    for (java.lang.annotation.Annotation[] as : m.getParameterAnnotations())
                        for (java.lang.annotation.Annotation a : as)
                            if (a instanceof me.zed_0xff.zombie_buddy.Patch.Local loc && loc.value().equals("g")) exitLocal = true;
                    exitOk = x.onThrowable() == Throwable.class && x.suppress() == Throwable.class && exitLocal;
                }
                if (m.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.OnEnter.class))
                    for (java.lang.annotation.Annotation[] as : m.getParameterAnnotations())
                        for (java.lang.annotation.Annotation a : as)
                            if (a instanceof me.zed_0xff.zombie_buddy.Patch.Local loc && loc.value().equals("g")) localOk = true;
            }
            assert exitOk && localOk : c.getName();
        }
        assert seen.equals(want) : seen;
    }

    static void gateShimOffReadsNothing() {
        LuaGate.resetForTest(); LuaGate.guard.on = false;
        assert !LuaPatches.gateEnter(null);                                         // must not touch LuaManager
    }

    static void gateShimRoutesWorker() {
        Lua l = new Lua("function f() end"); setUp(l);
        boolean[] took = new boolean[1];
        runWorkers(1, () -> { took[0] = LuaPatches.gateEnter(l.kt); if (took[0]) LuaPatches.gateExit(); });
        assert took[0] && LuaGate.gatedCalls == 1 && fakeGame == Thread.currentThread();   // the worker put the game thread back
        LuaGate.resetForTest();
    }

    // ---- Task 4: the replay buffer ----

    static void replayInLoopOrder() throws Exception {
        LuaReplay.clear();
        Thread[] t = new Thread[4];
        for (int k = 0; k < 4; k++) {
            int kk = k;
            t[k] = new Thread(() -> { for (int i = 396 + kk; i >= 0; i -= 4) LuaReplay.capture(i, new Object[] {"OnZombieUpdate", i}); });
            t[k].start();
        }
        for (Thread x : t) x.join(5000);
        java.util.List<Integer> got = new java.util.ArrayList<>();
        assert LuaReplay.replay(c -> got.add((Integer) c[1])) == 400;
        for (int i = 0; i < 400; i++) assert got.get(i) == i : i;
        assert LuaReplay.pending() == 0 && LuaReplay.replay(c -> { }) == 0;
    }

    static void sameOrderKeepsCaptureOrder() {
        LuaReplay.clear();
        LuaReplay.capture(5, new Object[] {"a"}); LuaReplay.capture(5, new Object[] {"b"}); LuaReplay.capture(2, new Object[] {"z"}); LuaReplay.capture(5, new Object[] {"c"});
        StringBuilder s = new StringBuilder();
        LuaReplay.replay(c -> s.append(c[0]));
        assert s.toString().equals("zabc");
    }

    static void throwingFireContinues() {
        LuaReplay.clear(); long f = LuaReplay.failures;
        LuaReplay.capture(1, new Object[] {"x"}); LuaReplay.capture(2, new Object[] {"y"});
        int[] n = {0};
        assert LuaReplay.replay(c -> { n[0]++; if (c[0].equals("x")) throw new RuntimeException(); }) == 2 && n[0] == 2 && LuaReplay.failures == f + 1;
    }

    static void replayKeepsOneThreadsTiesAcrossGrowth() {
        // 300 captures on one thread (the arrays grow past 64 and 128), orders repeating in runs, negative and large orders included
        LuaReplay.clear();
        for (int i = 0; i < 300; i++) LuaReplay.capture(i / 3 - 50, new Object[] {"e", i});
        LuaReplay.capture(Integer.MAX_VALUE, new Object[] {"e", -1});
        int[] n = {0};
        LuaReplay.replay(c -> {
            int seq = (Integer) c[1];
            if (seq >= 0) assert seq == n[0]++ : seq;                              // the captured sequence is already in order
            else assert n[0] == 300;                                                // MAX_VALUE last
        });
        assert n[0] == 300;
    }

    static void replayEmptiesAndPrunesEndedThreads() throws Exception {
        LuaReplay.clear();
        Thread t = new Thread(() -> LuaReplay.capture(1, new Object[] {"x"}));
        t.start(); t.join(5000);
        assert LuaReplay.pending() == 1 && LuaReplay.replay(c -> { }) == 1 && LuaReplay.pending() == 0;
        LuaReplay.clear();
        LuaReplay.capture(3, new Object[] {"y"});
        LuaReplay.clear();
        assert LuaReplay.pending() == 0 && LuaReplay.replay(c -> { assert false; }) == 0;
    }

    // ---- Task 5: held Lua file writes ----

    static final long MS = 1_000_000L;
    static java.io.File D;

    static void filesSetUp(boolean on) {
        D = SelfTest.tmp("luafiles/x").getParentFile();
        LuaFiles.resetForTest(); LuaFiles.guard.on = on; LuaFiles.cacheDir = () -> D.getPath();
    }

    /** The hook as the game runs it: held writer, or the stock write followed by the note. Returns whether the open was held. */
    static boolean open(String name, boolean append, String text, long now) throws Exception {
        Object w = LuaFiles.onOpen(name, true, append, now);
        if (w != null) {
            ((zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w).write(text);
            ((zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w).close();
            return true;
        }
        java.io.File f = LuaFiles.resolve(D.getPath(), name);
        f.getParentFile().mkdirs();
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, append)) { o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        LuaFiles.onStockOpened(name, append, new Object(), now);
        return false;
    }

    static String read(String name) throws Exception {
        java.io.File f = LuaFiles.resolve(D.getPath(), name);
        return f.exists() ? new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8) : "";
    }

    static void heartbeatNameRule() {
        for (String n : new String[] {"ping.txt", "MyModHeartbeat.txt", "server_pulse.json", "Pulse2.json", "temp_log.txt", "tmp-log.txt", "TempLog.txt", "templog.txt", "logs/heartbeat.txt"})
            assert LuaFiles.heartbeatName(n) : n;
        for (String n : new String[] {"mapping.txt", "shipping.log", "temperature.txt", "pingpong.txt", "status.json", "log.txt"})
            assert !LuaFiles.heartbeatName(n) : n;
    }

    static void keyAndPathRules() {
        assert LuaFiles.key("a\\B.txt", false).equals("a/B.txt") && LuaFiles.key("a\\B.txt", true).equals("a/b.txt");
        String s = java.io.File.separator;
        assert LuaFiles.resolve("/c", "a/b\\c.txt").getPath().equals(new java.io.File("/c" + s + "a" + s + "b" + s + "c.txt").getPath());
    }

    static void rewriteBurstLastWins() throws Exception {
        filesSetUp(true);
        assert !open("status.json", false, "v0", 0);
        for (int i = 1; i <= 4; i++) assert open("status.json", false, "v" + i, i * 100 * MS);
        LuaFiles.tick(450 * MS);
        assert read("status.json").equals("v0");
        LuaFiles.tick(600 * MS);
        assert read("status.json").equals("v4") && LuaFiles.diskWrites == 1;
        LuaFiles.resetForTest();
    }

    static void heartbeatAppendsKept() throws Exception {
        filesSetUp(true);
        assert !open("mod_heartbeat.txt", true, "a\n", 0);
        assert open("mod_heartbeat.txt", true, "b\n", 500 * MS) && open("mod_heartbeat.txt", true, "c\n", 1000 * MS);
        LuaFiles.tick(1900 * MS);
        assert read("mod_heartbeat.txt").equals("a\n");
        LuaFiles.tick(2100 * MS);
        assert read("mod_heartbeat.txt").equals("a\nb\nc\n");
        LuaFiles.resetForTest();
    }

    static void stockAppendFlushesHeldFirst() throws Exception {
        filesSetUp(true);
        open("x_ping.txt", true, "1", 0); open("x_ping.txt", true, "2", 100 * MS);   // second held
        assert !open("x_ping.txt", true, "3", 2500 * MS);                            // window over, no tick yet: held text first
        assert read("x_ping.txt").equals("123");
        LuaFiles.resetForTest();
    }

    static void stockRewriteSupersedesHeld() throws Exception {
        filesSetUp(true);
        open("s.json", false, "a", 0); assert open("s.json", false, "b", 100 * MS);
        assert !open("s.json", false, "c", 700 * MS);
        LuaFiles.tick(5000 * MS);
        assert read("s.json").equals("c");
        LuaFiles.resetForTest();
    }

    static void readBackFlushesAndExempts() throws Exception {
        filesSetUp(true);
        open("r.txt", false, "a", 0); assert open("r.txt", false, "b", 100 * MS);
        LuaFiles.beforeRead("r.txt");
        assert read("r.txt").equals("b") && !open("r.txt", false, "c", 200 * MS) && read("r.txt").equals("c");
        LuaFiles.resetForTest();
    }

    static void heldWriterIsStockClass() throws Exception {
        filesSetUp(true);
        open("w.txt", false, "", 0);
        Object w = LuaFiles.onOpen("w.txt", true, false, 10 * MS);
        assert w instanceof zombie.Lua.LuaManager.GlobalObject.LuaFileWriter;
        ((zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w).writeln("x"); ((zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w).close();
        LuaFiles.flushAll();
        assert read("w.txt").equals("x" + System.lineSeparator());
        LuaFiles.resetForTest();
    }

    static void fileThrottleOffPassesThrough() throws Exception {
        filesSetUp(false);
        open("o.json", false, "a", 0);
        assert !open("o.json", false, "b", 10 * MS) && read("o.json").equals("b");
        LuaFiles.resetForTest();
    }

    static void tickCountsAsAcceptedOpen() throws Exception {
        // a mod that keeps rewriting gets one disk write per window, not one per window plus one per burst
        filesSetUp(true);
        open("k.json", false, "v0", 0);
        assert open("k.json", false, "a", 100 * MS) && open("k.json", false, "b", 300 * MS);
        LuaFiles.tick(500 * MS);
        assert read("k.json").equals("b") && LuaFiles.diskWrites == 1;
        assert open("k.json", false, "c", 600 * MS);                                // 100 ms after the tick's write: held
        LuaFiles.tick(900 * MS);
        assert read("k.json").equals("b");
        LuaFiles.tick(1000 * MS);
        assert read("k.json").equals("c") && LuaFiles.diskWrites == 2;
        LuaFiles.resetForTest();
    }

    static void createFalseHeldOnlyWithPendingText() throws Exception {
        // create=false is held only to extend text we already hold (stock's FileOutputStream would create the file anyway; running stock is the safe default)
        filesSetUp(true);
        open("c.json", false, "a", 0);
        assert LuaFiles.onOpen("c.json", false, false, 100 * MS) == null;
        assert open("c.json", false, "b", 150 * MS);
        assert LuaFiles.onOpen("c.json", false, false, 200 * MS) != null;           // held text pending: the file exists
        LuaFiles.resetForTest();
    }

    static void unknownNameAndSizeCapPassThrough() throws Exception {
        filesSetUp(true);
        assert LuaFiles.onOpen("never.txt", true, false, 0) == null && LuaFiles.heldOpens == 0;   // stock has not accepted it yet
        open("big.json", false, "", 0);
        Object w = LuaFiles.onOpen("big.json", true, false, 10 * MS);
        ((zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w).write("x".repeat(LuaFiles.MAX_HELD_CHARS));
        assert LuaFiles.onOpen("big.json", true, false, 20 * MS) == null;           // over the cap: stock, held text dropped (stock truncates)
        LuaFiles.resetForTest();
    }

    /** The cap bounds the memory of one open writer too: past it the held text is written and the writer carries on. */
    static void heldTextCapFlushesEarly() throws Exception {
        filesSetUp(true);
        open("cap.json", false, "", 0);
        var w = (zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) LuaFiles.onOpen("cap.json", true, false, 10 * MS);
        w.write("a".repeat(LuaFiles.MAX_HELD_CHARS));
        assert read("cap.json").equals("") && LuaFiles.diskWrites == 0;                // exactly at the cap: still held
        w.write("bb");
        assert read("cap.json").equals("a".repeat(LuaFiles.MAX_HELD_CHARS)) && LuaFiles.diskWrites == 1;
        w.close();
        LuaFiles.flushAll();
        assert read("cap.json").equals("a".repeat(LuaFiles.MAX_HELD_CHARS) + "bb");
        LuaFiles.resetForTest();
    }

    /** Reading a name nobody wrote makes no entry: a read-heavy mod cannot fill the table and stop later writers being held. */
    static void readsOfUnwrittenNamesMakeNoEntries() throws Exception {
        filesSetUp(true);
        for (int i = 0; i < 20_000; i++) LuaFiles.beforeRead("r" + i + ".txt");
        assert LuaFiles.entryCount() == 0;
        open("late.json", false, "a", 0);
        assert open("late.json", false, "b", 10 * MS);                                  // still held
        LuaFiles.resetForTest();
    }

    static void windowsKeysFoldCase() {
        assert LuaFiles.key("Dir\\File.TXT", true).equals("dir/file.txt");
        assert LuaFiles.key("Dir/File.TXT", false).equals("Dir/File.TXT");
    }

    static void fileKeyOffByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        LuaFiles.init();
        assert !LuaFiles.guard.on;
        LuaFiles.resetForTest();
    }

    // ---- Task 6: file hooks ----

    static void fileHookShimsStockWhenOff() {
        LuaFiles.resetForTest(); LuaFiles.guard.on = false;
        assert LuaPatches.onFileWriter("a.txt", true, false) == null;
        LuaPatches.onFileWriterOpened("a.txt", false, null); LuaPatches.onFileRead("a.txt"); LuaPatches.onFrameEndFiles();   // no throw, no state
    }

    static void fileHoldersTargetStock() {
        java.util.Set<String> want = java.util.Set.of("getFileWriter", "getFileReader", "getFileInput", "getFileOutput"), seen = new java.util.HashSet<>();
        for (Class<?> c : LuaPatches.class.getDeclaredClasses()) {
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            if (p != null && p.className().equals("zombie.Lua.LuaManager$GlobalObject")) seen.add(p.methodName());
        }
        assert seen.equals(want) : seen;
    }

    static void fileHoldersShape() {
        // the writer holder skips the stock body on a held writer; the three readers only note the name
        for (Class<?> c : LuaPatches.class.getDeclaredClasses()) {
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            if (p == null || !p.className().equals("zombie.Lua.LuaManager$GlobalObject")) continue;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                me.zed_0xff.zombie_buddy.Patch.OnEnter e = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnEnter.class);
                if (e != null) assert e.skipOn() == p.methodName().equals("getFileWriter") : p.methodName();
            }
        }
    }

    // ---- Task 7: logDedupe core ----

    static final Object S1 = new Object(), S2 = new Object();
    static final Object[] NO_ARGS = new Object[0];

    static void firstPrintedRepeatsDropped() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true; long t = 1_000_000_000L;
        assert !LogDedupe.drop(S1, 1, "spam", NO_ARGS, t);
        for (int i = 1; i <= 99; i++) assert LogDedupe.drop(S1, 1, "spam", NO_ARGS, t + i);
        assert !LogDedupe.drop(S1, 1, "other", NO_ARGS, t + 1);
        assert LogDedupe.roll(t + 2_999_999_999L).isEmpty();
        java.util.List<Object[]> s = LogDedupe.roll(t + 3_000_000_000L);
        assert s.size() == 1 && s.get(0)[0] == S1 && s.get(0)[1].equals("[PZButter] logDedupe: 99 more in 3 s: spam");
        assert LogDedupe.roll(t + 3_000_000_000L).isEmpty();                         // one winner
        assert !LogDedupe.drop(S1, 1, "spam", NO_ARGS, t + 3_000_000_001L);          // new window: printed once again
        LogDedupe.resetForTest();
    }

    static void argsLinesNeverDropped() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        assert !LogDedupe.drop(S1, 1, "x=%s", new Object[] {1}, 0) && !LogDedupe.drop(S1, 1, "x=%s", new Object[] {1}, 1);
        assert !LogDedupe.drop(S1, 1, new StringBuilder("sb"), NO_ARGS, 2) && !LogDedupe.drop(S1, 1, new StringBuilder("sb"), NO_ARGS, 3);
        assert !LogDedupe.drop(S1, 1, null, NO_ARGS, 4) && !LogDedupe.drop(S1, 1, null, NO_ARGS, 5);
        assert !LogDedupe.drop(S1, 1, "n", null, 6) && LogDedupe.drop(S1, 1, "n", null, 7);   // a null array is no arguments
        LogDedupe.resetForTest();
    }

    static void otherStreamOrSeverityPrinted() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        assert !LogDedupe.drop(S1, 1, "x", NO_ARGS, 0) && !LogDedupe.drop(S2, 1, "x", NO_ARGS, 1) && !LogDedupe.drop(S1, 2, "x", NO_ARGS, 2);
        assert LogDedupe.drop(S1, 1, "x", NO_ARGS, 3);
        LogDedupe.resetForTest();
    }

    static void summaryPassesThrough() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        String s = LogDedupe.summary("x".repeat(300), 5);
        assert s.length() == "[PZButter] logDedupe: 5 more in 3 s: ".length() + 200;
        assert !LogDedupe.drop(S1, 1, s, NO_ARGS, 0) && !LogDedupe.drop(S1, 1, s, NO_ARGS, 1);
        LogDedupe.resetForTest();
    }

    static void keyCapPassesThrough() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        for (int i = 0; i < LogDedupe.MAX_KEYS; i++) assert !LogDedupe.drop(S1, 1, "k" + i, NO_ARGS, 0);
        assert !LogDedupe.drop(S1, 1, "new", NO_ARGS, 0) && !LogDedupe.drop(S1, 1, "new", NO_ARGS, 0);
        assert LogDedupe.drop(S1, 1, "k7", NO_ARGS, 0);
        LogDedupe.resetForTest();
    }

    static void concurrentCountsExact() throws Exception {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        java.util.concurrent.atomic.AtomicInteger printed = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] t = new Thread[8];
        for (int k = 0; k < 8; k++) (t[k] = new Thread(() -> { for (int i = 0; i < 10_000; i++) if (!LogDedupe.drop(S1, 1, "same", NO_ARGS, 5)) printed.incrementAndGet(); })).start();
        for (Thread x : t) { x.join(10_000); assert !x.isAlive(); }
        assert printed.get() == 1;
        assert LogDedupe.roll(5 + LogDedupe.WINDOW_NS).get(0)[1].equals("[PZButter] logDedupe: 79999 more in 3 s: same");
        LogDedupe.resetForTest();
    }

    static void dedupeOffDropsNothing() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = false;
        assert !LogDedupe.drop(S1, 1, "a", NO_ARGS, 0) && !LogDedupe.drop(S1, 1, "a", NO_ARGS, 1);
        assert !LogDedupe.due(Long.MAX_VALUE / 2) && LogDedupe.roll(Long.MAX_VALUE / 2).isEmpty();
        LogDedupe.resetForTest();
    }

    static void dedupeKeyOffByDefault() {
        Settings.load(SelfTest.write("enabled=true\n"));
        LogDedupe.init();
        assert !LogDedupe.guard.on;
        Settings.load(SelfTest.write("enabled=true\nlogDedupe=true\n"));
        LogDedupe.init();
        assert LogDedupe.guard.on;
        Settings.load(SelfTest.write("enabled=true\n"));
        LogDedupe.init();
        LogDedupe.resetForTest();
    }

    // summaries leave in the order their messages were first seen, whatever the table's own order
    static void summariesInFirstSeenOrder() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        for (int i = 0; i < 50; i++) { String m = "m" + (49 - i); assert !LogDedupe.drop(S1, 1, m, NO_ARGS, 0); assert LogDedupe.drop(S1, 1, m, NO_ARGS, 1); }
        java.util.List<Object[]> s = LogDedupe.roll(LogDedupe.WINDOW_NS);
        assert s.size() == 50;
        for (int i = 0; i < 50; i++) assert s.get(i)[1].equals("[PZButter] logDedupe: 1 more in 3 s: m" + (49 - i)) : i;
        LogDedupe.resetForTest();
    }

    // a repeat that reaches a message after its window was summarised is printed (never counted into a summary already gone)
    static void lateRepeatAfterRollPrints() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        assert !LogDedupe.drop(S1, 1, "late", NO_ARGS, 0) && LogDedupe.drop(S1, 1, "late", NO_ARGS, 1);
        LogDedupe.Window old = LogDedupe.window();                                    // a thread that read the window before the roll
        assert LogDedupe.roll(LogDedupe.WINDOW_NS).size() == 1;
        assert !LogDedupe.dropIn(old, S1, 1, "late");                      // closed record: printed, in no summary
        assert LogDedupe.roll(2 * LogDedupe.WINDOW_NS).isEmpty();
        LogDedupe.resetForTest();
    }

    // every dropped line is in exactly one summary, while a thread keeps rolling the window
    static void concurrentRollLosesNoCount() throws Exception {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong(), summed = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread[] t = new Thread[4];
        long[] clock = {0};
        for (int k = 0; k < 4; k++) (t[k] = new Thread(() -> {
            for (int i = 0; i < 50_000; i++) if (LogDedupe.drop(S1, 1, "hot" + (i & 3), NO_ARGS, clock[0])) dropped.incrementAndGet();
        })).start();
        Thread roller = new Thread(() -> {
            long now = 0;
            while (!stop.get()) { now += LogDedupe.WINDOW_NS; for (Object[] r : LogDedupe.roll(now)) summed.addAndGet(countOf((String) r[1])); Thread.onSpinWait(); }
        });
        roller.start();
        for (Thread x : t) { x.join(20_000); assert !x.isAlive(); }
        stop.set(true); roller.join(5000);
        for (Object[] r : LogDedupe.roll(Long.MAX_VALUE / 2)) summed.addAndGet(countOf((String) r[1]));
        assert dropped.get() == summed.get() : dropped.get() + " dropped, " + summed.get() + " summarised";
        LogDedupe.resetForTest();
    }

    static long countOf(String summary) {
        String rest = summary.substring("[PZButter] logDedupe: ".length());
        return Long.parseLong(rest.substring(0, rest.indexOf(' ')));
    }

    // ---- Task 8: log hooks ----

    static void logShimPrintsSummaryOnStream() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(bytes, true);
        assert !LogPatches.onLine(ps, null, "tick", NO_ARGS, true) && LogPatches.onLine(ps, null, "tick", NO_ARGS, true);
        LogDedupe.resetWindowStartForTest(System.nanoTime() - LogDedupe.WINDOW_NS);   // window over
        LogPatches.onFrameEnd();
        assert bytes.toString().contains("[PZButter] logDedupe: 1 more in 3 s: tick");
        LogDedupe.resetForTest();
    }

    static void logShimOffAndErrors() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = false;
        assert !LogPatches.onLine(S1, null, "a", NO_ARGS, true) && !LogPatches.onLine(S1, null, "a", NO_ARGS, true);
        LogPatches.onFrameEnd();
        // a stream that throws on the summary: the line path stays correct and the failure goes to the guard
        LogDedupe.guard.on = true;
        java.io.PrintStream bad = new java.io.PrintStream(new java.io.ByteArrayOutputStream()) {
            @Override public void println(String x) { throw new IllegalStateException("closed"); }
        };
        assert !LogPatches.onLine(bad, null, "b", NO_ARGS, true) && LogPatches.onLine(bad, null, "b", NO_ARGS, true);
        LogDedupe.resetWindowStartForTest(System.nanoTime() - LogDedupe.WINDOW_NS);
        LogPatches.onFrameEnd();                                                      // must not throw
        assert LogDedupe.guard.on;                                                    // one failure is not five
        assert !LogPatches.onLine("not a stream", null, "c", NO_ARGS, true);                // first sight never touches the stream
        LogDedupe.resetForTest();
    }

    // a line below the stream's level is never printed by stock: not recorded, not counted, never summarised
    static void logShimSkipsFilteredLines() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        java.io.PrintStream ps = new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true);
        zombie.debug.DebugLogStream errOnly = new zombie.debug.DebugLogStream(ps, ps, ps, zombie.debug.LogSeverity.Error);
        for (int i = 0; i < 3; i++) assert !LogPatches.onLine(errOnly, zombie.debug.LogSeverity.Warning, "quiet", NO_ARGS, true);
        assert LogDedupe.roll(LogDedupe.WINDOW_NS).isEmpty() && !LogDedupe.due(Long.MAX_VALUE / 2);
        assert !LogPatches.onLine(errOnly, zombie.debug.LogSeverity.Error, "loud", NO_ARGS, true) && LogPatches.onLine(errOnly, zombie.debug.LogSeverity.Error, "loud", NO_ARGS, true);
        LogDedupe.resetForTest();
    }

    // warnOnce / debugOnceln pass argument 3 = false: stock prints them once per JVM, so a repeat is never ours to count or summarise
    static void onceLinesPassThroughUntouched() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        for (int i = 0; i < 5; i++) assert !LogPatches.onLine(S1, null, "once", NO_ARGS, false);
        assert LogDedupe.window() == null && !LogDedupe.due(Long.MAX_VALUE / 2) && LogDedupe.roll(Long.MAX_VALUE / 2).isEmpty();
        assert !LogPatches.onLine(S1, null, "once", NO_ARGS, true) && LogPatches.onLine(S1, null, "once", NO_ARGS, true);   // the same text as a repeatable line counts
        assert !LogPatches.onLine(S1, null, "other", NO_ARGS, false) && !LogPatches.onLine(S1, null, "other", NO_ARGS, false);
        assert LogDedupe.roll(Long.MAX_VALUE / 2).size() == 1;
        LogDedupe.resetForTest();
    }

    // a stream whose level is above General would filter a General summary: it leaves at the first line's severity, on that severity's print stream
    static void summaryKeepsSeverityAndStream() throws Exception {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        java.io.PrintStream po = new java.io.PrintStream(new java.io.ByteArrayOutputStream()), pw = new java.io.PrintStream(new java.io.ByteArrayOutputStream()),
            pe = new java.io.PrintStream(new java.io.ByteArrayOutputStream());
        zombie.debug.DebugLogStream d = new zombie.debug.DebugLogStream(po, pw, pe, zombie.debug.LogSeverity.Warning);
        assert !d.isLogEnabled(zombie.debug.LogSeverity.General) && LogPatches.writelnBound();   // the protected writeln of the game is found
        zombie.debug.LogSeverity w = zombie.debug.LogSeverity.Warning, e = zombie.debug.LogSeverity.Error;
        assert !LogPatches.onLine(d, w, "w-spam", NO_ARGS, true) && LogPatches.onLine(d, w, "w-spam", NO_ARGS, true) && LogPatches.onLine(d, w, "w-spam", NO_ARGS, true);
        assert !LogPatches.onLine(d, e, "e-spam", NO_ARGS, true) && LogPatches.onLine(d, e, "e-spam", NO_ARGS, true);
        java.util.List<Object[]> got = new java.util.ArrayList<>();
        LogPatches.Sink real = LogPatches.sink;
        LogPatches.sink = (st, ps, v, text) -> got.add(new Object[] {st, ps, v, text});
        try {
            LogDedupe.resetWindowStartForTest(System.nanoTime() - LogDedupe.WINDOW_NS);
            LogPatches.onFrameEnd();
        } finally { LogPatches.sink = real; }
        assert got.size() == 2 && got.get(0)[0] == d && got.get(0)[1] == pw && got.get(0)[2] == w && got.get(0)[3].equals("[PZButter] logDedupe: 2 more in 3 s: w-spam") : got;
        assert got.get(1)[1] == pe && got.get(1)[2] == e && got.get(1)[3].equals("[PZButter] logDedupe: 1 more in 3 s: e-spam");
        LogDedupe.resetForTest();
    }

    // repeats are only counted by the line hook: the summaries leave from the frame end, never from a logging thread
    static void onLineNeverPrintsSummaries() {
        LogDedupe.resetForTest(); LogDedupe.guard.on = true;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(bytes, true);
        assert !LogPatches.onLine(ps, null, "m", NO_ARGS, true) && LogPatches.onLine(ps, null, "m", NO_ARGS, true);
        LogDedupe.resetWindowStartForTest(System.nanoTime() - LogDedupe.WINDOW_NS);
        assert LogPatches.onLine(ps, null, "m", NO_ARGS, true) && bytes.size() == 0;   // window over, still no summary from the line path
        LogPatches.onFrameEnd();
        assert bytes.toString().contains("2 more in 3 s: m");
        LogDedupe.resetForTest();
    }

    static void logHoldersShape() {
        java.util.Map<String, Integer> want = java.util.Map.of("writeln", 1, "writeWithCallerPrefixln", 1), seen = new java.util.HashMap<>();
        for (Class<?> c : LogPatches.class.getDeclaredClasses()) {
            me.zed_0xff.zombie_buddy.Patch p = c.getAnnotation(me.zed_0xff.zombie_buddy.Patch.class);
            if (p == null) continue;
            assert p.className().equals("zombie.debug.DebugLogStream") : p.className();
            seen.merge(p.methodName(), 1, Integer::sum);
            int enters = 0;
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                me.zed_0xff.zombie_buddy.Patch.OnEnter e = m.getAnnotation(me.zed_0xff.zombie_buddy.Patch.OnEnter.class);
                if (e == null) continue;
                enters++;
                assert e.skipOn() && m.getReturnType() == boolean.class : p.methodName();
                // the LogSeverity binding of argument 1 is what selects the protected overload
                java.lang.annotation.Annotation[][] as = m.getParameterAnnotations();
                boolean sev = false;
                for (int i = 0; i < as.length; i++)
                    for (java.lang.annotation.Annotation a : as[i])
                        if (a instanceof me.zed_0xff.zombie_buddy.Patch.Argument arg && arg.value() == 1) sev = m.getParameterTypes()[i] == zombie.debug.LogSeverity.class;
                assert sev : p.methodName();
                // writeWithCallerPrefixln also binds argument 3 (false = a once-line, which passes through untouched)
                boolean once = false;
                for (int i = 0; i < as.length; i++)
                    for (java.lang.annotation.Annotation a : as[i])
                        if (a instanceof me.zed_0xff.zombie_buddy.Patch.Argument arg && arg.value() == 3) once = m.getParameterTypes()[i] == boolean.class;
                assert once == p.methodName().equals("writeWithCallerPrefixln") : p.methodName();
            }
            assert enters == 1 : p.methodName();
        }
        assert seen.equals(want) : seen;
    }

    // ---- Task 9: mod scan, profile, forced-off answers ----

    static byte[] classBytes(Class<?> c) throws Exception {
        try (java.io.InputStream in = c.getResourceAsStream(c.getName().substring(c.getName().lastIndexOf('.') + 1) + ".class")) { return in.readAllBytes(); }
    }

    static void scanFindsPatchAnnotations() throws Exception {
        java.nio.file.Path jar = SelfTest.tmp("modcompat/other.jar").toPath();
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (Class<?> c : new Class<?>[] {LuaPatches.Call.class, LuaPatches.Trigger.class, LuaFast.class}) {
                out.putNextEntry(new java.util.jar.JarEntry(c.getName().replace('.', '/') + ".class")); out.write(classBytes(c)); out.closeEntry();
            }
            out.putNextEntry(new java.util.jar.JarEntry("broken/Bad.class")); out.write(new byte[] {1, 2, 3}); out.closeEntry();   // skipped, not fatal
            out.putNextEntry(new java.util.jar.JarEntry("readme.txt")); out.write("x".getBytes()); out.closeEntry();
        }
        java.util.List<ModCompat.Target> t = ModCompat.scanJar(jar);
        assert t.size() == 2 : t;                                                   // LuaFast has no @Patch
        assert t.contains(new ModCompat.Target("se.krka.kahlua.integration.LuaCaller", "protectedCallVoid", true, "other.jar"));
        assert t.contains(new ModCompat.Target("zombie.Lua.Event", "trigger", false, "other.jar"));
    }

    @me.zed_0xff.zombie_buddy.Patch(className = "a.B", methodName = "m")
    static class ThisRw { @me.zed_0xff.zombie_buddy.Patch.OnEnter public static void e(@me.zed_0xff.zombie_buddy.Patch.This(readOnly = false) Object self) { } }
    @me.zed_0xff.zombie_buddy.Patch(className = "a.B", methodName = "m")
    static class AllArgsRw { @me.zed_0xff.zombie_buddy.Patch.OnEnter public static void e(@me.zed_0xff.zombie_buddy.Patch.AllArguments(readOnly = false) Object[] all) { } }
    @me.zed_0xff.zombie_buddy.Patch(className = "a.B", methodName = "m")
    static class ThisRo { @me.zed_0xff.zombie_buddy.Patch.OnEnter public static void e(@me.zed_0xff.zombie_buddy.Patch.This Object self) { } }
    @me.zed_0xff.zombie_buddy.Patch(className = "a.B", methodName = "m")
    static class AllArgsRo { @me.zed_0xff.zombie_buddy.Patch.OnEnter public static void e(@me.zed_0xff.zombie_buddy.Patch.AllArguments Object[] all) { } }
    @me.zed_0xff.zombie_buddy.Patch(className = "a.B", methodName = "m")
    static class ThrownRw { @me.zed_0xff.zombie_buddy.Patch.OnExit(onThrowable = Throwable.class) public static void e(@me.zed_0xff.zombie_buddy.Patch.Thrown(readOnly = false) Throwable t) { } }
    @me.zed_0xff.zombie_buddy.Patch(className = "a.B", methodName = "m")
    static class ThrownRo { @me.zed_0xff.zombie_buddy.Patch.OnExit(onThrowable = Throwable.class) public static void e(@me.zed_0xff.zombie_buddy.Patch.Thrown Throwable t) { } }

    /** The four ways a @Patch replaces stock, and the ones that do not; the last is a class built here (isAdvice = false). */
    static void scanReadsEveryReplacingForm() throws Exception {
        java.util.function.Function<Class<?>, Boolean> replaces = c -> {
            try {
                java.util.List<ModCompat.Target> t = ModCompat.scanClass(classBytes(c), "x");
                assert t.size() == 1 : c;
                return t.get(0).replaces();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        assert replaces.apply(LuaPatches.Call.class);                               // OnEnter(skipOn = true)
        assert replaces.apply(DeliveryPatches.RightDelay.class);                    // Return(readOnly = false), no skipOn
        assert !replaces.apply(DeliveryPatches.Step.class);                         // OnEnter only
        assert !replaces.apply(LuaPatches.Rawset.class);                            // Argument and This with the default readOnly
        assert !replaces.apply(DeliveryPatches.Acquire.class);                      // Return with the default readOnly
        assert replaces.apply(ThisRw.class) && replaces.apply(AllArgsRw.class);     // This / AllArguments with readOnly = false
        assert !replaces.apply(ThisRo.class) && !replaces.apply(AllArgsRo.class);
        assert replaces.apply(ThrownRw.class) && !replaces.apply(ThrownRo.class);   // Thrown(readOnly = false) can swallow or swap stock's exception
        java.lang.constant.ClassDesc patch = java.lang.constant.ClassDesc.of("me.zed_0xff.zombie_buddy.Patch");
        java.util.function.Function<Boolean, byte[]> fake = isAdvice -> java.lang.classfile.ClassFile.of().build(java.lang.constant.ClassDesc.of("x.Fake"),
            cb -> cb.with(java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute.of(java.lang.classfile.Annotation.of(patch,
                java.lang.classfile.AnnotationElement.ofString("className", "a.B"), java.lang.classfile.AnnotationElement.ofString("methodName", "m"),
                java.lang.classfile.AnnotationElement.ofBoolean("isAdvice", isAdvice)))));
        assert ModCompat.scanClass(fake.apply(false), "f").equals(java.util.List.of(new ModCompat.Target("a.B", "m", true, "f")));
        assert ModCompat.scanClass(fake.apply(true), "f").equals(java.util.List.of(new ModCompat.Target("a.B", "m", false, "f")));
        assert ModCompat.scanClass(classBytes(LuaFast.class), "f").isEmpty() && ModCompat.scanClass(new byte[] {1, 2}, "f").isEmpty();
    }

    static void nameMatchesZbRules() {
        assert ModCompat.nameMatches("update", "update") && !ModCompat.nameMatches("update", "updateX");
        assert ModCompat.nameMatches("*", "anything") && ModCompat.nameMatches("<init>", "<init>") && !ModCompat.nameMatches("<init>", "init");
        assert ModCompat.nameMatches("/get.*Writer/", "getFileWriter") && !ModCompat.nameMatches("/get.*Writer/", "getFileWriterX");
        assert ModCompat.nameMatches("*Writer", "getFileWriter") && ModCompat.nameMatches("get*", "getFileWriter") && ModCompat.nameMatches("*File*", "getFileWriter");
        assert !ModCompat.nameMatches("*Reader", "getFileWriter") && !ModCompat.nameMatches("set*", "getFileWriter") && !ModCompat.nameMatches("*Nope*", "getFileWriter");
        assert !ModCompat.nameMatches("/(/", "anything");                          // a bad regex matches nothing
    }

    static void conflictOnlyWhenOneReplaces() {
        ModCompat.Target ourSkip = new ModCompat.Target("a.B", "m", true, "LuaPatches.Call"), ourObs = new ModCompat.Target("a.B", "m", false, "LuaPatches.Trigger");
        ModCompat.Target theirObs = new ModCompat.Target("a.B", "m", false, "x.jar"), theirSkip = new ModCompat.Target("a.B", "*", true, "y.jar");
        assert ModCompat.conflicts(java.util.List.of(ourObs), java.util.List.of(theirObs)).isEmpty();
        assert ModCompat.conflicts(java.util.List.of(ourSkip), java.util.List.of(theirObs)).get(0)[3].equals("ours");
        assert ModCompat.conflicts(java.util.List.of(ourObs), java.util.List.of(theirSkip)).get(0)[3].equals("theirs");
        assert ModCompat.conflicts(java.util.List.of(ourSkip), java.util.List.of(theirSkip)).get(0)[3].equals("both");
        assert ModCompat.conflicts(java.util.List.of(ourSkip), java.util.List.of(new ModCompat.Target("a.C", "m", true, "z.jar"))).isEmpty();
        assert ModCompat.conflicts(java.util.List.of(ourSkip), java.util.List.of(new ModCompat.Target("a.B", "other", true, "z.jar"))).isEmpty();
        String[] c = ModCompat.conflicts(java.util.List.of(ourSkip), java.util.List.of(theirObs)).get(0);
        assert c[0].equals("LuaPatches.Call") && c[1].equals("a.B.m") && c[2].equals("x.jar");
    }

    static void profileSetsTheTwoKeys() {
        Settings.load(SelfTest.write("enabled=true\nmodProfile=compatibility\nmodCompat=report\nuiRetainedMods=true\n"));
        assert ModCompat.mode().equals("auto") && !Settings.flag("uiRetainedMods");
        Settings.load(SelfTest.write("enabled=true\nmodProfile=performance\nmodCompat=off\nuiRetainedMods=false\n"));
        assert ModCompat.mode().equals("report") && Settings.flag("uiRetainedMods");
        Settings.load(SelfTest.write("enabled=true\nmodProfile=custom\nmodCompat=auto\nuiRetainedMods=false\n"));
        assert ModCompat.mode().equals("auto") && !Settings.flag("uiRetainedMods");
        Settings.load(SelfTest.write("enabled=true\nmodProfile=Performance\n"));                // case and spaces do not matter, a missing line is the profile's
        assert ModCompat.mode().equals("report") && Settings.flag("uiRetainedMods");
        Settings.load(SelfTest.write("enabled=true\nmodProfile=nonsense\nmodCompat=report\n"));   // an unknown profile is custom
        assert ModCompat.mode().equals("report");
        Settings.load(SelfTest.write("enabled=true\nmodCompat=bogus\n"));
        assert ModCompat.mode().equals("off");
        Settings.load(SelfTest.write("enabled=true\n"));                                          // shipped defaults: no scan
        assert ModCompat.mode().equals("off");
        Settings.load(SelfTest.write("enabled=false\nmodProfile=performance\n"));
        assert ModCompat.mode().equals("off");
    }

    static void forcedOffAnswersStock() {
        Settings.load(SelfTest.write("enabled=true\nlimiterSleep=true\nsoundTickHz=60\npresentPacing=on\nzoomEase=0.1,0.2,0.3,0.4\n"));
        assert Settings.flag("limiterSleep") && Settings.num("soundTickHz", 0, 0, 1000) == 60 && Settings.mode("presentPacing").equals("on");
        Settings.forcedOff.addAll(java.util.Set.of("limiterSleep", "soundTickHz", "presentPacing", "zoomEase"));
        assert !Settings.flag("limiterSleep") && Settings.num("soundTickHz", 0, 0, 1000) == 0 && Settings.mode("presentPacing").equals("off");
        assert Settings.text("zoomEase", "0.25,0.1,0.25,1.0").equals("0.25,0.1,0.25,1.0");
        assert Settings.flag("luaBudget");                                         // keys not forced answer as before
        Settings.load(SelfTest.write("enabled=true\nlimiterSleep=true\n"));
        assert Settings.forcedOff.isEmpty() && Settings.flag("limiterSleep");
    }

    /** A switch whose stock value is true answers true when forced off (false would be the deviation), and still false without the master switch. */
    static void forcedOffStockTrueKeys() {
        Settings.load(SelfTest.write("enabled=true\ndriveTreeCutaway=false\nglassTilesPerFrame=false\ntranslucentLightsPerFrame=false\n"));
        assert !Settings.flag("driveTreeCutaway") && !Settings.flag("glassTilesPerFrame");
        Settings.forcedOff.addAll(java.util.Set.of("driveTreeCutaway", "glassTilesPerFrame", "translucentLightsPerFrame"));
        assert Settings.flag("driveTreeCutaway") && Settings.flag("glassTilesPerFrame") && Settings.flag("translucentLightsPerFrame");
        Settings.load(SelfTest.write("enabled=false\n"));
        Settings.forcedOff.add("driveTreeCutaway");
        assert !Settings.flag("driveTreeCutaway");
        Settings.load(SelfTest.write("enabled=true\n"));
    }

    static void otherJarsNeverThrows() {
        java.util.List<java.nio.file.Path> j = ModCompat.otherJars();                // headless: ZB's map is empty or missing
        assert j != null && j.isEmpty();
    }

    /** The JavaModLoadState records ZombieBuddy keeps: loaded ones count, refused or undecided ones and our own jar do not. */
    static void jarsFromKeepsLoadedAndDropsOwn() throws Exception {
        Class<?> st = Class.forName("me.zed_0xff.zombie_buddy.Loader$JavaModLoadState");
        java.lang.reflect.Constructor<?> ctor = st.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        java.nio.file.Path a = java.nio.file.Path.of("/mods/a.jar"), b = java.nio.file.Path.of("/mods/b.jar"), c = java.nio.file.Path.of("/mods/c.jar"),
            own = java.nio.file.Path.of("/mods/pzbutter.jar");
        java.util.Map<java.nio.file.Path, Object> m = new java.util.LinkedHashMap<>();
        m.put(a, ctor.newInstance("a", a, null, "loaded", "h", Boolean.TRUE));
        m.put(b, ctor.newInstance("b", b, null, "denied", "h", Boolean.FALSE));
        m.put(c, ctor.newInstance("c", c, null, "pending", "h", null));
        m.put(own, ctor.newInstance("pzbutter", own, null, "loaded", "h", Boolean.TRUE));
        try {
            assert ModCompat.jarsFrom(m, own).equals(java.util.List.of(a));
            assert ModCompat.jarsFrom(java.util.Map.of(), own).isEmpty();
        } catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new AssertionError(t); }
    }

    // ---- Task 10: holder keys table, report and auto ----

    static void keysTableCoversEveryHolder() throws Exception {
        String template = Settings.render(new java.util.Properties());
        java.util.Set<String> holders = new java.util.HashSet<>();
        for (Class<?> outer : ModCompat.OUTERS)
            for (Class<?> c : outer.getDeclaredClasses())
                if (c.isAnnotationPresent(me.zed_0xff.zombie_buddy.Patch.class)) holders.add(outer.getSimpleName() + "." + c.getSimpleName());
        assert holders.equals(ModCompat.KEYS.keySet()) : "missing or stale: " + holders + " vs " + ModCompat.KEYS.keySet();
        for (java.util.Map.Entry<String, String[]> e : ModCompat.KEYS.entrySet()) {
            assert e.getValue().length > 0 : e.getKey();
            for (String k : e.getValue()) assert !k.equals("enabled") && !k.equals("frameLog") && template.contains("\n" + k + "=") : e.getKey() + " " + k;
        }
        // every PZ Butter Plan 7 holder names its own key
        assert java.util.Arrays.asList(ModCompat.KEYS.get("LuaPatches.FileWriter")).equals(java.util.List.of("luaFileWriteThrottle"));
        assert java.util.Arrays.asList(ModCompat.KEYS.get("LogPatches.Writeln")).equals(java.util.List.of("logDedupe"));
        assert java.util.Arrays.asList(ModCompat.KEYS.get("LuaPatches.TriggerHook")).equals(java.util.List.of("luaWorkerGate"));
    }

    /** Keys of the table that are numbers or modes (not switches); every one needs its stock answer checked below. */
    static final java.util.Set<String> VALUE_KEYS = java.util.Set.of("aimHoldMs", "bakeMipLevels", "borderlessFullscreen", "cutawayRadius", "gpuMaxFrames",
        "gridStackInterval", "lightSwitchCheckFrames", "lightingFps", "presentPacing", "roofHideDebounceFrames", "soundTickHz", "treeBakeMaxChunksPerSec", "vrr", "zoomEaseMs",
        "numberZombiesBlended", "zombieLodFixed3d", "zombieModelAddBudgetUs", "zombieSimLodTiles", "zombieSimLodSteps", "vboBatchKb", "vboSizeClasses");

    /**
     * Forced off answers stock for every key of the table: a switch is false (true for the stock-true ones), and each number or mode key
     * leaves its feature at the stock value (aimHoldMs 150, the rest 0 or off) even when the file says otherwise.
     */
    static void forcedKeysAnswerStock() {
        Settings.load(SelfTest.write("enabled=true\n"));
        String template = Settings.render(new java.util.Properties());
        java.util.Set<String> all = new java.util.TreeSet<>(), values = new java.util.TreeSet<>();
        for (String[] ks : ModCompat.KEYS.values()) all.addAll(java.util.Arrays.asList(ks));
        for (String k : all) {
            int i = template.indexOf("\n" + k + "=");
            String v = template.substring(i + k.length() + 2, template.indexOf('\n', i + 1));
            if (!v.equals("true") && !v.equals("false")) values.add(k);
        }
        assert values.equals(VALUE_KEYS) : "a number or mode key joined or left the table, add its stock check: " + values;
        Settings.forcedOff.addAll(all);
        for (String k : all) if (!VALUE_KEYS.contains(k)) assert !Settings.flag(k) == !Settings.STOCK_ON.contains(k) : k;
        // the numbers and modes, through the features' own init, with values in the file that are not stock
        Settings.load(SelfTest.write("enabled=true\naimHoldMs=999\ncutawayRadius=9\ngridStackInterval=9\nroofHideDebounceFrames=9\nzoomEaseMs=9\n"
            + "bakeMipLevels=9\ntreeBakeMaxChunksPerSec=9\nsoundTickHz=9\nlightSwitchCheckFrames=9\nlightingFps=9\npresentPacing=on\nvrr=on\nborderlessFullscreen=on\n"
            + "numberZombiesBlended=9\nzombieLodFixed3d=99\nzombieModelAddBudgetUs=9\nzombieSimLodTiles=9\nzombieSimLodSteps=3\nvboBatchKb=512\nvboSizeClasses=orphan\n"));
        Settings.forcedOff.addAll(VALUE_KEYS);
        AimHold.init(); Cutaways.init(); GridGate.init(); ZoomEase.init(); BakeMisc.init(); ChunkTrees.init(); SoundTick.init();
        LightChecks.init(); SwapHold.init(); Vrr.init(); ZombieLod.init(); BatchSize.init(); UploadSizer.init();
        try {
            assert AimHold.holdMs == 150 && Cutaways.radius == 0 && GridGate.interval == 0 && GridGate.roofFrames == 0 && ZoomEase.ms == 0;
            assert BakeMisc.mipLevels == 0 && ChunkTrees.maxPerSec == 0 && !SoundPatches.tickGuard.on && LightChecks.switchTtl == 0 && LightChecks.lightingFps == 0;
            assert SwapHold.pacing.equals("off") && Vrr.mode.equals("off") && Vrr.fullscreenWindow.equals("off") && !Vrr.guard.on;
            assert ZombieLod.blended == 0 && ZombieLod.cap == 0 && ZombieLod.budgetNs == 0 && !ZombieLod.blendGuard.on && !ZombieLod.capGuard.on && !ZombieLod.budgetGuard.on;
            assert ZombieLod.simTiles == 0 && ZombieLod.simSteps == 1 && !ZombieLod.simGuard.on;
            assert BatchSize.kb == 0 && !BatchSize.guard.on;                       // vboBatchKb: stock 4 KB, nothing installed
            assert UploadSizer.mode == 0 && !UploadSizer.guard.on;                  // vboSizeClasses: the game's upload
            // gpuMaxFrames (stock-off means a different number) has its own test below
        } finally {
            Settings.load(SelfTest.write("enabled=true\n"));                         // back to the shipped values for the features that follow
            AimHold.init(); Cutaways.init(); GridGate.init(); ZoomEase.init(); BakeMisc.init(); ChunkTrees.init(); SoundTick.init();
            LightChecks.init(); SwapHold.init(); Vrr.init(); ZombieLod.init(); BatchSize.init(); UploadSizer.init();
        }
    }

    static void oursReadsEveryHolder() {
        java.util.List<ModCompat.Target> ours = ModCompat.ours();
        assert ours.size() == ModCompat.KEYS.size() : ours.size() + " vs " + ModCompat.KEYS.size();
        assert ours.contains(new ModCompat.Target("se.krka.kahlua.integration.LuaCaller", "protectedCallVoid", true, "LuaPatches.Call"));
        assert ours.contains(new ModCompat.Target("zombie.Lua.Event", "trigger", false, "LuaPatches.Trigger"));
        assert ours.contains(new ModCompat.Target("zombie.GameWindow", "frameStep", false, "Patches.Frame"));
        for (ModCompat.Target t : ours) assert ModCompat.KEYS.containsKey(t.where()) : t;
    }

    static void autoForcesOnlyConflictingKeys() {
        java.util.List<String> log = new java.util.ArrayList<>();
        java.util.List<ModCompat.Target> ours = ModCompat.ours();
        java.util.List<ModCompat.Target> theirs = java.util.List.of(new ModCompat.Target("se.krka.kahlua.integration.LuaCaller", "protectedCallVoid", true, "other.jar"));
        java.util.Set<String> off = ModCompat.apply("auto", ours, theirs, log::add);
        // protectedCallVoid is hooked by LuaPatches.Call only; the other LuaCaller holders stay
        assert off.equals(new java.util.HashSet<>(java.util.Arrays.asList(ModCompat.KEYS.get("LuaPatches.Call")))) : off;
        assert log.get(0).startsWith("[PZButter] modCompat: other.jar patches se.krka.kahlua.integration.LuaCaller.protectedCallVoid") && log.get(log.size() - 1).contains("switched off");
        assert log.get(0).contains("LuaPatches.Call: luaBudget, luaSkipEmpty, luaTiming, luaWorkerGate") && log.get(0).contains("both replace the game method");
        log.clear();
        assert ModCompat.apply("report", ours, theirs, log::add).isEmpty() && log.size() == 1;
        // an observing mod on a method where we only observe: no conflict at all
        log.clear();
        assert ModCompat.apply("auto", ours, java.util.List.of(new ModCompat.Target("zombie.Lua.Event", "trigger", false, "obs.jar")), log::add).isEmpty() && log.isEmpty();
        // a replacing mod on the frame loop switches off what the frame holder drives, nothing else
        log.clear();
        java.util.Set<String> frame = ModCompat.apply("auto", ours, java.util.List.of(new ModCompat.Target("zombie.GameWindow", "frameStep", true, "frame.jar")), log::add);
        assert frame.equals(new java.util.HashSet<>(java.util.Arrays.asList(ModCompat.KEYS.get("Patches.Frame")))) && !frame.contains("limiterSleep");
    }

    static void applyOffAndUnknownHolder() {
        java.util.List<String> log = new java.util.ArrayList<>();
        java.util.List<ModCompat.Target> theirs = java.util.List.of(new ModCompat.Target("a.B", "m", true, "x.jar"));
        assert ModCompat.apply("off", ModCompat.ours(), theirs, log::add).isEmpty() && log.isEmpty();
        assert ModCompat.apply("bogus", ModCompat.ours(), theirs, log::add).isEmpty() && log.isEmpty();
        // a holder with no table entry is reported, nothing is switched off, no throw
        java.util.List<ModCompat.Target> ghost = java.util.List.of(new ModCompat.Target("a.B", "m", true, "Ghost.Holder"));
        assert ModCompat.apply("auto", ghost, theirs, log::add).isEmpty() && log.size() == 1 && log.get(0).contains("Ghost.Holder: no key");
        // the same pair twice reports once
        log.clear();
        ModCompat.apply("report", ghost, java.util.List.of(theirs.get(0), theirs.get(0)), log::add);
        assert log.size() == 1;
    }

    /** The forced set reaches the settings: a forced key answers stock to the feature's own init. */
    static void autoFeedsSettings() {
        Settings.load(SelfTest.write("enabled=true\nluaTiming=true\nluaSkipEmpty=true\nlimiterSleep=true\n"));
        assert Settings.flag("luaTiming") && Settings.flag("luaSkipEmpty");
        Settings.forcedOff.addAll(ModCompat.apply("auto", ModCompat.ours(),
            java.util.List.of(new ModCompat.Target("se.krka.kahlua.integration.LuaCaller", "protectedCallVoid", true, "other.jar")), s -> { }));
        assert !Settings.flag("luaTiming") && !Settings.flag("luaSkipEmpty") && !Settings.flag("luaBudget") && !Settings.flag("luaWorkerGate");
        assert Settings.flag("limiterSleep") && Settings.enabled;                   // unrelated keys and the master switch stay
        LuaTiming.init();
        assert !LuaTiming.guard.on;
        Settings.load(SelfTest.write("enabled=true\n"));
        LuaTiming.init();
    }

    /** off (the shipped default) does nothing and prints nothing; a failing scan never throws. */
    static void modCompatInitOffDoesNothing() {
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(cap, true));
            Settings.load(SelfTest.write("enabled=true\n"));
            ModCompat.init();
            assert cap.size() == 0 && Settings.forcedOff.isEmpty();
            Settings.load(SelfTest.write("enabled=true\nmodCompat=auto\n"));
            ModCompat.init();                                                       // headless: ZB's list is empty, nothing to scan, nothing forced
            assert Settings.forcedOff.isEmpty();
            assert cap.toString().contains("modCompat=auto: 0 other Java mods scanned") : cap;
        } finally { System.setOut(out); Settings.load(SelfTest.write("enabled=true\n")); }
    }

    /** gpuMaxFrames is a number whose default is on: forced off must still switch it off. */
    static void gpuQueueForcedOff() {
        Settings.load(SelfTest.write("enabled=true\n"));
        GpuQueue.init();
        assert GpuQueue.guard.on;
        Settings.forcedOff.add("gpuMaxFrames");
        GpuQueue.init();
        assert !GpuQueue.guard.on;
        Settings.load(SelfTest.write("enabled=true\n"));
        GpuQueue.init();
        assert GpuQueue.guard.on;
        Settings.load(SelfTest.write("enabled=false\n"));
        GpuQueue.init();
    }
}
