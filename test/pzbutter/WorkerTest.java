package pzbutter;

/** Plan 8b checks (frame workers; Task 2: the list resource is on the class path). Run from SelfTest.main. */
final class WorkerTest {
    private WorkerTest() {}

    static void run() throws Exception {
        workersResourceInJar();
    }

    static void workersResourceInJar() {
        assert WorkerTest.class.getResource("/pzbutter/workers-4a0e9546ec.txt") != null;
    }
}
