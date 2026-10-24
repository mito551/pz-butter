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
        createFalseIsNeverHeld();
        unknownNameAndSizeCapPassThrough();
        windowsKeysFoldCase();
        fileKeyOffByDefault();
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
        assert took[0] && LuaGate.gatedCalls == 1 && fakeGame != null;
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

    static void createFalseIsNeverHeld() throws Exception {
        // a stock open with create=false fails on a missing file: only a held create=true text may be extended
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
}
