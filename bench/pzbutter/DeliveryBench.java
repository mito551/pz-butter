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
 * uislice: ns per entry for UiSlice.replay and UiSlice.record over 200-entry slices (all glDraw; 10 % state entries;
 *          10 % entries with a 4-float vars array); prints a checksum of the replayed state, which must not change.
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
            case "uislice" -> uislice();
            default -> throw new IllegalArgumentException("parker | stamps | learner | widths | rawget | uislice");
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

    static zombie.core.sprite.GenericSpriteRenderState sliceState(int n) {
        zombie.core.sprite.GenericSpriteRenderState s = new zombie.core.sprite.GenericSpriteRenderState(0) { };
        for (int i = 0; i < n; i++) { s.numSprites = i; s.CheckSpriteSlots(); }
        s.numSprites = 0;
        return s;
    }

    static void uislice() {
        zombie.core.textures.TextureDraw.Type[] states = {zombie.core.textures.TextureDraw.Type.glStencilFunc,
            zombie.core.textures.TextureDraw.Type.glEnable, zombie.core.textures.TextureDraw.Type.glBlendFunc};
        int n = 200;
        for (String scen : new String[] {"draw", "state", "vars"}) {
            zombie.core.sprite.GenericSpriteRenderState src = sliceState(n);
            for (int i = 0; i < n; i++) {
                zombie.core.textures.TextureDraw d = src.sprite[i];
                boolean odd = i % 10 == 3;
                d.type = scen.equals("state") && odd ? states[i % 3] : zombie.core.textures.TextureDraw.Type.glDraw;
                d.a = i; d.b = i * 3; d.c = i * 5; d.col0 = i * 7; d.col1 = i * 11; d.col2 = i * 13; d.col3 = i * 17;
                d.x0 = i; d.x1 = i + 1; d.x2 = i + 2; d.x3 = i + 3; d.y0 = -i; d.y1 = -i - 1; d.y2 = -i - 2; d.y3 = -i - 3;
                d.u0 = i * .5f; d.u1 = i * .25f; d.v0 = i * .125f; d.v1 = i * .0625f; d.z = i * .001f; d.singleCol = (i & 1) == 0;
                if (scen.equals("vars") && odd) d.vars = new float[] {i, i + .5f, i + .25f, i + .125f};
                src.style[i] = null;
                src.numSprites = i + 1;
            }
            UiSlice sl = UiSlice.record(src, 0, n, null);
            zombie.core.sprite.GenericSpriteRenderState dst = sliceState(n);
            int reps = 100_000;
            double[] ns = new double[7];
            for (int r = -2; r < 7; r++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < reps; k++) { dst.numSprites = 0; UiSlice.replay(sl, dst); }
                if (r >= 0) ns[r] = (System.nanoTime() - t0) / (double) reps / n;
            }
            long check = 0;
            for (int i = 0; i < dst.numSprites; i++) {
                zombie.core.textures.TextureDraw d = dst.sprite[i];
                check = check * 31 + d.type.ordinal() + d.a + d.col3 + Float.floatToRawIntBits(d.x3) + Float.floatToRawIntBits(d.v1)
                    + (d.singleCol ? 1 : 0) + (d.vars == null ? 0 : Float.floatToRawIntBits(d.vars[3]) + 7L * d.vars.length);
            }
            Arrays.sort(ns);
            System.out.printf("uislice replay %-5s: %6.2f ns/entry (min %.2f, max %.2f), checksum %x%n", scen, ns[3], ns[0], ns[6], check);
            double[] rs = new double[7];
            UiSlice keep = sl;
            for (int r = -2; r < 7; r++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < reps; k++) keep = UiSlice.record(src, 0, n, keep);
                if (r >= 0) rs[r] = (System.nanoTime() - t0) / (double) reps / n;
            }
            Arrays.sort(rs);
            System.out.printf("uislice record %-5s: %6.2f ns/entry (min %.2f, max %.2f), n %d%n", scen, rs[3], rs[0], rs[6], keep.n);
        }
    }
}
