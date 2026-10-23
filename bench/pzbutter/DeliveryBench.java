package pzbutter;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Benchmarks for the Plan 2 hot-path units. Usage: DeliveryBench parker | stamps | learner.
 * parker:  oversleep of park(1 ms) and park(4 ms) (median, p99), cross-thread wake latency (median, p99),
 *          parked thread CPU per park(4 ms). Lower is better for all.
 * stamps:  ns and bytes per onPush + onAcquire pair, three states in rotation.
 * learner: ns per observe() over a fixed 240 fps trace; prints a checksum of every hold, which must not change.
 * rawget:  ns per lookup, stock KahluaTableImpl.rawget against the luaRawgetFast hook; hits, then misses that ask a metatable.
 * soundgate: ns per SoundPatches.skipTick() call with soundTickHz=60, at 2000 calls a frame (a horde's sound parameters)
 *          and at 3 calls a frame (ambience only); frames advance every N calls.
 * cutaway: ns per cutawayFast replay (Cutaways.replayable + replay, as occludingEnter) over 256 chunk levels, for sparse,
 *          dense and box-clipped masks; prints a grid checksum, which must not change.
 * splash:  ns per Weather.updateSplashes (SplittableRandom overload) per 64-square chunk level, after warm-up to the
 *          steady state, for light rain (n 2000), heavy rain (n 400) and heavy rain at 30 fps (n 100); prints the
 *          trigger rate per idle square, which must stay near 1/n.
 */
public final class DeliveryBench {
    static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        switch (args.length > 0 ? args[0] : "") {
            case "parker" -> parker();
            case "stamps" -> stamps();
            case "learner" -> learner();
            case "widths" -> widths();
            case "rawget" -> rawget();
            case "soundgate" -> soundgate();
            case "cutaway" -> cutaway();
            case "splash" -> splash();
            default -> throw new IllegalArgumentException("parker | stamps | learner | widths | rawget | soundgate | cutaway | splash");
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
            SwapHold.Learner l = new SwapHold.Learner(new SwapHold.Config(100, 0));
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

    /** TextWidths: get, and put on a miss, over 2000 labels in 4 fonts; about 90 % hits after the first pass. */
    static void widths() {
        Object[] fonts = {new Object(), new Object(), new Object(), new Object()};
        String[] labels = new String[2200];
        for (int i = 0; i < labels.length; i++) labels[i] = "Context option " + i;
        int n = 2_000_000;
        double[] ns = new double[7], bytes = new double[7];
        long check = 0;
        for (int r = -1; r < 7; r++) {
            TextWidths.clear();
            long seed = 7, sum = 0;
            long a0 = MX.getCurrentThreadAllocatedBytes(), t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                int li = (int) ((seed >>> 33) % (((seed >>> 20) & 15) == 0 ? labels.length : 2000));
                Object f = fonts[(int) (seed >>> 60) & 3];
                Integer w = TextWidths.get(f, labels[li]);
                if (w == null) { w = li * 3 + 7; TextWidths.put(f, labels[li], w); }
                sum += w;
            }
            long t1 = System.nanoTime(), a1 = MX.getCurrentThreadAllocatedBytes();
            if (r >= 0) { ns[r] = (t1 - t0) / (double) n; bytes[r] = (a1 - a0) / (double) n; }
            check = sum;
        }
        Arrays.sort(ns); Arrays.sort(bytes);
        System.out.printf("widths: %6.2f ns/op (min %.2f, max %.2f), %5.2f B/op, checksum %d%n", ns[3], ns[0], ns[6], bytes[3], check);
    }

    static void rawget() {
        se.krka.kahlua.j2se.KahluaTableImpl meta = new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.HashMap<>());
        se.krka.kahlua.j2se.KahluaTableImpl t = new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.HashMap<>());
        String[] hit = new String[64], miss = new String[64];
        for (int i = 0; i < 64; i++) { hit[i] = "field" + i; miss[i] = "method" + i; t.rawset(hit[i], (double) i); meta.rawset(miss[i], (double) i); }
        t.setMetatable(meta);
        LuaFast.rawgetFast = LuaPatches.rawgetGuard.on = true;
        for (String[] keys : new String[][] {hit, miss}) {
            for (boolean hook : new boolean[] {false, true}) {
                int n = 20_000_000;
                double[] ns = new double[7];
                long check = 0;
                for (int r = -1; r < 7; r++) {
                    long sum = 0, t0 = System.nanoTime();
                    for (int i = 0; i < n; i++) {
                        Object v = hook ? LuaPatches.rawgetFast(t, keys[i & 63]) : t.rawget(keys[i & 63]);
                        sum += ((Double) v).longValue();
                    }
                    if (r >= 0) ns[r] = (System.nanoTime() - t0) / (double) n;
                    check = sum;
                }
                Arrays.sort(ns);
                System.out.printf("rawget %s %s: %6.2f ns/op (min %.2f, max %.2f), checksum %d%n",
                    keys == hit ? "hit " : "miss", hook ? "hook " : "stock", ns[3], ns[0], ns[6], check);
            }
        }
    }

    static void soundgate() throws Exception {
        java.io.File f = java.io.File.createTempFile("pzb-bench", ".properties");
        f.deleteOnExit();
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write("enabled=true\nsoundTickHz=60\n"); }
        Settings.load(f);
        SoundTick.init();
        FrameClock.on = true;   // the gate reads a frame counter only while it runs
        for (int perFrame : new int[] {2000, 3}) {
            int n = 30_000_000;
            double[] ns = new double[7];
            long skipped = 0;
            for (int r = -1; r < 7; r++) {
                long sk = 0, t0 = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    if (i % perFrame == 0) FrameClock.frameNo++;
                    if (SoundPatches.skipTick()) sk++;
                }
                if (r >= 0) ns[r] = (System.nanoTime() - t0) / (double) n;
                skipped = sk;
            }
            Arrays.sort(ns);
            System.out.printf("soundgate %4d/frame: %6.3f ns/call (min %.3f, max %.3f), skipped %d%n", perFrame, ns[3], ns[0], ns[6], skipped);
        }
    }

    static void cutaway() {
        int levels = 256, gw = 60;
        // scenario: name, bits set per mask, box x origin (0 = chunk inside the box, 20 = box cuts the chunk)
        Object[][] scen = {{"sparse", 8, 0}, {"dense", 48, 0}, {"clipped", 24, 20}};
        for (Object[] s : scen) {
            int bits = (Integer) s[1], gx1 = (Integer) s[2], gy1 = 0, gx2 = gx1 + gw - 1, gy2 = gw - 1;
            java.util.Random r = new java.util.Random(7);
            Cutaways.Fast[] fs = new Cutaways.Fast[levels];
            long[] stored = new long[levels];
            int[] wx = new int[levels], wy = new int[levels], z = new int[levels];
            for (int l = 0; l < levels; l++) {
                long t = 0;
                while (Long.bitCount(t) < bits) t |= 1L << r.nextInt(64);
                Cutaways.Fast f = new Cutaways.Fast();
                int p = l & 3;
                Cutaways.beginCompute(f, p, gx1, gy1, gx2, gy2);
                f.truth[p] = t;
                Cutaways.endCompute(f, p);
                fs[l] = f;
                stored[l] = Cutaways.lossy(t);
                wx[l] = 2 + (l & 3); wy[l] = 2 + ((l >> 2) & 3); z[l] = (l >> 4) % 3;
            }
            int[] grid = new int[gw * gw];
            int n = 40_000;
            double[] ns = new double[7];
            long sum = 0;
            for (int run = -2; run < 7; run++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < n; k++) {
                    for (int l = 0; l < levels; l++) {
                        int p = l & 3;
                        if (Cutaways.replayable(fs[l], p, gx1, gy1, gx2, gy2, stored[l]))
                            Cutaways.replay(fs[l].truth[p], wx[l], wy[l], z[l], gx1, gy1, gx2, gy2, gx2 - gx1 + 1, grid);
                    }
                    if ((k & 63) == 0) Arrays.fill(grid, -32);
                }
                if (run >= 0) ns[run] = (System.nanoTime() - t0) / (double) n / levels;
                sum = 0;
                for (int i = 0; i < grid.length; i++) sum = sum * 31 + grid[i];
            }
            Arrays.sort(ns);
            System.out.printf("cutaway %-7s: %6.3f ns/level (min %.3f, max %.3f), checksum %x%n", s[0], ns[3], ns[0], ns[6], sum);
        }
    }

    static void splash() {
        int levels = 256;
        for (int n : new int[] {2000, 400, 100}) {
            java.util.SplittableRandom r = new java.util.SplittableRandom(11);
            float step = 0.08f * (30.0f / 120f);
            float[][] frame = new float[levels][64];
            byte[][] flags = new byte[levels][64];
            for (float[] f : frame) Arrays.fill(f, -1f);
            for (int k = 0; k < 2000; k++) for (int l = 0; l < levels; l++) Weather.updateSplashes(frame[l], flags[l], n, step, r);
            int frames = 4000;
            double[] ns = new double[7];
            long idle = 0, trig = 0;
            for (int run = -2; run < 7; run++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < frames; k++) for (int l = 0; l < levels; l++) Weather.updateSplashes(frame[l], flags[l], n, step, r);
                if (run >= 0) ns[run] = (System.nanoTime() - t0) / (double) frames / levels;
            }
            for (int k = 0; k < 200; k++) for (int l = 0; l < levels; l++) {   // rate check, outside the timing
                float[] f = frame[l];
                int before = 0; for (float v : f) if (v < 0) before++;
                Weather.updateSplashes(f, flags[l], n, step, r);
                idle += before;
                for (float v : f) if (v == 0f) trig++;
            }
            Arrays.sort(ns);
            System.out.printf("splash n %4d: %6.3f ns/level (min %.3f, max %.3f), rate x n %.3f%n", n, ns[3], ns[0], ns[6], trig * (double) n / idle);
        }
    }
}
