package pzbutter;

/** A feature's on switch and failure count: the 5th failure switches it off with one log line. */
public final class Guard {
    public final String name;
    public volatile boolean on;
    private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();

    public Guard(String name) { this.name = name; }

    public void fail(Throwable t) {
        try {
            if (failures.incrementAndGet() >= 5) {
                synchronized (this) {
                    if (!on) return;
                    on = false;
                    System.out.println("[PZButter] " + name + " off after repeated failures: " + t);
                }
            }
        } catch (Throwable ignored) { }
    }
}
