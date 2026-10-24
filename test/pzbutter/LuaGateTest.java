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
    static {
        try { FAKE = MethodHandles.lookup().findStaticVarHandle(LuaGateTest.class, "fakeGame", Thread.class); }
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
}
