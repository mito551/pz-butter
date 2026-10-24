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
}
