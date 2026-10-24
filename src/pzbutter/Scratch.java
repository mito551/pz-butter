package pzbutter;

/** Per-thread scratch accessor called by transformed game code. Task 5 owns this class. */
final class Scratch {
    private Scratch() {}

    static Object get(int k) { throw new UnsupportedOperationException("Task 5"); }
}
