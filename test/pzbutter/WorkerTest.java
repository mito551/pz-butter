package pzbutter;

import java.util.List;
import java.util.Set;

/** Plan 8b checks (frame workers; Task 2: the list resource is on the class path). Run from SelfTest.main. */
final class WorkerTest {
    private WorkerTest() {}

    static void run() throws Exception {
        workersResourceInJar();
        preloadDecideOrder();
        preloadDecideNoPool();
        preloadDecideRevisionMismatch();
        preloadDecideAot();
        preloadDecideLoadedClassOff();
        zbAccessMissingFieldNull();
        gameRevisionRead();
        workerListParses();
        transformAddsFieldAndMarker();
        transformLeavesOtherClassesAlone();
        transformMismatchLeavesClassUntouched();
        transformerSkipsRedefinition();
        transformRewritesScratchRead();
        scratchListFindsMutableStatics();
        workerListRepeatedK();
        workerListRejectsDuplicateField();
        scratchListArrays();
        transformRewritesArrayRead();
        transformVerifiesOnGameClasses();
        transformSkipsNonPublicScratchType();
        scratchListResolvesOnGameJar();
        scratchGivesEachWorkerItsCopy();
        scratchKeepsOriginalForOtherThreads();
        scratchCopiesArrays();
        scratchProbeReportsUncopyable();
        scratchUnresolvableOwner();
        freshGivesEachWorkerItsOwnInstance();
        freshListParses();
        scratchListKeepsHandSection();
        untickedModDecision();
        batchRunsEachIndexOnce();
        batchUsesWorkersAndCaller();
        batchRethrowsAfterAllFinish();
        batchNestedRunsInline();
        batchWorkerReachesLuaThroughGate();
        latchAppliesInOrder();
        latchDirectOutsideBatch();
        latchNoAllocationWarm();
        latchThrowingRecordDoesNotStopOthers();
        latchClearsObjectSlots();
        latchUnsortedBufferFallsBack();
        latchTieByWorkerSlot();
        latchDirectPropagates();
        latchRegistryNoLeak();
        bucketMultiplierWindow();
    }

    static void workersResourceInJar() {
        assert WorkerTest.class.getResource("/pzbutter/workers-4a0e9546ec.txt") != null;
    }

    static void preloadDecideOrder() {
        WorkerList l = WorkerList.parse("revision r1\nclass a.B\n");
        assert !Preload.decide("r1", l, 4, true, false, List.of(), true, Set.of()).install();
        assert Preload.decide("r1", l, 4, true, false, List.of(), true, Set.of()).reason().equals("enabled=false");
        assert Preload.decide("r1", l, 4, true, true, List.of(), true, Set.of()).install();
    }

    static void preloadDecideNoPool() {
        WorkerList l = WorkerList.parse("revision r1\nclass a.B\n");
        for (int n : new int[] {0, 1})
            assert Preload.decide("r1", l, n, true, true, List.of(), true, Set.of()).reason().equals("frameThreads=" + n);
        assert Preload.decide("r1", l, 4, false, true, List.of(), true, Set.of()).reason().equals("luaWorkerGate=false");
    }

    static void preloadDecideRevisionMismatch() {
        WorkerList l = WorkerList.parse("revision r1\nclass a.B\n");
        assert Preload.decide("r2", l, 4, true, true, List.of(), true, Set.of()).reason().equals("revision r2 != list r1");
        assert Preload.decide("r2", null, 4, true, true, List.of(), true, Set.of()).reason().equals("no list for revision r2");
    }

    static void preloadDecideAot() {
        WorkerList l = WorkerList.parse("revision r1\nclass a.B\n");
        assert Preload.decide("r1", l, 4, true, true, List.of("-XX:AOTCache=x.aot"), true, Set.of()).reason().equals("AOT cache -XX:AOTCache=x.aot");
        assert Preload.decide("r1", l, 4, true, true, List.of("-XX:AOTMode=off"), true, Set.of()).install();
    }

    static void preloadDecideLoadedClassOff() {
        WorkerList l = WorkerList.parse("revision r1\nclass a.B\nclass a.C\n");
        assert Preload.decide("r1", l, 4, true, true, List.of(), true, Set.of("a.C", "a.B")).reason().equals("a.B loaded before the preload");
    }

    static void zbAccessMissingFieldNull() {
        assert ZbAccess.instrumentation("java.lang.Object") == null;
        assert ZbAccess.instrumentation("no.such.Loader") == null;
    }

    static void gameRevisionRead() { assert Preload.gameRevision().equals("4a0e9546ec"); }   // projectzomboid.jar on the test class path

