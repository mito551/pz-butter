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

    /** streamTiming: the add-to-pickup latency of the oldest job still waiting, per minute. */
    public static volatile boolean timing;
    private static volatile boolean queued;
    private static volatile long queuedNs;

    static void init() {
        try {
            guard.on = Settings.enabled && Settings.flag("wake");
            timing = Settings.enabled && Settings.flag("streamTiming");
        } catch (Throwable t) { guard.fail(t); guard.on = false; timing = false; }
    }

    public static void resetForTest() {
        guard.on = false;
        timing = false;
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

    /** WorldStreamer.addJob exit (game thread). */
    public static void jobAdded() {
        if (guard.on) {
            jobQueued = true;
            Thread t = streamerThread;
            if (t != null) LockSupport.unpark(t);
        }
        if (timing && !queued) {   // keep the oldest waiting job's stamp
            queuedNs = System.nanoTime();
            queued = true;
        }
    }

    /** WorldStreamer.DoChunkAlways entry (streamer thread): the streamer picked a job up. */
    public static void pickup() {
        if (!timing || !queued) return;
        long now = System.nanoTime();
        long d = now - queuedNs;
        latencySumNs += d;
        if (d > latencyMaxNs) latencyMaxNs = d;
        latencyCount++;
        queued = false;
    }
}
