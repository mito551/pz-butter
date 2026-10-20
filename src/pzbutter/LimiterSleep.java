package pzbutter;

/**
 * limiterSleep (SPEC 3.12): the game's frame limiter (GameWindow.mainThreadStep, called in a yield loop) waits
 * by spinning. Before each check this parks the game thread until 0.2 ms before the frame is due; the spin
 * covers only that. Work queued for the main thread wakes it at once, so callers waiting on it are not held.
 */
final class LimiterSleep {
    static final Guard guard = new Guard("limiterSleep");
    static final long SPIN_NS = 200_000L;
    static final long MAX_NS = Parker.MAX_NS;
    private static final Parker parker = new Parker();

    private LimiterSleep() {}

    static void init() { guard.on = Settings.flag("limiterSleep"); }

    static boolean active(boolean uncapped, int lockFps) { return !uncapped && lockFps > 0; }

    /**
     * ns to park before the limiter's next check. mainThreadStep adds (now - currentTime) to the accumulator and
     * runs the frame once the accumulator reaches the period.
     */
    static long sleepNanos(long now, long currentTime, long accumulator, long periodNs, long spinNs) {
        if (now < currentTime) return 0;
        long s = periodNs - accumulator - (now - currentTime) - spinNs;
        return s <= 0 ? 0 : Math.min(s, MAX_NS);
    }

    static void park(long ns) { parker.park(ns); }

    static void wake() { parker.wake(); }
}