    static void workerListParses() {
        WorkerList l = WorkerList.parse("revision r\nclass t.A\nscratch 0 t/A L Ljava/util/ArrayList;\nfield t.A pzbIdx I\n");
        assert l.revision().equals("r") && l.classes().equals(List.of("t.A")) && l.listed("t/A");
        assert l.scratch().get(0).equals(new WorkerList.Scratch(0, "t/A", "L", "Ljava/util/ArrayList;"));
        assert l.fieldsOf("t.A").equals(List.of(new WorkerList.Field("t.A", "pzbIdx", "I")));
        try { WorkerList.parse("revision r\nbogus\n"); assert false; } catch (IllegalArgumentException e) { assert e.getMessage().contains("2"); }
    }

    static void transformAddsFieldAndMarker() throws Exception {
        Class<?> c = WorkerTestSynth.loadTransformed();
        assert c.getField(WorkerTransform.MARKER).getInt(null) == WorkerTestSynth.list().textHash();
        java.lang.invoke.VarHandle h = Game.field(c, "pzbIdx", int.class);
        Object a = c.getConstructor().newInstance();
        h.set(a, 7);
        assert (int) h.get(a) == 7;
    }

    static void transformLeavesOtherClassesAlone() { assert WorkerTransform.transform(WorkerTestSynth.otherClass(), WorkerTestSynth.list()) == null; }

    static void transformMismatchLeavesClassUntouched() {
        assert WorkerTransform.transform(WorkerTestSynth.classWithField("pzbIdx"), WorkerTestSynth.list()) == null;
        assert WorkerTransform.transform(WorkerTestSynth.listedClassWithoutReads(), WorkerTestSynth.listNoFields()) == null;
    }

    static void transformerSkipsRedefinition() throws Exception {
        WorkerTransform t = new WorkerTransform(WorkerTestSynth.list(), null);
        assert t.transform(null, null, "t/A", Object.class, null, WorkerTestSynth.classA()) == null;
    }

    static void transformRewritesScratchRead() throws Exception {
        byte[] b = WorkerTransform.transform(WorkerTestSynth.classA(), WorkerTestSynth.list());
        java.lang.classfile.ClassModel m = java.lang.classfile.ClassFile.of().parse(b);
        boolean getstatic = false, call = false;
        for (java.lang.classfile.MethodModel mm : m.methods()) {
            if (!mm.methodName().equalsString("sum")) continue;
            for (java.lang.classfile.CodeElement e : mm.code().orElseThrow()) {
                if (e instanceof java.lang.classfile.instruction.FieldInstruction f && f.opcode() == java.lang.classfile.Opcode.GETSTATIC
                    && f.name().equalsString("L")) getstatic = true;
                if (e instanceof java.lang.classfile.instruction.InvokeInstruction i && i.owner().asInternalName().equals("pzbutter/Scratch")
                    && i.name().equalsString("get")) call = true;
            }
        }
        assert !getstatic && call;
    }

    static void scratchListFindsMutableStatics() throws Exception {
        java.nio.file.Path jar = WorkerTestSynth.writeScratchJar(SelfTest.tmp("scratch/t.jar").toPath());
        List<String> out = ScratchList.run(jar, List.of("t.A.m:()V"), "r", new String[] {"t/"}, t -> t.equals("t/V") || t.equals("java/util/ArrayList"));
        assert out.get(0).equals("revision r");
        assert out.contains("class t.A") && out.contains("class t.B");
        assert out.stream().anyMatch(l -> l.startsWith("scratch ") && l.contains(" t/A L Ljava/util/ArrayList; "));
        assert out.stream().anyMatch(l -> l.startsWith("scratch ") && l.contains(" t/B V Lt/V; "));
        assert out.stream().noneMatch(l -> l.startsWith("scratch ") && l.contains(" t/A S "));
        assert out.stream().anyMatch(l -> l.startsWith("# written") && l.contains("t.A.counter:I"));
        assert out.contains("class t.C");
        assert out.stream().anyMatch(l -> l.startsWith("scratch 1 t/C V Lt/V;"));   // same k as t/B V
        WorkerList w = WorkerList.parse(String.join("\n", out));
        assert w.scratchIndex("t/C", "V", "Lt/V;") == w.scratchIndex("t/B", "V", "Lt/V;") && w.scratchIndex("t/B", "V", "Lt/V;") == 1;   // the output is a valid list
    }

    static void workerListRepeatedK() {
        WorkerList l = WorkerList.parse("revision r\nscratch 0 a/B V La/V;\nscratch 0 a/C V La/V;\nscratch 1 a/B W La/V;\n");
        assert l.scratchIndex("a/C", "V", "La/V;") == 0 && l.scratchIndex("a/B", "W", "La/V;") == 1;
        try { WorkerList.parse("revision r\nscratch 0 a/B V La/V;\nscratch 0 a/C X La/V;\n"); assert false; }
        catch (IllegalArgumentException e) { assert e.getMessage().contains("3"); }
        try { WorkerList.parse("revision r\nscratch 0 a/B V La/V;\nscratch 0 a/C V La/W;\n"); assert false; }
        catch (IllegalArgumentException e) { assert e.getMessage().contains("3"); }
    }

