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
}
