package pzbutter;

import java.util.concurrent.locks.LockSupport;

/** One thread's bounded park that another thread can cut short. */
final class Parker {
    static final long MAX_NS = 50_000_000L;
    private volatile Thread parked;

    void park(long ns) {
        parked = Thread.currentThread();
        try { LockSupport.parkNanos(Math.min(ns, MAX_NS)); }
        finally { parked = null; }
    }

    void wake() {
        Thread t = parked;
        if (t != null) LockSupport.unpark(t);
    }
}
