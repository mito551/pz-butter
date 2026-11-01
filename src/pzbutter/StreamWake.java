package pzbutter;

import java.util.concurrent.locks.LockSupport;

/**
 * wake (Plan 10b Task 3): WakeTransform rewrites WorldStreamer.threadLoop's four Thread.sleep(J)V calls to {@link #sleep} and
 * inserts {@link #loopStart} at its entry. An idle pass sleeps twice in a row (140 ms at 450, then 140 ms at 492) and polls
 * jobQueue only at the top (118-172), so the "job queued" flag is cleared at the top, never in sleep: a job added during either
 * sleep ends both. An add after the clear is seen by the poll; an add after the poll sets the flag for the next sleep.
 *
 * streamTiming: the add-to-pickup latency of the oldest waiting job, sampled on the streamer thread only. The minute reset races
 * the streamer's sums; a sample can be lost (diagnostic only).
 */
public final class StreamWake {
    private StreamWake() {}

    public static final Guard guard = new Guard("wake");

    /** Whether WakeTransform was installed during premain, and why not. */
    public static volatile boolean transformState;
    public static volatile String transformReason = "wake off";

    private static volatile Thread streamerThread;
    private static volatile boolean jobQueued;

    /** Calls of the rewritten sleep with the guard on, and those that returned early for a queued job (minute line). */
    public static volatile long sleeps, woken;

    public static volatile long latencySumNs;
    public static volatile long latencyMaxNs;
    public static volatile long latencyCount;

    public static volatile boolean timing;
    private static volatile boolean queued;
    private static volatile long queuedNs;

    static void init() {
        try {
            guard.on = Settings.enabled && Settings.flag("wake");
            timing = Settings.enabled && Settings.flag("streamTiming");
            if (guard.on) System.out.println("[PZButter] wake: transform " + (transformState ? "active" : "off (" + transformReason + ")"));
        } catch (Throwable t) { guard.fail(t); guard.on = false; timing = false; }
    }

    public static void resetForTest() {
        guard.on = false;
        timing = false;
        streamerThread = null;
        jobQueued = false;
        sleeps = woken = 0;
        latencySumNs = 0;
        latencyMaxNs = 0;
        latencyCount = 0;
        queued = false;
        queuedNs = 0;
    }

    /** WorldStreamer.threadLoop entry (streamer thread), inserted by WakeTransform: before this pass polls jobQueue. */
    public static void loopStart() {
        if (!guard.on) return;
        streamerThread = Thread.currentThread();
        jobQueued = false;
    }

    /** Replaces Thread.sleep(J)V in WorldStreamer.threadLoop. Same exceptions as Thread.sleep. */
    public static void sleep(long ms) throws InterruptedException {
        if (!guard.on || ms <= 0) { Thread.sleep(ms); return; }
        streamerThread = Thread.currentThread();
        sleeps++;
        if (Thread.interrupted()) throw new InterruptedException();
        long deadline = System.nanoTime() + ms * 1_000_000L;
        while (true) {
            if (jobQueued) { woken++; return; }
            long remain = deadline - System.nanoTime();
            if (remain <= 0) return;
            LockSupport.parkNanos(remain);
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    /** WorldStreamer.addJob exit (game thread). */
    public static void jobAdded() {
        if (guard.on && !jobQueued) {   // already set: the streamer has not passed the loop top since, nothing to wake
            jobQueued = true;
            Thread t = streamerThread;
            if (t != null) LockSupport.unpark(t);
        }
        if (timing && !queued) {   // keep the oldest waiting job's stamp
            queuedNs = System.nanoTime();
            queued = true;
        }
    }

    /** WorldStreamer.DoChunkAlways entry: a pickup on the streamer thread (addJobInstant/addJobWipe call it on their own thread). */
    public static void pickup() {
        if (!timing || !queued) return;
        Thread s = streamerThread;
        if (s != null && Thread.currentThread() != s) return;
        long d = System.nanoTime() - queuedNs;
        latencySumNs += d;
        if (d > latencyMaxNs) latencyMaxNs = d;
        latencyCount++;
        queued = false;
    }
}
