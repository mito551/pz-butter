package pzbutter;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;

/**
 * Benchmarks for the Plan 2 hot-path units. Usage: DeliveryBench parker | stamps | learner.
 * parker:  oversleep of park(1 ms) and park(4 ms) (median, p99), cross-thread wake latency (median, p99),
 *          parked thread CPU per park(4 ms). Lower is better for all.
 * stamps:  ns and bytes per onPush + onAcquire pair, three states in rotation.
 * learner: ns per observe() over a fixed 240 fps trace; prints a checksum of every hold, which must not change.
 * rawget:  ns per lookup, stock KahluaTableImpl.rawget against the luaRawgetFast hook; hits, then misses that ask a metatable.
 * uislice: ns per entry for UiSlice.replay and UiSlice.record over 200-entry slices (all glDraw; 10 % state entries;
 *          10 % entries with a 4-float vars array); prints a checksum of the replayed state, which must not change.
 * readset: ns per read for UiRetain.ReadSet: add (recording), stillSame with every value unchanged, and stillSame with
 *          the value changed whose first read comes last; 200 and 2000 reads over real Kahlua tables, each (table, key) read 4 times per render.
 * soundgate: ns per SoundPatches.skipTick() call with soundTickHz=60, at 2000 calls a frame (a horde's sound parameters)
 *          and at 3 calls a frame (ambience only); frames advance every N calls.
 * cutaway: ns per cutawayFast replay (Cutaways.replayable + replay, as occludingEnter) over 256 chunk levels, for sparse,
 *          dense and box-clipped masks; prints a grid checksum, which must not change.
 * splash:  ns per Weather.updateSplashes (SplittableRandom overload) per 64-square chunk level, after warm-up to the
 *          steady state, for light rain (n 2000), heavy rain (n 400) and heavy rain at 30 fps (n 100); prints the
 *          trigger rate per idle square, which must stay near 1/n.
 * edge:    ns per isBlockedTo call, stock IsoGridSquare.isBlockedTo against EdgeTest.blockedTo, over a 3 x 3 patch with
 *          each square paired with its eight neighbours (outside ones are null); scenarios open (no objects), door (a
 *          blocked door on the middle square's north edge) and window (a blocked window on its west edge). Both must
 *          count the same blocked pairs (checked).
 * plan:    ns per BakeHold.plan call (one player, one frame) over 40 pairs all REDRAW; 120 mixed (10 % CUTAWAY, 30 % REDRAW,
 *          50 % LIGHT, 10 % BLOOD); 400 mostly LIGHT (dusk at the widest zoom). Prints a checksum of the grant array and the
 *          left* counts, which must not change between versions.
 * gate:    ns per frame of the bake-hold shims for one player: BakeHold.planChunks (classes, light delta, plan) then
 *          BakeHold.gate for every level of every chunk, on real FBORenderLevels (no GL). Scenarios: 60 clean levels (gate
 *          cost only), 60 lighting-dirty levels, 200 levels at the widest zoom (10 % redraw, 60 % light, 30 % clean; the
 *          light drifts every frame). Prints the number of held gate answers and a checksum of them, which must not change.
 * luagate: the Lua token (LuaGate), five scenarios, median of 5 runs after a warm-up. (1) ns per enter/exit pair on the game thread,
 *          key on, no worker in existence (100 M pairs); (2) the same with the key off; (3) 8 workers x 20,000 gated calls of a
 *          one-line Lua function while the holder waits in awaitYielding: total ms; (4) ns per yieldPoint() with no waiter;
 *          (5) 2 workers x 5,000 gated calls, one per item, while the holder does its own Lua work (500 calls) between
 *          yieldPoint() calls: total ms. Checked on every run: the Lua counter equals the number of calls and the owner fields
 *          are restored; a failed check aborts.
 * replay:  the event replay buffer (LuaReplay). 8 persistent threads x 250 captures with interleaved loop orders (a 2,000-zombie
 *          frame; thread k captures orders k, k+8, ...) released together: ns per capture (the sum of the threads' own loop
 *          times over the captures), then ns per replayed call on one thread; median of 7 runs after a warm-up. A second
 *          scenario is a quiet frame of 50 events (5 threads x 10). Prints a checksum of the fired order sequence, which must
 *          equal the sorted sequence's (checked) and not change between versions.
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
            case "readset" -> readset();
            case "soundgate" -> soundgate();
            case "cutaway" -> cutaway();
            case "splash" -> splash();
            case "edge" -> edge();
            case "plan" -> plan();
            case "gate" -> gate();
            case "luagate" -> luagate();
            case "replay" -> replay();
            default -> throw new IllegalArgumentException("parker | stamps | learner | widths | rawget | uislice | readset | soundgate | cutaway | splash | edge | plan | gate | luagate | replay");
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

    static void readset() {
        java.util.function.BiFunction<Object, Object, Object> read = (t, k) -> ((se.krka.kahlua.vm.KahluaTable) t).rawget(k);
        for (int reads : new int[] {200, 2000}) {
            int distinct = reads / 4, nt = Math.max(4, distinct / 10);
            se.krka.kahlua.j2se.KahluaTableImpl[] tables = new se.krka.kahlua.j2se.KahluaTableImpl[nt];
            for (int i = 0; i < nt; i++) tables[i] = new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.HashMap<>());
            Object[] rt = new Object[reads], rk = new Object[reads], rv = new Object[reads];
            java.util.SplittableRandom rnd = new java.util.SplittableRandom(7);
            for (int d = 0; d < distinct; d++) {
                se.krka.kahlua.j2se.KahluaTableImpl t = tables[d % nt];
                String k = ("field" + d).intern();
                Object v = switch (d % 3) { case 0 -> (double) d; case 1 -> "s" + d; default -> (d & 4) == 0; };
                t.rawset(k, v);
                for (int c = 0; c < 4; c++) { int at = d * 4 + c; rt[at] = t; rk[at] = k; }
            }
            for (int i = reads - 1; i > 0; i--) {   // shuffle so repeats are spread as in a render
                int j = rnd.nextInt(i + 1);
                Object a = rt[i]; rt[i] = rt[j]; rt[j] = a; a = rk[i]; rk[i] = rk[j]; rk[j] = a;
            }
            for (int i = 0; i < reads; i++) rv[i] = ((se.krka.kahlua.vm.KahluaTable) rt[i]).rawget(rk[i]);
            java.util.Map<Object, Integer> first = new java.util.HashMap<>();
            for (int i = 0; i < reads; i++) first.putIfAbsent(rk[i], i);
            int lastFirst = java.util.Collections.max(first.values());   // the key whose first read comes last
            UiRetain.ReadSet rs = new UiRetain.ReadSet();
            int reps = 4_000_000 / reads;
            double[] add = new double[7], same = new double[7], changed = new double[7];
            long check = 0;
            for (int r = -2; r < 7; r++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < reps; k++) { rs.clear(); for (int i = 0; i < reads; i++) rs.add(rt[i], rk[i], rv[i]); }
                long t1 = System.nanoTime();
                int ok = 0;
                for (int k = 0; k < reps; k++) if (rs.stillSame(read)) ok++;
                long t2 = System.nanoTime();
                se.krka.kahlua.vm.KahluaTable last = (se.krka.kahlua.vm.KahluaTable) rt[lastFirst];
                Object old = last.rawget(rk[lastFirst]);
                last.rawset(rk[lastFirst], "changed");
                int bad = 0;
                for (int k = 0; k < reps; k++) if (!rs.stillSame(read)) bad++;
                long t3 = System.nanoTime();
                last.rawset(rk[lastFirst], old);
                if (r >= 0) {
                    add[r] = (t1 - t0) / (double) reps / reads; same[r] = (t2 - t1) / (double) reps / reads; changed[r] = (t3 - t2) / (double) reps / reads;
                }
                check = check * 31 + ok * 7L + bad + rs.size();
            }
            Arrays.sort(add); Arrays.sort(same); Arrays.sort(changed);
            System.out.printf("readset %4d add    : %6.2f ns/read (min %.2f, max %.2f)%n", reads, add[3], add[0], add[6]);
            System.out.printf("readset %4d same   : %6.2f ns/read (min %.2f, max %.2f)%n", reads, same[3], same[0], same[6]);
            System.out.printf("readset %4d changed: %6.2f ns/read (min %.2f, max %.2f), checksum %x%n", reads, changed[3], changed[0], changed[6], check);
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

    static final class BenchDoor extends zombie.iso.objects.IsoDoor {
        boolean facingNorth, isBlk;
        BenchDoor() { super((zombie.iso.IsoCell) null); }
        @Override public boolean getNorth() { return facingNorth; }
        @Override public boolean isBlocked() { return isBlk; }
    }

    static final class BenchWindow extends zombie.iso.objects.IsoWindow {
        boolean facingNorth, isBlk;
        BenchWindow() { super((zombie.iso.IsoCell) null); }
        @Override public boolean getNorth() { return facingNorth; }
        @Override public boolean isBlocked() { return isBlk; }
    }

    static void edge() {
        for (String sc : new String[] {"open", "door", "window"}) {
            IsoGridSquare[][] g = new IsoGridSquare[3][3];
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) g[x][y] = new IsoGridSquare(null, null, x, y, 0);
            IsoGridSquare.GetSquare gs = (x, y, z) -> x >= 0 && y >= 0 && x < 3 && y < 3 && z == 0 ? g[x][y] : null;
            if (sc.equals("door")) { BenchDoor d = new BenchDoor(); d.facingNorth = true; d.isBlk = true; g[1][1].getObjects().add(d); }
            if (sc.equals("window")) { BenchWindow w = new BenchWindow(); w.facingNorth = false; w.isBlk = true; g[1][1].getObjects().add(w); }
            java.util.List<IsoGridSquare> as = new java.util.ArrayList<>(), bs = new java.util.ArrayList<>();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                as.add(g[x][y]);
                bs.add(gs.getGridSquare(x + dx, y + dy, 0));
            }
            IsoGridSquare[] a = as.toArray(new IsoGridSquare[0]), b = bs.toArray(new IsoGridSquare[0]);
            int n = a.length, reps = 200_000;
            double[] stockNs = new double[7], edgeNs = new double[7];
            long stockHits = 0, edgeHits = 0;
            for (int r = -2; r < 7; r++) {
                long hits = 0, t0 = System.nanoTime();
                for (int k = 0; k < reps; k++) for (int i = 0; i < n; i++) if (a[i].isBlockedTo(b[i], gs)) hits++;
                long t1 = System.nanoTime();
                long eh = 0;
                for (int k = 0; k < reps; k++) for (int i = 0; i < n; i++) if (EdgeTest.blockedTo(a[i], b[i], gs)) eh++;
                long t2 = System.nanoTime();
                if (r >= 0) { stockNs[r] = (t1 - t0) / (double) ((long) reps * n); edgeNs[r] = (t2 - t1) / (double) ((long) reps * n); }
                stockHits = hits; edgeHits = eh;
            }
            if (stockHits != edgeHits) throw new IllegalStateException("edge " + sc + ": stock " + stockHits + ", edgeTestFast " + edgeHits);
            Arrays.sort(stockNs); Arrays.sort(edgeNs);
            System.out.printf("edge %-6s stock   : %6.2f ns/call (min %.2f, max %.2f), blocked %d of %d%n", sc, stockNs[3], stockNs[0], stockNs[6], stockHits, (long) reps * n);
            System.out.printf("edge %-6s edgeTest: %6.2f ns/call (min %.2f, max %.2f), blocked %d of %d%n", sc, edgeNs[3], edgeNs[0], edgeNs[6], edgeHits, (long) reps * n);
        }
    }

    static void plan() {
        String[] names = {"40 redraw", "120 mixed", "400 light"};
        int[] sizes = {40, 120, 400};
        for (int sc = 0; sc < 3; sc++) {
            int n = sizes[sc];
            java.util.Random r = new java.util.Random(7 + sc);
            int[] cls = new int[n], dist = new int[n], held = new int[n];
            long[] since = new long[n], last = new long[n];
            for (int i = 0; i < n; i++) {
                int c;
                if (sc == 0) c = BakeHold.REDRAW;
                else if (sc == 1) { int u = r.nextInt(10); c = u == 0 ? BakeHold.CUTAWAY : u < 4 ? BakeHold.REDRAW : u < 9 ? BakeHold.LIGHT : BakeHold.BLOOD; }
                else { int u = r.nextInt(100); c = u < 90 ? BakeHold.LIGHT : u < 95 ? BakeHold.STRONG : u < 98 ? BakeHold.REDRAW : BakeHold.BLOOD; }
                cls[i] = c;
                dist[i] = r.nextInt(12);
                held[i] = c <= BakeHold.REDRAW ? (r.nextInt(20) == 0 ? 3 : r.nextInt(3)) : r.nextInt(12);   // a few overdue redraws, no overdue lights
                since[i] = 1000 - r.nextInt(600);
                last[i] = 1000 - r.nextInt(600);
            }
            BakeHold.Budget b = new BakeHold.Budget();
            b.total = 8; b.rebake = 4; b.strong = 8; b.light = 8;
            b.rebakeMaxFrames = 3; b.lightMaxFrames = 30; b.lightMs = 250; b.bloodMs = 250;
            boolean[] g = new boolean[n];
            int reps = 20_000;
            double[] ns = new double[7];
            long sum = 0;
            for (int rr = -2; rr < 7; rr++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < reps; k++) sum += BakeHold.plan(n, cls, dist, held, since, last, 1000, b, g);
                long t1 = System.nanoTime();
                if (rr >= 0) ns[rr] = (t1 - t0) / (double) reps;
            }
            long chk = 17;
            for (int i = 0; i < n; i++) chk = chk * 31 + (g[i] ? 1 : 0);
            chk = chk * 31 + b.leftTotal; chk = chk * 31 + b.leftRebake; chk = chk * 31 + b.leftStrong; chk = chk * 31 + b.leftLight;
            Arrays.sort(ns);
            System.out.printf("plan %-9s: %9.1f ns/plan (min %.1f, max %.1f), checksum %d, counted %d%n", names[sc], ns[3], ns[0], ns[6], chk, sum / (reps * 9L));
        }
    }

    static IsoChunk benchChunk(int wx, int wy) {
        IsoChunk c = new IsoChunk((zombie.iso.IsoCell) null);
        c.minLevel = 0; c.maxLevel = 1; c.wx = wx; c.wy = wy;
        c.squares = new IsoGridSquare[2][64];
        for (int z = 0; z < 2; z++)
            for (int i = 0; i < 64; i++) {
                IsoGridSquare sq = new IsoGridSquare(null, null, wx * 8 + i % 8, wy * 8 + i / 8, z);
                sq.setLightInfoServerGUIOnly(new zombie.core.textures.ColorInfo(0.5f, 0.5f, 0.5f, 1f));
                c.squares[z][i] = sq;
            }
        zombie.iso.fboRenderChunk.FBORenderLevels lv = c.getRenderLevels(0);
        lv.setOnScreen(0, true); lv.setOnScreen(1, true);
        lv.getOrCreateFBOForLevel(0, 1f).isInit = true;
        lv.clearDirty(0, 1f);
        return c;
    }

    static void gate() throws Exception {
        java.io.File f = java.io.File.createTempFile("pzbutter-gate", ".properties");
        f.deleteOnExit();
        java.nio.file.Files.writeString(f.toPath(), "enabled=true\nbakeScheduler=true\n");
        Settings.load(f);
        LightChecks.init();
        BakeHold.init();
        if (!ChunkPatches.holdGuard.on) throw new IllegalStateException("bakeScheduler did not switch on (game members)");
        zombie.iso.fboRenderChunk.FBORenderChunkManager mgr = new zombie.iso.fboRenderChunk.FBORenderChunkManager();
        zombie.iso.fboRenderChunk.FBORenderChunkManager.instance = mgr;
        zombie.iso.IsoCamera.frameState.playerIndex = 0;
        zombie.iso.IsoCamera.frameState.camCharacterX = 40f; zombie.iso.IsoCamera.frameState.camCharacterY = 40f;
        FrameClock.on = true;
        String[] names = {"60 clean", "60 light", "200 widest"};
        int[] counts = {30, 30, 100};
        for (int sc = 0; sc < 3; sc++) {
            int n = counts[sc];
            java.util.Random r = new java.util.Random(11 + sc);
            java.util.ArrayList<IsoChunk> chunks = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) chunks.add(benchChunk(i % 10, i / 10));
            // dirt: clean / all light / 10 % redraw, 60 % light, 30 % clean; re-applied every frame because a grant leaves dirt in place here
            long[] dirt = new long[n];
            for (int i = 0; i < n; i++) dirt[i] = sc == 0 ? 0 : sc == 1 ? 32 : (r.nextInt(10) == 0 ? 1024 : r.nextInt(10) < 6 ? 32 : 0);
            int frames = 1500;
            double[] ns = new double[7];
            long heldCount = 0, chk = 17;
            for (int rr = -2; rr < 7; rr++) {
                long held = 0, h = 17;
                long t0 = System.nanoTime();
                for (int fr = 0; fr < frames; fr++) {
                    long frame = (long) (rr + 2) * frames + fr + 1;
                    long now = frame * 16;
                    for (int i = 0; i < n; i++) {
                        IsoChunk c = chunks.get(i);
                        if (dirt[i] != 0) {
                            c.getRenderLevels(0).invalidateLevel(0, dirt[i]);
                            if ((dirt[i] & 32) != 0) c.squares[(int) (frame & 1)][(int) ((frame + i) & 63)].getLightInfo(0).r = 0.5f + ((frame * 7 + i) % 40) / 255f;
                        }
                    }
                    BakeHold.planChunks(chunks, 0, 1f, frame, now);
                    for (int i = 0; i < n; i++) {
                        IsoChunk c = chunks.get(i);
                        for (int lvl = 0; lvl <= 1; lvl++) {
                            boolean hd = BakeHold.gate(mgr, c, lvl, 1f, 0, frame, now);
                            if (hd) held++;
                            h = h * 31 + (hd ? 1 : 0);
                        }
                    }
                }
                long t1 = System.nanoTime();
                if (rr >= 0) ns[rr] = (t1 - t0) / (double) frames;
                heldCount = held; chk = h;
            }
            Arrays.sort(ns);
            System.out.printf("gate %-10s: %9.1f ns/frame (min %.1f, max %.1f), held answers per run %d, checksum %d%n", names[sc], ns[3], ns[0], ns[6], heldCount, chk);
        }
        FrameClock.on = false;
    }

    // ---- luagate ----

    static volatile Thread benchGame;
    static final java.lang.invoke.VarHandle BENCH_SLOT;
    static {
        try { BENCH_SLOT = java.lang.invoke.MethodHandles.lookup().findStaticVarHandle(DeliveryBench.class, "benchGame", Thread.class); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    static final class BenchLua {
        final se.krka.kahlua.j2se.KahluaTableImpl env = new se.krka.kahlua.j2se.KahluaTableImpl(new java.util.HashMap<>());
        final se.krka.kahlua.vm.KahluaThread kt = new se.krka.kahlua.vm.KahluaThread(se.krka.kahlua.j2se.J2SEPlatform.getInstance(), env);
        final se.krka.kahlua.integration.LuaCaller caller = new se.krka.kahlua.integration.LuaCaller(new se.krka.kahlua.converter.KahluaConverterManager());
        final Object inc;

        BenchLua() throws Exception {
            kt.debugOwnerThread = Thread.currentThread();
            caller.pcall(kt, se.krka.kahlua.luaj.compiler.LuaCompiler.loadstring("n = 0; function inc() n = n + 1 end", "b", env), new Object[0]);
            inc = env.rawget("inc");
        }

        void call() { caller.pcallvoid(kt, inc, (Object) null); }
        double n() { return (Double) env.rawget("n"); }
        void reset() { env.rawset("n", 0.0); }
    }

    static void benchSetup(BenchLua l) {
        LuaGate.resetForTest();
        LuaGate.guard.on = true;
        LuaGate.gameThreadSlot = BENCH_SLOT;
        benchGame = Thread.currentThread();
        LuaGate.luaThreads = () -> new se.krka.kahlua.vm.KahluaThread[] {l.kt};
        LuaGate.claim();
    }

    static void benchCheck(BenchLua l, double calls, Thread main) {
        if (l.n() != calls || l.kt.debugOwnerThread != main || benchGame != main)
            throw new IllegalStateException("luagate check failed: counter " + l.n() + " of " + calls + ", owner restored " + (l.kt.debugOwnerThread == main) + ", game thread restored " + (benchGame == main));
    }

    static double median(double[] v) { Arrays.sort(v); return v[v.length / 2]; }

    static void luagate() throws Exception {
        BenchLua l = new BenchLua();
        Thread main = Thread.currentThread();
        final int pairs = 100_000_000;
        // (1) key on, game thread, no worker; (2) key off
        for (int sc = 1; sc <= 2; sc++) {
            benchSetup(l);
            LuaGate.guard.on = sc == 1;
            double[] ns = new double[5];
            long taken = 0;
            for (int r = -1; r < 5; r++) {
                long t0 = System.nanoTime();
                taken += gatePairs(l.kt, pairs);
                if (r >= 0) ns[r] = (System.nanoTime() - t0) / (double) pairs;
            }
            if (taken != 0 || LuaGate.strays != 0) throw new IllegalStateException("luagate: the game thread was gated or counted");
            System.out.printf("luagate %-22s: %6.2f ns per enter/exit pair (min %.2f, max %.2f)%n", sc == 1 ? "1 key on, no worker" : "2 key off", median(ns), ns[0], ns[4]);
            LuaGate.resetForTest();
        }
        // (3) 8 workers x 20,000 gated calls, the holder in awaitYielding
        {
            double[] ms = new double[5];
            for (int r = -1; r < 5; r++) {
                benchSetup(l); l.reset();
                long t0 = System.nanoTime();
                runBenchWorkers(l, 8, 20_000);
                if (r >= 0) ms[r] = (System.nanoTime() - t0) / 1e6;
                benchCheck(l, 160_000, main);
                if (LuaGate.gatedCalls != 160_000) throw new IllegalStateException("luagate: gatedCalls " + LuaGate.gatedCalls);
                LuaGate.resetForTest();
            }
            System.out.printf("luagate 3 8 workers x 20000    : %8.1f ms (min %.1f, max %.1f)%n", median(ms), ms[0], ms[4]);
        }
        // (4) yieldPoint with no waiter
        {
            benchSetup(l);
            double[] ns = new double[5];
            long sink = 0;
            for (int r = -1; r < 5; r++) {
                long t0 = System.nanoTime();
                sink += yieldPoints(pairs);
                if (r >= 0) ns[r] = (System.nanoTime() - t0) / (double) pairs;
            }
            if (sink != 0) throw new IllegalStateException("luagate: yieldPoint served a waiter that does not exist");
            System.out.printf("luagate 4 yieldPoint, no waiter: %6.2f ns per call (min %.2f, max %.2f)%n", median(ns), ns[0], ns[4]);
            LuaGate.resetForTest();
        }
        // (5) 2 workers, one gated call per item (5,000 each), the holder serves them between 500 calls of its own work
        {
            double[] ms = new double[5];
            for (int r = -1; r < 5; r++) {
                benchSetup(l); l.reset();
                java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
                Thread[] w = new Thread[2];
                for (int i = 0; i < 2; i++) {
                    w[i] = LuaGate.worker(() -> {
                        for (int k = 0; k < 5_000; k++) { boolean g = LuaGate.enter(l.kt); try { l.call(); } finally { if (g) LuaGate.exit(); } }
                        finished.incrementAndGet();
                    }, "bench-w" + i);
                }
                long own = 0;
                long t0 = System.nanoTime();
                for (Thread t : w) t.start();
                while (finished.get() < 2) {
                    for (int k = 0; k < 500; k++) { l.call(); own++; }       // the holder's own work (not gated)
                    LuaGate.yieldPoint();
                }
                for (Thread t : w) t.join();
                if (r >= 0) ms[r] = (System.nanoTime() - t0) / 1e6;
                benchCheck(l, 10_000 + own, main);
                LuaGate.resetForTest();
            }
            System.out.printf("luagate 5 2 workers, yield/500  : %8.1f ms (min %.1f, max %.1f)%n", median(ms), ms[0], ms[4]);
        }
    }

    static long gatePairs(se.krka.kahlua.vm.KahluaThread kt, int n) {
        long taken = 0;
        for (int i = 0; i < n; i++) {
            boolean g = LuaGate.enter(kt);
            if (g) { taken++; LuaGate.exit(); }
        }
        return taken;
    }

    static long yieldPoints(int n) {
        long sink = 0;
        for (int i = 0; i < n; i++) sink += LuaGate.yieldPoint();
        return sink;
    }

    static void runBenchWorkers(BenchLua l, int n, int calls) throws Exception {
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] w = new Thread[n];
        for (int i = 0; i < n; i++) {
            w[i] = LuaGate.worker(() -> {
                for (int k = 0; k < calls; k++) { boolean g = LuaGate.enter(l.kt); try { l.call(); } finally { if (g) LuaGate.exit(); } }
                finished.incrementAndGet();
                LuaGate.wakeOwner();
            }, "bench-w" + i);
            w[i].start();
        }
        LuaGate.awaitYielding(() -> finished.get() == n);
        for (Thread t : w) t.join();
    }

    // ---- replay ----

    static void replay() throws Exception {
        replayScenario("1 2000 events, 8 threads", 8, 250);
        replayScenario("2 50 events, 5 threads", 5, 10);
    }

    static void replayScenario(String label, int threads, int per) throws Exception {
        final int total = threads * per;
        Object[][][] calls = new Object[threads][per][];
        for (int k = 0; k < threads; k++)
            for (int i = 0; i < per; i++) calls[k][i] = new Object[] {"OnZombieUpdate", i * threads + k};
        long expect = 0;
        for (int i = 0; i < total; i++) expect = expect * 31 + i;
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(threads + 1), end = new java.util.concurrent.CyclicBarrier(threads + 1);
        long[] threadNs = new long[threads];
        Throwable[] err = new Throwable[1];
        Thread[] w = new Thread[threads];
        final int runs = 3 + 7;                      // 3 warm-up, 7 measured
        for (int k = 0; k < threads; k++) {
            int kk = k;
            w[k] = new Thread(() -> {
                try {
                    for (int r = 0; r < runs; r++) {
                        start.await(20, java.util.concurrent.TimeUnit.SECONDS);
                        Object[][] mine = calls[kk];
                        long t0 = System.nanoTime();
                        for (int i = 0; i < per; i++) LuaReplay.capture(i * threads + kk, mine[i]);
                        threadNs[kk] = System.nanoTime() - t0;
                        end.await(20, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Throwable t) { err[0] = t; }
            }, "bench-rp" + k);
            w[k].setDaemon(true);
            w[k].start();
        }
        double[] cap = new double[7], rep = new double[7];
        long[] sum = new long[1];
        int[] fired = new int[1];
        for (int r = 0; r < runs; r++) {
            LuaReplay.clear();
            start.await(20, java.util.concurrent.TimeUnit.SECONDS);
            end.await(20, java.util.concurrent.TimeUnit.SECONDS);
            long cns = 0;
            for (long v : threadNs) cns += v;
            sum[0] = 0; fired[0] = 0;
            long t0 = System.nanoTime();
            int n = LuaReplay.replay(c -> { sum[0] = sum[0] * 31 + (Integer) c[1]; fired[0]++; });
            long rns = System.nanoTime() - t0;
            if (n != total || fired[0] != total || sum[0] != expect || LuaReplay.pending() != 0)
                throw new IllegalStateException("replay check failed: " + n + " of " + total + ", checksum " + sum[0] + " expected " + expect);
            if (r >= 3) { cap[r - 3] = cns / (double) total; rep[r - 3] = rns / (double) total; }
        }
        for (Thread t : w) t.join(20_000);
        if (err[0] != null) throw new IllegalStateException("replay worker failed", err[0]);
        System.out.printf("replay %-24s: %7.1f ns per capture (min %.1f, max %.1f), %7.1f ns per replayed call (min %.1f, max %.1f), checksum %d%n",
            label, median(cap), cap[0], cap[6], median(rep), rep[0], rep[6], sum[0]);
    }
}
