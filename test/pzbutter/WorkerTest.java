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
        scratchListArrays();
        transformRewritesArrayRead();
        transformVerifiesOnGameClasses();
        scratchGivesEachWorkerItsCopy();
        scratchKeepsOriginalForOtherThreads();
        scratchCopiesArrays();
        scratchProbeReportsUncopyable();
        scratchUnresolvableOwner();
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
}
