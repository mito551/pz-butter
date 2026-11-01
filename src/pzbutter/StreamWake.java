package pzbutter;

import java.util.concurrent.locks.LockSupport;

public final class StreamWake {
    private StreamWake() {}

    public static final Guard guard = new Guard("wake");

    /** Whether WakeTransform was installed during premain. */
    public static volatile boolean transformState;

    private static volatile Thread streamerThread;
    private static volatile boolean jobQueued;

    public static volatile long latencySumNs;
    public static volatile long latencyMaxNs;
    public static volatile long latencyCount;

    private static volatile boolean queued;
    private static volatile long queuedNs;

    static void init() {
        try { guard.on = Settings.enabled && Settings.flag("wake"); }
        catch (Throwable t) { guard.fail(t); guard.on = false; }
    }

    public static void resetForTest() {
        guard.on = false;
        streamerThread = null;
        jobQueued = false;
        latencySumNs = 0;
        latencyMaxNs = 0;
        latencyCount = 0;
        queued = false;
        queuedNs = 0;
    }

    public static void sleep(long ms) throws InterruptedException {
        streamerThread = Thread.currentThread();
        if (!guard.on) {
            Thread.sleep(ms);
            return;
        }
        if (Thread.interrupted()) throw new InterruptedException();
        long deadline = System.nanoTime() + ms * 1_000_000L;
        if (jobQueued) { jobQueued = false; return; }
        try {
            while (true) {
                if (Thread.interrupted()) throw new InterruptedException();
                if (jobQueued) { jobQueued = false; return; }
                long remain = deadline - System.nanoTime();
                if (remain <= 0) return;
                LockSupport.parkNanos(remain);
                if (Thread.interrupted()) throw new InterruptedException();
                if (jobQueued) { jobQueued = false; return; }
                if (System.nanoTime() >= deadline) return;
            }
        } catch (InterruptedException e) {
            throw e;
        }
    }

    public static void jobAdded() {
        jobQueued = true;
        Thread t = streamerThread;
        if (t != null) LockSupport.unpark(t);
        if (Settings.flag("streamTiming")) {
            queuedNs = System.nanoTime();
            queued = true;
        }
    }

    public static void pickup() {
        if (!Settings.flag("streamTiming")) return;
        if (!queued) return;
        long now = System.nanoTime();
        long d = now - queuedNs;
        latencySumNs += d;
        if (d > latencyMaxNs) latencyMaxNs = d;
        latencyCount++;
        queued = false;
    }
}