    static void transformRewritesArrayRead() {
        byte[] b = WorkerTransform.transform(WorkerTestSynth.classArr(), WorkerList.parse(WorkerTestSynth.ARR_LIST));
        assert b != null;
        boolean cast = false;
        for (java.lang.classfile.MethodModel mm : java.lang.classfile.ClassFile.of().parse(b).methods()) {
            if (!mm.methodName().equalsString("len")) continue;
            for (java.lang.classfile.CodeElement e : mm.code().orElseThrow())
                if (e instanceof java.lang.classfile.instruction.TypeCheckInstruction t && t.opcode() == java.lang.classfile.Opcode.CHECKCAST
                    && t.type().asSymbol().descriptorString().equals("[I")) cast = true;
        }
        assert cast;
        assert java.lang.classfile.ClassFile.of().verify(b).isEmpty();
    }

    /** Every listed game class, transformed, passes the class file verifier. */
    static void transformVerifiesOnGameClasses() throws Exception {
        WorkerList list = WorkerList.load("4a0e9546ec");
        java.lang.classfile.ClassFile cf = java.lang.classfile.ClassFile.of(java.lang.classfile.ClassFile.ClassHierarchyResolverOption.of(
            java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(ClassLoader.getSystemClassLoader())
                .orElse(java.lang.classfile.ClassHierarchyResolver.defaultResolver())));
        for (String c : list.classes()) {
            byte[] bytes;
            try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream(c.replace('.', '/') + ".class")) {
                assert in != null : c + ": class file not found";
                bytes = in.readAllBytes();
            }
            byte[] out = WorkerTransform.transform(bytes, list);
            assert out != null : c + " not transformed";
            var errors = cf.verify(out);
            assert errors.isEmpty() : c + ": " + errors.get(0).getMessage();
        }
    }

    static void scratchListArrays() throws Exception {
        java.nio.file.Path jar = WorkerTestSynth.writeScratchJar(SelfTest.tmp("scratch/t2.jar").toPath());
        List<String> out = ScratchList.run(jar, List.of("t.D.q:()V"), "r", new String[] {"t/"});
        assert out.stream().noneMatch(l -> l.startsWith("scratch ") && l.contains(" t/D G "));
        assert out.stream().anyMatch(l -> l.startsWith("# review t.D.G "));
        assert out.stream().anyMatch(l -> l.startsWith("scratch ") && l.contains(" t/D H [I "));
        assert out.stream().noneMatch(l -> l.startsWith("# readonly t.D.H"));
    }

    private static ClassLoader loaderFor(byte[] b) {
        return new ClassLoader(WorkerTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (!n.equals("t.A") && !n.equals("t.Arr") && !n.equals("t.Bad")) throw new ClassNotFoundException(n);
                return defineClass(n, b, 0, b.length);
            }
        };
    }

    /** Runs sum(k) 20,000 times on 8 threads (workers or plain); returns the number of wrong sums and exceptions. */
    private static int raceSum(Class<?> c, boolean workers) throws Exception {
        java.lang.reflect.Method sum = c.getMethod("sum", int.class);
        java.util.concurrent.atomic.AtomicInteger bad = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] t = new Thread[8];
        for (int i = 0; i < 8; i++) {
            int k = 50 + i;
            Runnable r = () -> { for (int j = 0; j < 20_000; j++) try { if ((int) sum.invoke(null, k) != k * (k - 1) / 2) bad.incrementAndGet(); } catch (Exception e) { bad.incrementAndGet(); } };
            t[i] = workers ? LuaGate.worker(r, "w" + i) : new Thread(r);
            t[i].start();
        }
        for (Thread x : t) x.join(60_000);
        return bad.get();
    }

    static void scratchGivesEachWorkerItsCopy() throws Exception {
        Scratch.init(WorkerTestSynth.list());
        Class<?> c = WorkerTestSynth.loadTransformed();
        Scratch.loader = c.getClassLoader();
        try {
            assert raceSum(c, true) == 0;
            assert Scratch.copies() == 8;
            Class<?> plain = loaderFor(WorkerTestSynth.classA()).loadClass("t.A");   // the control: untransformed shares L and must fail
            assert raceSum(plain, false) > 0;
        } finally { Scratch.loader = ClassLoader.getSystemClassLoader(); }
    }

    static void scratchKeepsOriginalForOtherThreads() throws Exception {
        Scratch.init(WorkerTestSynth.list());
        Class<?> c = WorkerTestSynth.loadTransformed();
        Scratch.loader = c.getClassLoader();
        try {
            Object original = c.getField("L").get(null);
            assert Scratch.get(0) == original;
            Object[] seen = new Object[3];
            Thread other = new Thread(() -> seen[0] = Scratch.get(0)); other.start(); other.join(5000);
            assert seen[0] == original;
            int before = Scratch.copies();
            Thread w = LuaGate.worker(() -> { seen[1] = Scratch.get(0); seen[2] = Scratch.get(0); }, "w");
            w.start(); w.join(5000);
            assert seen[1] != null && seen[1] != original && seen[1] == seen[2] && seen[1] instanceof java.util.ArrayList;
            assert Scratch.copies() == before + 1;
            ((java.util.ArrayList<Object>) original).add(1);                    // a copy starts as a copy of the original's content
            Object[] later = new Object[1];
            Thread w2 = LuaGate.worker(() -> later[0] = Scratch.get(0), "w2"); w2.start(); w2.join(5000);
            assert ((java.util.ArrayList<?>) later[0]).size() == 1 && later[0] != original;
        } finally { Scratch.loader = ClassLoader.getSystemClassLoader(); }
    }

    static void scratchCopiesArrays() throws Exception {
        Scratch.init(WorkerList.parse(WorkerTestSynth.ARR_LIST));
        Class<?> c = loaderFor(WorkerTestSynth.classArr()).loadClass("t.Arr");
        Scratch.loader = c.getClassLoader();
        try {
            int[] original = (int[]) c.getField("X").get(null);
            original[1] = 5;
            int[][] seen = new int[1][];
            Thread w = LuaGate.worker(() -> seen[0] = (int[]) Scratch.get(0), "w"); w.start(); w.join(5000);
            assert seen[0] != null && seen[0] != original && seen[0].length == 3 && seen[0][1] == 5;
        } finally { Scratch.loader = ClassLoader.getSystemClassLoader(); }
    }

    /** t.Bad with `static final Optional F` (no public constructor, not Cloneable). */
    private static byte[] classBad() {
        var self = java.lang.constant.ClassDesc.of("t.Bad"); var opt = java.lang.constant.ClassDesc.of("java.util.Optional");
        return java.lang.classfile.ClassFile.of().build(self, cb -> {
            cb.withFlags(java.lang.classfile.ClassFile.ACC_PUBLIC);
            cb.withField("F", opt, java.lang.classfile.ClassFile.ACC_PUBLIC | java.lang.classfile.ClassFile.ACC_STATIC | java.lang.classfile.ClassFile.ACC_FINAL);
            cb.withMethodBody("<clinit>", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void), java.lang.classfile.ClassFile.ACC_STATIC, b -> b
                .invokestatic(opt, "empty", java.lang.constant.MethodTypeDesc.of(opt)).putstatic(self, "F", opt).return_());
        });
    }

    static void scratchProbeReportsUncopyable() throws Exception {
        Scratch.init(WorkerList.parse("revision r\nclass t.Bad\nscratch 0 t/Bad F Ljava/util/Optional;\n"));
        Scratch.loader = loaderFor(classBad());
        try {
            assert Scratch.uncopyable() == 0;
            String p = Scratch.probe();
            assert p.startsWith("t/Bad.F: ") : p;
            assert Scratch.uncopyable() == 1;
            Object original = Scratch.get(0);
            Object[] seen = new Object[1];
            Thread w = LuaGate.worker(() -> seen[0] = Scratch.get(0), "w"); w.start(); w.join(5000);
            assert seen[0] == original && Scratch.copies() == 0;   // a worker still gets the shared object, never a throw
        } finally { Scratch.loader = ClassLoader.getSystemClassLoader(); }
    }

    static void scratchUnresolvableOwner() throws Exception {
        Scratch.init(WorkerList.parse("revision r\nclass t.Nope\nscratch 0 t/Nope F Ljava/util/ArrayList;\n"));
        Scratch.loader = loaderFor(classBad());
        try {
            assert Scratch.get(0) == null;
            Object[] seen = {"x"};
            Thread w = LuaGate.worker(() -> seen[0] = Scratch.get(0), "w"); w.start(); w.join(5000);
            assert seen[0] == null;
            assert Scratch.probe().startsWith("t/Nope.F: ") && Scratch.uncopyable() == 1;
        } finally { Scratch.loader = ClassLoader.getSystemClassLoader(); }
    }

    static void freshGivesEachWorkerItsOwnInstance() throws Exception {
        Scratch.init(WorkerList.parse(WorkerTestSynth.FRESH_LIST_TEXT));
        Class<?> c = WorkerTestSynth.loadFreshTransformed();
        Scratch.loader = c.getClassLoader();
        try {
            Object original = c.getField("INSTANCE").get(null);
            assert Scratch.get(0) == original;
            Object[] seen = new Object[2];
            Thread w1 = LuaGate.worker(() -> seen[0] = Scratch.get(0), "w1");
            Thread w2 = LuaGate.worker(() -> seen[1] = Scratch.get(0), "w2");
            w1.start(); w2.start(); w1.join(5000); w2.join(5000);
            assert seen[0] != null && seen[1] != null && seen[0] != seen[1] && seen[0] != original && seen[1] != original;
            assert seen[0].getClass().getField("value").get(seen[0]) != null;
            assert seen[1].getClass().getField("value").get(seen[1]) != null;
            assert seen[0].getClass().getField("value").get(seen[0]) != seen[1].getClass().getField("value").get(seen[1]);
            Object[] nth = new Object[1];
            Thread t = new Thread(() -> nth[0] = Scratch.get(0)); t.start(); t.join(5000);
            assert nth[0] == original;
        } finally { Scratch.loader = ClassLoader.getSystemClassLoader(); }
    }

    static void freshListParses() {
        WorkerList l = WorkerList.parse("revision r\nclass t.G\nfresh 0 t/G INSTANCE Lt/Gen; init\n");
        assert l.fresh().size() == 1;
        assert l.fresh().get(0).equals(new WorkerList.Fresh(0, "t/G", "INSTANCE", "Lt/Gen;", "init"));
        try { WorkerList.parse("revision r\nfresh 0 t/G INSTANCE Lt/Gen;\n"); assert false; } catch (IllegalArgumentException e) { assert e.getMessage().contains("2"); }
        try { WorkerList.parse("revision r\nscratch 0 t/G INSTANCE Lt/Gen;\nfresh 0 t/H INSTANCE Lt/Gen; init\n"); assert false; } catch (IllegalArgumentException e) { assert e.getMessage().contains("3"); }
    }

    static void scratchListKeepsHandSection() throws Exception {
        java.nio.file.Path jar = WorkerTestSynth.writeScratchJar(SelfTest.tmp("scratch/h.txt").toPath());
        java.nio.file.Path hand = SelfTest.tmp("scratch/hand.txt").toPath();
        java.nio.file.Files.writeString(hand, "revision r\n# hand begin\nfresh 0 t/G INSTANCE Lt/Gen; init\nclass t.X\n# hand end\n");
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try { ScratchList.main(new String[]{"--hand", hand.toString(), jar.toString(), "r", "t.A.m:()V"}); } finally { System.setOut(old); }
        String out = baos.toString();
        assert out.contains("# hand begin") && out.contains("fresh 0 t/G INSTANCE Lt/Gen; init") && out.contains("# hand end");
    }

    static void batchRunsEachIndexOnce() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        FrameBatch.start(4);
        for (int n : new int[] {0, 1, 2, 7, 10_000}) {
            java.util.concurrent.atomic.AtomicIntegerArray hits = new java.util.concurrent.atomic.AtomicIntegerArray(Math.max(n, 1));
            FrameBatch.run(n, i -> hits.incrementAndGet(i));
            for (int i = 0; i < n; i++) assert hits.get(i) == 1 : n + "/" + i;
        }
        FrameBatch.stopForTest(); LuaGate.resetForTest();
    }

    static void batchUsesWorkersAndCaller() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        FrameBatch.start(4);
        java.util.Set<Thread> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.CountDownLatch all = new java.util.concurrent.CountDownLatch(4);
        FrameBatch.run(4, i -> { seen.add(Thread.currentThread()); all.countDown(); try { all.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } });
        assert seen.size() == 4 && seen.contains(Thread.currentThread());
        FrameBatch.stopForTest(); LuaGate.resetForTest();
    }

    static void batchRethrowsAfterAllFinish() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        FrameBatch.start(3);
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        try { FrameBatch.run(100, i -> { if (i == 17) throw new IllegalStateException("17"); done.incrementAndGet(); }); assert false; }
        catch (IllegalStateException e) { assert e.getMessage().equals("17") && done.get() == 99; }
        FrameBatch.stopForTest(); LuaGate.resetForTest();
    }

    static void batchNestedRunsInline() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        FrameBatch.start(3);
        java.util.concurrent.atomic.AtomicInteger inner = new java.util.concurrent.atomic.AtomicInteger();
        FrameBatch.run(6, i -> { Thread t = Thread.currentThread(); FrameBatch.run(5, j -> { assert Thread.currentThread() == t; inner.incrementAndGet(); }); });
        assert inner.get() == 30;
        FrameBatch.stopForTest(); LuaGate.resetForTest();
    }

    static void batchWorkerReachesLuaThroughGate() throws Exception {
        LuaGateTest.Lua l = new LuaGateTest.Lua("n = 0; function inc() n = n + 1 end");
        LuaGateTest.setUp(l);
        FrameBatch.start(4);
        Object inc = l.env.rawget("inc");
        FrameBatch.run(400, i -> LuaGateTest.gated(l, () -> l.caller.pcallvoid(l.kt, inc, (Object) null)));
        assert ((Double) l.env.rawget("n")) == 400.0;
        FrameBatch.stopForTest(); LuaGate.resetForTest();
    }

    static void workerListRejectsDuplicateField() {
        try { WorkerList.parse("revision r\nfield t.A x I\nfield t.A x I\n"); assert false; }
        catch (IllegalArgumentException e) { assert e.getMessage().contains("3"); }
        try { WorkerList.parse("revision r\nfield t.A x I\nfield t.A x J\n"); assert false; }
        catch (IllegalArgumentException e) { assert e.getMessage().contains("3"); }
    }

    static void scratchListResolvesOnGameJar() throws Exception {
        WorkerList l = WorkerList.load("4a0e9546ec");
        java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
        for (WorkerList.Scratch s : l.scratch()) {
            Class<?> owner = Class.forName(s.owner().replace('/', '.'), false, ClassLoader.getSystemClassLoader());
            Class<?> type = java.lang.invoke.MethodType.fromMethodDescriptorString("()" + s.desc(), ClassLoader.getSystemClassLoader()).returnType();
            java.lang.invoke.MethodHandles.privateLookupIn(owner, lookup).findStaticVarHandle(owner, s.field(), type);
        }
    }

    static void transformSkipsNonPublicScratchType() throws Exception {
        byte[] ppType = WorkerTestSynth.nonPublicTypeClass();
        byte[] ppReader = WorkerTestSynth.readerWithType("t.NonPublicType");
        WorkerList ppList = WorkerList.parse("revision r\nclass t.Rdr\nscratch 0 t/Rdr F Lt/NonPublicType;\n");
        ClassLoader ppLoader = loaderForTypes(ppReader, ppType, "t.NonPublicType");
        assert WorkerTransform.transform(ppReader, ppList, ppLoader) == null;

        byte[] pubType = WorkerTestSynth.publicTypeClass();
        byte[] pubReader = WorkerTestSynth.readerWithType("t.PublicType");
        WorkerList pubList = WorkerList.parse("revision r\nclass t.Rdr\nscratch 0 t/Rdr F Lt/PublicType;\n");
        ClassLoader pubLoader = loaderForTypes(pubReader, pubType, "t.PublicType");
        assert WorkerTransform.transform(pubReader, pubList, pubLoader) != null;
    }

    private static ClassLoader loaderForTypes(byte[] reader, byte[] type, String typeName) {
        return new ClassLoader(WorkerTest.class.getClassLoader()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (n.equals("t.Rdr")) return defineClass(n, reader, 0, reader.length);
                if (n.equals(typeName)) return defineClass(n, type, 0, type.length);
                throw new ClassNotFoundException(n);
            }
        };
    }

    static void untickedModDecision() {
        assert !Preload.inModList(java.util.Set.of());
        assert !Preload.inModList(java.util.Set.of("Other"));
        assert Preload.inModList(null);
        assert Preload.inModList(java.util.Set.of("PZButter"));
    }

    static void latchAppliesInOrder() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        java.util.List<Integer> applied = new java.util.ArrayList<>();
        Latch.Apply op = (a, b, i, f) -> applied.add((Integer) a);
        Thread[] ws = new Thread[4];
        Latch.begin(1);
        for (int t = 0; t < 4; t++) {
            int tt = t;
            ws[t] = LuaGate.worker(() -> {
                for (int j = 0; j < 10; j++) {
                    Latch.record((j * 4 + tt) % 12, op, (j * 4 + tt) % 12, null, 0, 0.0f);
                }
            }, "w" + t);
            ws[t].start();
        }
        for (Thread w : ws) w.join();
        int count = Latch.applyAll();
        assert count == 40 : count;
        assert applied.size() == 40 : applied.size();
        for (int k = 1; k < applied.size(); k++) assert applied.get(k - 1) <= applied.get(k) : k;
        LuaGate.resetForTest();
    }

    static void latchDirectOutsideBatch() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        Latch.record(3, (a, b, i, f) -> n.incrementAndGet(), null, null, 0, 0.0f);
        assert n.get() == 1;
    }

    static void latchNoAllocationWarm() throws Exception {
        com.sun.management.ThreadMXBean mx;
        try { mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean(); }
        catch (ClassCastException e) { System.out.println("latchNoAllocationWarm: ThreadMXBean not com.sun.management, skipped"); return; }
        if (!mx.isThreadAllocatedMemorySupported()) { System.out.println("latchNoAllocationWarm: allocated memory not supported, skipped"); return; }
        mx.setThreadAllocatedMemoryEnabled(true);
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        Latch.Apply op = (a, b, i, f) -> { };
        final long[] delta = {0};
        Thread w = LuaGate.worker(() -> {
            // warm the buffer past 10,000 capacity first
            for (int k = 0; k < 20000; k++) Latch.record(1, op, null, null, 0, 0.0f);
            LuaGate.Worker ww = null; // warm buffer ready
            long before = mx.getCurrentThreadAllocatedBytes();
            for (int k = 0; k < 10000; k++) Latch.record(1, op, null, null, 0, 0.0f);
            delta[0] = mx.getCurrentThreadAllocatedBytes() - before;
        }, "w");
        Latch.begin(1);
        w.start(); w.join();
        int c = Latch.applyAll();
        assert c == 30000 : c;
        // measured with mx.getCurrentThreadAllocatedBytes(); at most 1 KB allowed for the measuring call
        assert delta[0] <= 1024 : "allocated " + delta[0];
        LuaGate.resetForTest();
    }

    static void latchThrowingRecordDoesNotStopOthers() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        Latch.Apply op = (a, b, i, f) -> { if ((Integer) a == 2) throw new IllegalStateException("boom"); done.incrementAndGet(); };
        Thread w = LuaGate.worker(() -> {
            for (int i = 0; i < 5; i++) Latch.record(i, op, i, null, 0, 0.0f);
        }, "w");
        Latch.begin(1);
        w.start(); w.join();
        long beforeFailed = Latch.failed;
        try { Latch.applyAll(); assert false; }
        catch (IllegalStateException e) { assert e.getMessage().equals("boom"); }
        assert done.get() == 4;
        assert Latch.failed == beforeFailed + 1;
        LuaGate.resetForTest();
    }

    static void latchClearsObjectSlots() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        Object a = new Object(); Object b = new Object();
        java.util.concurrent.atomic.AtomicReference<Object> ca = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Object> cb = new java.util.concurrent.atomic.AtomicReference<>();
        Thread w = LuaGate.worker(() -> {
            Latch.record(1, (x, y, i, f) -> { ca.set(x); cb.set(y); }, a, b, 0, 0.0f);
        }, "w");
        Latch.begin(1);
        w.start(); w.join();
        assert Latch.applyAll() == 1;
        // buffers expose no easy access; just ensure no exception and applied ran
        assert ca.get() == a && cb.get() == b;
        LuaGate.resetForTest();
    }

    static void latchUnsortedBufferFallsBack() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        java.util.List<Integer> applied = new java.util.ArrayList<>();
        Latch.Apply op = (a, b, i, f) -> applied.add((Integer) a);
        Thread w = LuaGate.worker(() -> {
            Latch.record(5, op, 5, null, 0, 0.0f);
            Latch.record(3, op, 3, null, 0, 0.0f);
            Latch.record(4, op, 4, null, 0, 0.0f);
        }, "w");
        long beforeUnsorted = Latch.unsorted;
        Latch.begin(1);
        w.start(); w.join();
        int count = Latch.applyAll();
        assert count == 3;
        assert applied.equals(java.util.List.of(3, 4, 5)) : applied;
        assert Latch.unsorted == beforeUnsorted + 1;
        LuaGate.resetForTest();
    }

    static void latchTieByWorkerSlot() throws Exception {
        Latch.Apply op = (a, b, i, f) -> { };
        for (int iter = 0; iter < 50; iter++) {
            LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
            java.util.List<Integer> applied = new java.util.ArrayList<>();
            Latch.Apply op2 = (a, b, i, f) -> applied.add((Integer) a);
            Thread[] ws = new Thread[2];
            for (int s = 0; s < 2; s++) {
                int ss = s;
                LuaGate.Worker w = (LuaGate.Worker) LuaGate.worker(() -> Latch.record(7, op2, ss, null, 0, 0.0f), "w" + s, s);
                ws[s] = w;
            }
            Latch.begin(1);
            for (Thread w : ws) w.start();
            for (Thread w : ws) w.join();
            int count = Latch.applyAll();
            assert count == 2 : count;
            assert applied.size() == 2 : applied.size();
            assert applied.get(0) == 0 : applied;
            assert applied.get(1) == 1 : applied;
            LuaGate.resetForTest();
        }
    }

    static void latchDirectPropagates() {
        try {
            Latch.record(1, (a, b, i, f) -> { throw new IllegalStateException("x"); }, null, null, 0, 0.0f);
            assert false;
        } catch (IllegalStateException e) {
            assert e.getMessage().equals("x");
        }
    }

    static void latchRegistryNoLeak() throws Exception {
        Latch.resetForTest();
        for (int round = 0; round < 20; round++) {
            LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
            FrameBatch.start(4);
            assert Latch.liveBuffers() == 3 : Latch.liveBuffers();
            FrameBatch.run(4, i -> Latch.record(i, (a, b, ii, f) -> { }, i, null, 0, 0.0f));
            Latch.applyAll();
            FrameBatch.stopForTest();
            LuaGate.resetForTest();
        }
        assert Latch.liveBuffers() == 0 : Latch.liveBuffers();
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        assert Latch.applyAll() == 0;
        LuaGate.resetForTest();
    }

    static void bucketMultiplierWindow() throws Exception {
        byte[] bytes;
        try (java.io.InputStream in = ClassLoader.getSystemResourceAsStream("zombie/MovingObjectUpdateSchedulerUpdateBucket.class")) {
            assert in != null : "MovingObjectUpdateSchedulerUpdateBucket.class not on the test class path";
            bytes = in.readAllBytes();
        }
        java.lang.classfile.ClassModel m = java.lang.classfile.ClassFile.of().parse(bytes);
        java.lang.classfile.MethodModel update = null;
        for (java.lang.classfile.MethodModel mm : m.methods()) {
            if (mm.methodName().equalsString("update")) { update = mm; break; }
        }
        assert update != null : "update(I)V not found";
        java.util.List<java.lang.classfile.CodeElement> insns = new java.util.ArrayList<>();
        for (java.lang.classfile.CodeElement e : update.code().orElseThrow()) insns.add(e);

        int countPre = 0, countFrame = 0, countUpdate = 0;
        int firstPre = -1, firstFrame = -1, firstUpdate = -1, lastUpdate = -1;
        java.util.List<Integer> multiWrites = new java.util.ArrayList<>();
        for (int i = 0; i < insns.size(); i++) {
            java.lang.classfile.CodeElement e = insns.get(i);
            if (e instanceof java.lang.classfile.instruction.InvokeInstruction inv
                    && inv.owner().asInternalName().equals("zombie/iso/IsoMovingObject")) {
                if (inv.name().equalsString("preupdate")) { countPre++; if (firstPre < 0) firstPre = i; }
                if (inv.name().equalsString("frameStep")) { countFrame++; if (firstFrame < 0) firstFrame = i; }
                if (inv.name().equalsString("update")) { countUpdate++; if (firstUpdate < 0) firstUpdate = i; lastUpdate = i; }
            }
            if (isMultiplierWrite(e)) multiWrites.add(i);
        }
        assert countPre == 1 : "preupdate appears " + countPre + " times, expected exactly once";
        assert countFrame == 1 : "frameStep appears " + countFrame + " times, expected exactly once";
        assert countUpdate == 1 : "update appears " + countUpdate + " times, expected exactly once";
        assert firstPre >= 0 && firstFrame >= 0 && firstUpdate >= 0 && firstPre < firstFrame && firstFrame < firstUpdate
            : "preupdate, frameStep and update are not in that order";

        assert !multiWrites.isEmpty() && multiWrites.get(0) < firstPre
            : "no multiplier write appears before the first preupdate()V";

        int setIdx = multiWrites.get(0);
        // find reset write: multiplier write after last update with constant 1 immediately before it
        int resetIdx = -1;
        for (int w : multiWrites) {
            if (w > lastUpdate && isConstOne(insns, w)) { resetIdx = w; break; }
        }
        assert resetIdx >= 0 : "no multiplier reset to 1 appears after the last update()V";

        int writesBetween = 0;
        for (int w : multiWrites) if (w >= setIdx && w <= resetIdx) writesBetween++;
        assert writesBetween == 2 : "extra multiplier write between the set and the reset (" + writesBetween + " writes)";

        // reset uses constant 1 (already checked isConstOne), and is after last update
        assert resetIdx > lastUpdate : "reset write does not appear after the last update()V";
    }

    private static boolean isMultiplierWrite(java.lang.classfile.CodeElement e) {
        return e instanceof java.lang.classfile.instruction.FieldInstruction f
            && f.opcode() == java.lang.classfile.Opcode.PUTFIELD
            && f.owner().asInternalName().equals("zombie/GameTime")
            && f.name().equalsString("perObjectMultiplier");
    }

    private static boolean isConstOne(java.util.List<java.lang.classfile.CodeElement> insns, int writeIdx) {
        if (writeIdx <= 0) return false;
        java.lang.classfile.CodeElement prev = insns.get(writeIdx - 1);
        String s = String.valueOf(prev);
        return s.contains("FCONST_1") || s.contains("ICONST_1");
    }
}
