package pzbutter;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Benchmarks for the Plan 2 hot-path units. Usage: DeliveryBench parker | stamps | learner.
 * parker:  oversleep of park(1 ms) and park(4 ms) (median, p99), cross-thread wake latency (median, p99),
 *          parked thread CPU per park(4 ms). Lower is better for all.
 * stamps:  ns and bytes per onPush + onAcquire pair, three states in rotation.
 * learner: ns per observe() over a fixed 240 fps trace; prints a checksum of every hold, which must not change.
 */
public final class DeliveryBench {
    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        switch (args.length > 0 ? args[0] : "") {
            case "parker" -> parker();
            case "stamps" -> stamps();
            case "learner" -> learner();
            default -> throw new IllegalArgumentException("parker | stamps | learner");
        }
    }

    static String us(long[] ns) {
        Arrays.sort(ns);
        return String.format("median %7.1f us, p99 %7.1f us", ns[ns.length / 2] / 1e3, ns[ns.length * 99 / 100] / 1e3);
    }

    static void parker() throws Exception {
        Parker p = new Parker();
        for (int i = 0; i < 50; i++) p.park(1_000_000L);   // warm-up
        for (long target : new long[] {1_000_000L, 4_000_000L}) {
            long[] over = new long[300];
            for (int i = 0; i < over.length; i++) {
                long t0 = System.nanoTime();
                p.park(target);
                over[i] = Math.max(0, System.nanoTime() - t0 - target);
            }
            System.out.printf("oversleep %d ms: %s%n", target / 1_000_000L, us(over));
        }
        long[] lat = new long[300];
        long[] wakeAt = new long[1];
        java.util.concurrent.atomic.AtomicBoolean parked = new java.util.concurrent.atomic.AtomicBoolean();
        Parker q = new Parker();
        for (int i = 0; i < lat.length; i++) {
            parked.set(false);
            int idx = i;
            Thread w = new Thread(() -> { parked.set(true); q.park(50_000_000L); lat[idx] = System.nanoTime() - wakeAt[0]; });
            w.start();
            while (!parked.get()) Thread.onSpinWait();
            Thread.sleep(2);
            wakeAt[0] = System.nanoTime();
            q.wake();
            w.join();
        }
        System.out.printf("wake latency: %s%n", us(lat));
        long[] cpu = new long[100];
        for (int i = 0; i < cpu.length; i++) {
            long c0 = MX.getCurrentThreadCpuTime();
            p.park(4_000_000L);
            cpu[i] = MX.getCurrentThreadCpuTime() - c0;
        }
        System.out.printf("cpu per park(4 ms): %s%n", us(cpu));
    }

    static void stamps() {
        Object[] s = {new Object(), new Object(), new Object()};
        int n = 2_000_000;
        double[] ns = new double[7], bytes = new double[7];
        for (int r = -1; r < 7; r++) {
            long a0 = MX.getCurrentThreadAllocatedBytes(), t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                FrameClock.frameNo = i;
                FrameClock.frameStartNs = i;
                FrameStamps.onPush(s[i % 3]);
                FrameStamps.onAcquire(s[(i + 2) % 3]);
            }
            long t1 = System.nanoTime(), a1 = MX.getCurrentThreadAllocatedBytes();
            // last pair: the acquired state was last pushed at i = n - 2, which stamps frame n - 1
            if (FrameStamps.renderFrameNo != n - 1) throw new IllegalStateException("wrong stamps: " + FrameStamps.renderFrameNo);
            if (r >= 0) { ns[r] = (t1 - t0) / (double) n; bytes[r] = (a1 - a0) / (double) n; }
        }
        Arrays.sort(ns); Arrays.sort(bytes);
        System.out.printf("stamps: %6.2f ns/pair (min %.2f, max %.2f), %4.1f B/pair%n", ns[3], ns[0], ns[6], bytes[3]);
    }

    static void learner() {
        long period = 1_000_000_000L / 240;
        double[] ns = new double[7];
        long check = 0;
        for (int r = -1; r < 7; r++) {
            SwapHold.Learner l = new SwapHold.Learner(new SwapHold.Config(260, 260, 100, 60, 1000, 25, 20));
            long seed = 42, sum = 0, now = 1_000_000_000L;
            int n = 240 * 120;
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                long ready = 1_000_000L + (seed >>> 40) % 7_000_000L + ((seed >>> 20) % 97 == 0 ? 20_000_000L : 0);
                now += period;
                l.observe(ready, now, period);
                sum = sum * 31 + l.holdNs();
            }
            long t1 = System.nanoTime();
            if (r >= 0) ns[r] = (t1 - t0) / (double) n;
            check = sum;
        }
        Arrays.sort(ns);
        System.out.printf("learner: %6.1f ns/observe (min %.1f, max %.1f), checksum %d%n", ns[3], ns[0], ns[6], check);
    }
}
