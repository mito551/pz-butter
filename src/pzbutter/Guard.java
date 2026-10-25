package pzbutter;

/** A feature's on switch and failure count: the 5th failure switches it off with one log line. */
public final class Guard {
    public final String name;
    public volatile boolean on;
    private int failures;

    public Guard(String name) { this.name = name; }

    public void fail(Throwable t) {
        try {
            if (++failures >= 5 && on) {
                on = false;
                System.out.println("[PZButter] " + name + " off after repeated failures: " + t);
            }
        } catch (Throwable ignored) { }
    }
}
