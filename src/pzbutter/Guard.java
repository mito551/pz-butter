package pzbutter;

/** A feature's on switch and failure count: the 5th failure switches it off with one log line. */
final class Guard {
    final String name;
    volatile boolean on;
    private int failures;

    Guard(String name) { this.name = name; }

    void fail(Throwable t) {
        try {
            if (++failures >= 5 && on) {
                on = false;
                System.out.println("[PZButter] " + name + " off after repeated failures: " + t);
            }
        } catch (Throwable ignored) { }
    }
}
