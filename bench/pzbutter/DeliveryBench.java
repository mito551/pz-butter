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
 * luafiles: the held Lua file writes (LuaFiles), median of 7 runs after a warm-up. ns per onOpen + write + close of a held
 *          heartbeat append, of a held rewrite, and ns per onOpen with the key off (pass-through); ns per tick() with 10 held
 *          keys none of which is due. Then a scripted 1,000-open sequence (three files, a tick every open, a fake clock) is
 *          run against a temp directory; the final contents must equal what stock's writes would leave (checked) and the
 *          printed checksum of them and the disk write count must not change between versions.
 * dedupe: the repeated-line table (LogDedupe.drop / roll), median of 7 runs after a warm-up, key on, a fixed clock. ns per drop()
 *          for (a) 10,000 unique lines (4,096 recorded, the rest over the cap), (b) one line 10,000 times, (c) 50 lines cycling
 *          (10,000 calls), (d) 8 threads x 10,000 calls of one line (the sum of the threads' own loop times per call). Prints the
 *          printed count (calls for which drop() was false) and the summary lines of one roll after each scenario, both fixed
 *          (checked: printed 10,000 / 1 / 50 / 1, and the summaries must equal the exact repeat counts).
 * scenecull: the cull loop's 3D cap and model budget (ZombieLod, Plan 8), median of 7 runs after a warm-up, 500 culls of 2,000
 *          setSceneCulled calls a run (every 16th is stock's own culled call). (1) keys off: ns per ZombiePatches.onCulledEnter +
 *          onCulledExit pair with a null self (50 M pairs a run); prints a checksum of the answers (the argument comes back
 *          unchanged). (2) cap 300, 600 zombies already in the model
 *          manager (the first 600 calls): ns per decide(); (3) budget 500 us with fake add costs of 40 us, 50 first-time adds per
 *          cull (every 40th call): ns per decide() plus granted(). Prints a checksum of every decided boolean (the rewrite
 *          pattern), which must not change between versions.
 * simlod: the extra update tier (ZombieLod.lower, Plan 8), median of 7 runs after a warm-up. ns per lower() over 2,000 objects with
 *          random distances 0-120 tiles (tiles 20), steps 1 and 3; and "all near" (every distance under tiles, no lowering), steps 3.
 *          Prints a checksum of the returned ordinals, which must not change between versions.
 * spotted: one simulated IsoPlayer.updateLOS frame (SpottedStack, Plan 8): 2,000 objects of which 1,500 are visible; per visible
 *          object lastSpotted.contains (first-sight check) and spottedList.add, then per spotted object contains + add if absent.
 *          lastSpotted is pre-filled (untimed) with 0 / 500 / 2,000 of the objects. ms per frame (median of 7 runs of 10 frames after a
 *          warm-up) on a java.util.Stack and on a SpottedStack; prints the final size and a checksum of the contains answers, which
 *          are equal for both (checked) and must not change between versions.
 * cullsort: zombie.entity.util.TimSort over 600 and 2,000 plain objects, the game's compare shape (two score calls per compare, stock
 *          arithmetic) against CullSort.prepare; the score costs about a relevance score (4 iterations of two square roots and a
 *          branch). Microseconds per sort (median of 7 runs of 20 sorts after a warm-up; the array copy is not timed); prints a
 *          checksum of the sorted order, equal for both (checked) and fixed between versions, and the score calls per sort.
 * statsget: Stats.get (stock) against the statsNoBox shim (ZombiePatches.onStatsGet + unpack) over every CharacterStat of one Stats with
 *          every second stat set (hits and default reads), 5 M reads a run, median of 7 runs after a warm-up; then the bytes allocated per
 *          read over 10 M reads of each. Prints a checksum of the floats read, equal for both (checked) and fixed between versions.
 * vbosize: the size-class upload (UploadSizer.upload through GpuPatches.onBufferData, Plan 9), median of 7 runs after a warm-up, a no-op
 *          Uploader that folds every call (kind, size) into a checksum. (1) key off: ns per onBufferData(null, null) (50 M calls a run);
 *          (2) orphan, two buffer objects alternating, 4 KB batches (every call a same-size respec + sub): ns per call (5 M calls a run);
 *          (3) subdata, batch sizes from a fixed-seed list between 1 KB and 1 MB over four buffer objects: ns per call (5 M calls a run).
 *          The checksum of the Uploader calls of (2) and (3) is fixed and must not change between versions.
 * vboslots: persistent sprite buffers over a no-op GL (PersistentVbo.map / SlotRing.decide through GpuPatches.onMapEnter / onMapExit /
 *          onUnmapEnter / onUnmapExit / onRingRendered / onSwapDone, Plan 9), median of 7 runs after a warm-up. (1) key off: ns per
 *          onMapEnter(null) + onUnmapEnter(null) (50 M pairs a run). (2) frame fences, 128 ring buffers (vertex + index) x 4 slots, 300 batches
 *          a frame (each batch maps and unmaps its vertex and its index buffer), the fake GL signalling every fence at once, 3,000 frames a run:
 *          ns per map + unmap of one buffer. (3) per-batch fences (onRingRendered after each batch; a frame fence is made per swap too), same load.
 *          Prints a checksum of the sequence of GL names bound and the wait counts: fixed between versions, equal for every run of a case.
 * glrecord: bakeNoSync's current-program record (GpuPatches.onUseProgram / onGetInteger / onGetIntegerExit, Plan 9), median of 7 runs after a
 *          warm-up. (1) key off: ns per onUseProgram + onGetInteger pair (50 M pairs a run). (2) on, 20,000 frames a run of 1,000 onUseProgram
 *          (16 programs in turn), every 5th followed by onGetInteger(GL_CURRENT_PROGRAM) + exit, every 5th (offset 2) by onGetInteger of another
 *          pname + exit (200 + 200 queries a frame): ns per shim call (1,000 onUseProgram + 400 enters + 400 exits = 1,800 calls a frame).
 *          Prints a checksum of the answers, fixed between versions and equal for every run.
 * scratch: ns per read of a listed scratch field (ArrayList.size() on it) in a class rewritten by WorkerTransform: plain getstatic baseline (untransformed),
 *          Scratch.get on a non-worker thread, and on a worker with a warm copy; median of 7 runs of 50 M reads after 3 warm-ups. Prints a checksum.
 * batch: FrameBatch.run: ns per run for n = 0, 16, 256, 2000 trivial tasks (an array write) at widths 2, 4, 8, and ms per batch of 2000 tasks of ~5 us
 *          at widths 1, 4, 8; median of 7 after warm-up. Prints a checksum.
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
            case "luafiles" -> luafiles();
            case "dedupe" -> dedupe();
            case "scenecull" -> scenecull();
            case "simlod" -> simlod();
            case "spotted" -> spotted();
            case "cullsort" -> { try { cullsort(); } catch (Throwable t) { throw new RuntimeException(t); } }
            case "statsget" -> statsget();
            case "vbosize" -> vbosize();
            case "vboslots" -> vboslots();
            case "glrecord" -> glrecord();
            case "scratch" -> scratch();
            case "batch" -> batch();
            case "calm" -> calm();
            case "latch" -> latch();
            case "pacing" -> pacing();
            default -> throw new IllegalArgumentException("... | pacing");
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

    // ---- luafiles ----

    static final long MSN = 1_000_000L;

    static void luafiles() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("pzbutter-luafiles").toFile();
        try {
            LuaFiles.resetForTest();
            LuaFiles.cacheDir = dir::getPath;
            final int opens = 100_000, runs = 3 + 7;
            for (int sc = 0; sc < 3; sc++) {
                String name = sc == 0 ? "bench_heartbeat.txt" : "bench_status.json";
                boolean append = sc == 0;
                double[] ns = new double[7];
                for (int r = 0; r < runs; r++) {
                    LuaFiles.resetForTest();
                    LuaFiles.cacheDir = dir::getPath;
                    LuaFiles.guard.on = sc != 2;
                    LuaFiles.onStockOpened(name, append, name, 0);
                    long t0 = System.nanoTime();
                    long held = 0;
                    for (int i = 0; i < opens; i++) {
                        Object w = LuaFiles.onOpen(name, true, append, 1000L + i * 8L);     // 0.8 ms in all: inside both windows
                        if (w != null) {
                            var f = (zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w;
                            f.write("x"); f.close();
                            held++;
                        }
                    }
                    long dt = System.nanoTime() - t0;
                    if (sc != 2 && held != opens || sc == 2 && held != 0) throw new IllegalStateException("luafiles check failed: held " + held + " scenario " + sc);
                    LuaFiles.flushAll();
                    if (r >= 3) ns[r - 3] = dt / (double) opens;
                }
                System.out.printf("luafiles %-30s: %7.1f ns (min %.1f, max %.1f)%n",
                    sc == 0 ? "1 held heartbeat append" : sc == 1 ? "2 held rewrite" : "3 key off, pass-through onOpen", median(ns), ns[0], ns[6]);
            }
            double[] ns = new double[7];
            for (int r = 0; r < runs; r++) {
                LuaFiles.resetForTest();
                LuaFiles.cacheDir = dir::getPath;
                LuaFiles.guard.on = true;
                for (int k = 0; k < 10; k++) {
                    String n = "k" + k + "_ping.txt";
                    LuaFiles.onStockOpened(n, true, n, 0);
                    ((zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) LuaFiles.onOpen(n, true, true, 1000L)).write("x");
                }
                int ticks = 1_000_000;
                long t0 = System.nanoTime();
                for (int i = 0; i < ticks; i++) LuaFiles.tick(2000L + i);                // all due at 2 s: not yet
                long dt = System.nanoTime() - t0;
                if (LuaFiles.diskWrites != 0) throw new IllegalStateException("luafiles: tick wrote early");
                LuaFiles.flushAll();
                if (r >= 3) ns[r - 3] = dt / (double) ticks;
            }
            System.out.printf("luafiles %-30s: %7.1f ns (min %.1f, max %.1f)%n", "4 tick, 10 held, none due", median(ns), ns[0], ns[6]);
            scripted(dir);
        } finally {
            LuaFiles.resetForTest();
            for (java.io.File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    /** 1,000 opens over three files with a fake clock, tick on every open; the files must end as stock's writes leave them. */
    static void scripted(java.io.File dir) throws Exception {
        LuaFiles.resetForTest();
        LuaFiles.cacheDir = dir::getPath;
        LuaFiles.guard.on = true;
        String[] names = {"status.json", "x_heartbeat.txt", "data.txt"};
        boolean[] app = {false, true, false};
        long[] step = {100 * MSN, 300 * MSN, 700 * MSN};
        StringBuilder[] want = {new StringBuilder(), new StringBuilder(), new StringBuilder()};
        long now = 0;
        long[] next = new long[3];
        int done = 0, held = 0;
        while (done < 1000) {
            now += 37 * MSN;
            for (int k = 0; k < 3 && done < 1000; k++) {
                if (now < next[k]) continue;
                next[k] = now + step[k];
                String text = names[k] + "#" + done + "\n";
                if (app[k]) want[k].append(text); else want[k].setLength(0);
                if (!app[k]) want[k].append(text);
                Object w = LuaFiles.onOpen(names[k], true, app[k], now);
                if (w != null) {
                    var f = (zombie.Lua.LuaManager.GlobalObject.LuaFileWriter) w;
                    f.write(text); f.close();
                    held++;
                } else {
                    java.io.File file = LuaFiles.resolve(dir.getPath(), names[k]);
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(file, app[k])) { o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                    LuaFiles.onStockOpened(names[k], app[k], text, now);
                }
                done++;
            }
            LuaFiles.tick(now);
        }
        LuaFiles.flushAll();
        long sum = 0;
        for (int k = 0; k < 3; k++) {
            String got = new String(java.nio.file.Files.readAllBytes(LuaFiles.resolve(dir.getPath(), names[k]).toPath()), java.nio.charset.StandardCharsets.UTF_8);
            if (!got.equals(want[k].toString())) throw new IllegalStateException("luafiles scripted: " + names[k] + " ends as " + got.length() + " chars, stock " + want[k].length());
            sum = sum * 31 + got.hashCode();
        }
        System.out.printf("luafiles scripted %d opens: %d held, %d disk writes, checksum %d%n", done, held, LuaFiles.diskWrites, sum);
    }

    // ---- dedupe ----

    static void dedupe() throws Exception {
        final int calls = 10_000, runs = 3 + 7;
        String[] uniq = new String[calls];
        for (int i = 0; i < calls; i++) uniq[i] = "unique line number " + i;
        String[] fifty = new String[50];
        for (int i = 0; i < 50; i++) fifty[i] = "cycling line " + i;
        Object stream = new Object();
        Object[] none = new Object[0];
        String[] label = {"a 10,000 unique lines", "b one line 10,000 times", "c 50 lines cycling", "d 8 threads x 10,000, one line"};
        for (int sc = 0; sc < 4; sc++) {
            double[] ns = new double[7];
            int printed = 0;
            String summaries = "";
            for (int r = 0; r < runs; r++) {
                LogDedupe.resetForTest();
                LogDedupe.guard.on = true;
                long dt;
                printed = 0;
                if (sc < 3) {
                    long t0 = System.nanoTime();
                    for (int i = 0; i < calls; i++) {
                        String m = sc == 0 ? uniq[i] : sc == 1 ? "one repeated line" : fifty[i % 50];
                        if (!LogDedupe.drop(stream, 1, m, none, 5L)) printed++;
                    }
                    dt = System.nanoTime() - t0;
                } else {
                    final int threads = 8;
                    java.util.concurrent.CyclicBarrier go = new java.util.concurrent.CyclicBarrier(threads);
                    long[] own = new long[threads];
                    int[] pr = new int[threads];
                    Thread[] t = new Thread[threads];
                    Throwable[] err = new Throwable[1];
                    for (int k = 0; k < threads; k++) {
                        final int kk = k;
                        (t[k] = new Thread(() -> {
                            try {
                                go.await();
                                long t0 = System.nanoTime();
                                int p = 0;
                                for (int i = 0; i < calls; i++) if (!LogDedupe.drop(stream, 1, "one repeated line", none, 5L)) p++;
                                own[kk] = System.nanoTime() - t0;
                                pr[kk] = p;
                            } catch (Throwable e) { err[0] = e; }
                        }, "dedupe-bench-" + k)).start();
                    }
                    for (Thread x : t) x.join(60_000);
                    if (err[0] != null) throw new IllegalStateException("dedupe worker failed", err[0]);
                    dt = 0;
                    for (int k = 0; k < threads; k++) { dt += own[k]; printed += pr[k]; }
                    dt /= 1;       // summed own loop times; divided by the call count below
                }
                int total = sc == 3 ? 8 * calls : calls;
                StringBuilder sb = new StringBuilder();
                long counted = 0;
                for (Object[] sline : LogDedupe.roll(5L + LogDedupe.WINDOW_NS)) { sb.append(sline[1]).append('\n'); counted += Long.parseLong(((String) sline[1]).split(" ")[2]); }
                int expectPrinted = sc == 0 ? calls : sc == 1 ? 1 : sc == 2 ? 50 : 1;
                // scenario a: 4,096 recorded and never repeated, so no summaries; the others summarise every drop
                long expectCounted = sc == 0 ? 0 : total - expectPrinted;
                if (printed != expectPrinted || counted != expectCounted)
                    throw new IllegalStateException("dedupe check failed in " + label[sc] + ": printed " + printed + " of " + total + ", summarised " + counted + " of " + expectCounted);
                summaries = sb.toString();
                if (r >= 3) ns[r - 3] = dt / (double) total;
            }
            System.out.printf("dedupe %-32s: %7.1f ns/drop (min %.1f, max %.1f), printed %d%n", label[sc], median(ns), ns[0], ns[6], printed);
            if (summaries.isEmpty()) System.out.println("  summaries: none");
            else for (String l : summaries.split("\n")) System.out.println("  " + l);
        }
        LogDedupe.resetForTest();
    }

    // ---- Plan 8: the cull loop's decisions ----

    /** One run: {@code culls} culls of {@code calls} calls; returns the checksum of the decided booleans. */
    static long cullRun(int culls, int calls, boolean budget) {
        long sum = 0;
        for (int c = 0; c < culls; c++) {
            ZombieLod.beginCull(20);
            for (int i = 0; i < calls; i++) {
                boolean culled = (i & 15) == 15;
                boolean added = budget ? i % 40 != 0 : i < 600;
                boolean r = ZombieLod.decide(culled, added);
                if (budget && !culled && !r) ZombieLod.granted(added, 40_000L);
                sum = sum * 31 + (r ? 1 : 0);
            }
            ZombieLod.endCull();
        }
        return sum;
    }

    static void scenecull() {
        final int culls = 500, calls = 2_000;
        {
            final int pairs = 50_000_000;
            double[] ns = new double[7];
            long sum = 0;
            for (int r = 0; r < 3 + 7; r++) {
                ZombieLod.resetForTest();
                long t0 = System.nanoTime();
                long s = 0;
                for (int i = 0; i < pairs; i++) {
                    boolean arg = (i & 1) != 0;
                    s += ZombiePatches.onCulledEnter(null, arg) ? 1 : 0;
                    ZombiePatches.onCulledExit(null);
                }
                long dt = System.nanoTime() - t0;
                if (s != pairs / 2) throw new IllegalStateException("scenecull: the argument changed with the keys off");
                sum = s;
                if (r >= 3) ns[r - 3] = dt / (double) pairs;
            }
            System.out.printf("scenecull %-26s: %6.2f ns/pair (min %.2f, max %.2f), checksum %d%n", "1 keys off, null self", median(ns), ns[0], ns[6], sum);
        }
        String[] label = {"2 cap 300, 600 added", "3 budget 500 us, 50 adds"};
        for (int sc = 0; sc < 2; sc++) {
            double[] ns = new double[7];
            long sum = 0, first = 0;
            for (int r = 0; r < 3 + 7; r++) {
                ZombieLod.resetForTest();
                if (sc == 0) { ZombieLod.capGuard.on = true; ZombieLod.cap = 300; }
                else { ZombieLod.budgetGuard.on = true; ZombieLod.budgetNs = 500_000L; }
                long t0 = System.nanoTime();
                sum = cullRun(culls, calls, sc == 1);
                long dt = System.nanoTime() - t0;
                if (r == 0) first = sum;
                else if (sum != first) throw new IllegalStateException("scenecull: checksum changed between runs");
                if (r >= 3) ns[r - 3] = dt / (double) ((long) culls * calls);
            }
            System.out.printf("scenecull %-26s: %6.2f ns/call (min %.2f, max %.2f), checksum %d, capped %d, deferred %d%n",
                label[sc], median(ns), ns[0], ns[6], sum, ZombieLod.capped, ZombieLod.deferred);
        }
        ZombieLod.resetForTest();
    }

    static long simlodRun(float[] dist, int steps, int rounds) {
        zombie.UpdateSchedulerSimulationLevel[] lv = zombie.UpdateSchedulerSimulationLevel.values();
        long sum = 0;
        for (int r = 0; r < rounds; r++)
            for (int i = 0; i < dist.length; i++)
                sum = sum * 31 + ZombieLod.lower(lv[(i + r) % lv.length], dist[i], 20, steps).ordinal();
        return sum;
    }

    static void simlod() {
        final int n = 2_000, rounds = 5_000;
        java.util.Random rnd = new java.util.Random(7);
        float[] far = new float[n], near = new float[n];
        for (int i = 0; i < n; i++) { far[i] = rnd.nextFloat() * 120f; near[i] = rnd.nextFloat() * 19f; }
        String[] label = {"1 steps 1, 0-120 tiles", "2 steps 3, 0-120 tiles", "3 steps 3, all near"};
        float[][] d = {far, far, near};
        int[] steps = {1, 3, 3};
        for (int sc = 0; sc < 3; sc++) {
            double[] ns = new double[7];
            long sum = 0, first = 0;
            for (int r = 0; r < 3 + 7; r++) {
                long t0 = System.nanoTime();
                sum = simlodRun(d[sc], steps[sc], rounds);
                long dt = System.nanoTime() - t0;
                if (r == 0) first = sum;
                else if (sum != first) throw new IllegalStateException("simlod: checksum changed between runs");
                if (r >= 3) ns[r - 3] = dt / (double) ((long) n * rounds);
            }
            System.out.printf("simlod %-26s: %6.2f ns/call (min %.2f, max %.2f), checksum %d%n", label[sc], median(ns), ns[0], ns[6], sum);
        }
    }

    /** One updateLOS frame over lastSpotted; returns a checksum of the contains answers and the final size. */
    static long losFrame(java.util.Stack<Object> last, Object[] objs, java.util.Stack<Object> spotted) {
        long sum = 0;
        spotted.clear();
        for (int i = 0; i < objs.length; i++) {
            if ((i & 3) == 3) continue;
            Object o = objs[i];
            boolean first = !last.contains(o);
            sum = sum * 31 + (first ? 1 : 0);
            spotted.add(o);
        }
        for (int i = 0; i < spotted.size(); i++) {
            Object o = spotted.get(i);
            boolean has = last.contains(o);
            sum = sum * 31 + (has ? 1 : 0);
            if (!has) last.add(o);
        }
        return sum * 31 + last.size();
    }

    static void spotted() {
        final int n = 2_000, frames = 10;
        Object[] objs = new Object[n];
        for (int i = 0; i < n; i++) objs[i] = new Object();
        int[] fill = {0, 500, 2_000};
        for (int fi = 0; fi < 3; fi++) {
            long[] check = new long[2];
            for (int kind = 0; kind < 2; kind++) {
                double[] ms = new double[7];
                long sum = 0, first = 0;
                java.util.Stack<Object> spottedList = new java.util.Stack<>();
                for (int r = 0; r < 3 + 7; r++) {
                    long total = 0;
                    for (int f = 0; f < frames; f++) {
                        java.util.Stack<Object> last = kind == 0 ? new java.util.Stack<>() : new SpottedStack<>();
                        for (int i = 0; i < fill[fi]; i++) last.add(objs[(i * 7) % n]);
                        long t0 = System.nanoTime();
                        sum = losFrame(last, objs, spottedList);
                        total += System.nanoTime() - t0;
                    }
                    if (r == 0) first = sum;
                    else if (sum != first) throw new IllegalStateException("spotted: checksum changed between runs");
                    if (r >= 3) ms[r - 3] = total / 1e6 / frames;
                }
                check[kind] = sum;
                System.out.printf("spotted %-10s %-14s: %8.3f ms/frame (min %.3f, max %.3f), checksum %d%n",
                    "prefill " + fill[fi], kind == 0 ? "Stack" : "SpottedStack", median(ms), ms[0], ms[6], sum);
            }
            if (check[0] != check[1]) throw new IllegalStateException("spotted: the two stacks disagree");
        }
    }

    static final class SortItem {
        final float x, y;
        final int id;
        SortItem(float x, float y, int id) { this.x = x; this.y = y; this.id = id; }
    }

    static int scoreCalls;

    /** About the cost of IsoPlayer.getZombieRelevenceScore: a short loop of square roots and a branch. */
    static float benchScore(Object o) {
        SortItem z = (SortItem) o;
        scoreCalls++;
        float v = z.x * z.x + z.y * z.y;
        for (int i = 0; i < 4; i++) {
            v = (float) Math.sqrt(v + z.x) + (float) Math.sqrt(v + z.y);
            if (v > 90f) v -= 45f;
        }
        return v;
    }

    static long orderSum(Object[] a) {
        long sum = 0;
        for (Object o : a) sum = sum * 31 + ((SortItem) o).id;
        return sum;
    }

    static void cullsort() throws Throwable {
        final int sorts = 20;
        for (int n : new int[] {600, 2_000}) {
            java.util.Random rnd = new java.util.Random(n);
            Object[] base = new Object[n];
            for (int i = 0; i < n; i++) base[i] = new SortItem(rnd.nextFloat() * 60f, rnd.nextFloat() * 60f, i);
            java.util.Comparator<Object> stockCmp = (p, q) -> CullSort.compareScores(benchScore(p), benchScore(q));
            long[] check = new long[2];
            long[] calls = new long[2];
            for (int kind = 0; kind < 2; kind++) {
                double[] us = new double[7];
                long sum = 0, first = 0;
                for (int r = 0; r < 3 + 7; r++) {
                    long total = 0;
                    scoreCalls = 0;
                    for (int s = 0; s < sorts; s++) {
                        Object[] a = base.clone();
                        long t0 = System.nanoTime();
                        if (kind == 0) new zombie.entity.util.TimSort<Object>().doSort(a, stockCmp, 0, n);
                        else {
                            java.util.Comparator<Object> c = CullSort.prepare(a, 0, n, DeliveryBench::benchScore);
                            new zombie.entity.util.TimSort<Object>().doSort(a, c, 0, n);
                            CullSort.done(c);
                        }
                        total += System.nanoTime() - t0;
                        sum = orderSum(a);
                    }
                    if (r == 0) first = sum;
                    else if (sum != first) throw new IllegalStateException("cullsort: checksum changed between runs");
                    if (r >= 3) us[r - 3] = total / 1e3 / sorts;
                    calls[kind] = scoreCalls / sorts;
                }
                check[kind] = sum;
                System.out.printf("cullsort %-5d %-14s: %9.1f us/sort (min %.1f, max %.1f), checksum %d, %d score calls/sort%n",
                    n, kind == 0 ? "stock compare" : "prepare", median(us), us[0], us[6], sum, calls[kind]);
            }
            if (check[0] != check[1]) throw new IllegalStateException("cullsort: the two orders differ");
        }
    }

    static long statsReads(zombie.characters.Stats st, zombie.characters.CharacterStat[] all, int reads, boolean ours) {
        long sum = 0;
        int n = all.length, k = 0;
        for (int i = 0; i < reads; i++) {
            zombie.characters.CharacterStat c = all[k];
            if (++k == n) k = 0;
            float v;
            if (ours) {
                long r = ZombiePatches.onStatsGet(st, c);
                v = r >= 0 ? Float.intBitsToFloat((int) r) : st.get(c);
            } else v = st.get(c);
            sum = sum * 31 + Float.floatToRawIntBits(v);
        }
        return sum;
    }

    static void statsget() {
        final int reads = 5_000_000, allocReads = 10_000_000;
        zombie.characters.Stats st = new zombie.characters.Stats();
        zombie.characters.CharacterStat[] all = zombie.characters.CharacterStat.ORDERED_STATS;
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < all.length; i += 2) st.set(all[i], rnd.nextFloat());
        ZombieMisc.resetForTest();
        ZombieMisc.statsGuard.on = true;
        long[] check = new long[2];
        for (int kind = 0; kind < 2; kind++) {
            double[] ns = new double[7];
            long sum = 0, first = 0;
            for (int r = 0; r < 3 + 7; r++) {
                long t0 = System.nanoTime();
                sum = statsReads(st, all, reads, kind == 1);
                long dt = System.nanoTime() - t0;
                if (r == 0) first = sum;
                else if (sum != first) throw new IllegalStateException("statsget: checksum changed between runs");
                if (r >= 3) ns[r - 3] = dt / (double) reads;
            }
            check[kind] = sum;
            long b0 = MX.getCurrentThreadAllocatedBytes();
            statsReads(st, all, allocReads, kind == 1);
            double bytes = (MX.getCurrentThreadAllocatedBytes() - b0) / (double) allocReads;
            System.out.printf("statsget %-12s: %6.2f ns/read (min %.2f, max %.2f), %5.2f bytes/read, checksum %d%n",
                kind == 0 ? "Stats.get" : "statsNoBox", median(ns), ns[0], ns[6], bytes, sum);
        }
        ZombieMisc.resetForTest();
        if (check[0] != check[1]) throw new IllegalStateException("statsget: the two reads disagree");
    }

    /** The folded Uploader of the vbosize bench: a checksum of the calls in order, no GL. */
    static final class FoldUploader implements UploadSizer.Uploader {
        long sum;
        public void alloc(int type, long bytes, int usage) { sum = sum * 31 + 7 * bytes + type + usage; }
        public void sub(int type, java.nio.ByteBuffer d) { sum = sum * 31 + 11L * d.remaining() + type; }
    }

    static void vbosize() throws Exception {
        final int offCalls = 50_000_000, calls = 5_000_000;
        UploadSizer.resetForTest();
        double[] ns = new double[7];
        for (int r = 0; r < 3 + 7; r++) {
            long t0 = System.nanoTime();
            int hits = 0;
            for (int i = 0; i < offCalls; i++) if (GpuPatches.onBufferData(null, null)) hits++;
            long dt = System.nanoTime() - t0;
            if (hits != 0) throw new IllegalStateException("vbosize: key off took an upload");
            if (r >= 3) ns[r - 3] = dt / (double) offCalls;
        }
        System.out.printf("vbosize key off          : %6.2f ns/call (min %.2f, max %.2f)%n", median(ns), ns[0], ns[6]);

        zombie.core.VBO.IGLBufferObject was = zombie.core.VBO.GLVertexBufferObject.funcs;
        Thread wasThread = PresentLog.renderThread;
        zombie.core.VBO.GLVertexBufferObject.funcs = new zombie.core.VBO.GLBufferObject15();
        PresentLog.renderThread = Thread.currentThread();
        try {
            java.util.Random rnd = new java.util.Random(5);
            java.nio.ByteBuffer[] sizes = new java.nio.ByteBuffer[512];
            for (int i = 0; i < sizes.length; i++) sizes[i] = java.nio.ByteBuffer.allocateDirect(1024 + rnd.nextInt(1024 * 1024 - 1024 + 1));
            java.nio.ByteBuffer fourKb = java.nio.ByteBuffer.allocateDirect(4096);
            for (int scen = 0; scen < 2; scen++) {
                double[] t = new double[7];
                long first = 0;
                for (int r = 0; r < 3 + 7; r++) {
                    UploadSizer.resetForTest();
                    FoldUploader f = new FoldUploader();
                    UploadSizer.gl = f;
                    UploadSizer.mode = scen == 0 ? UploadSizer.ORPHAN : UploadSizer.SUBDATA;
                    UploadSizer.guard.on = true;
                    zombie.core.VBO.GLVertexBufferObject[] v = new zombie.core.VBO.GLVertexBufferObject[scen == 0 ? 2 : 4];
                    for (int i = 0; i < v.length; i++) v[i] = new zombie.core.VBO.GLVertexBufferObject(4096, 34962, 35040);
                    long t0 = System.nanoTime();
                    for (int i = 0; i < calls; i++) {
                        if (!GpuPatches.onBufferData(v[i % v.length], scen == 0 ? fourKb : sizes[(i * 7) & 511])) throw new IllegalStateException("vbosize: upload refused");
                    }
                    long dt = System.nanoTime() - t0;
                    if (!UploadSizer.guard.on) throw new IllegalStateException("vbosize: the lag guard tripped");
                    if (r == 0) first = f.sum;
                    else if (f.sum != first) throw new IllegalStateException("vbosize: checksum changed between runs");
                    if (r >= 3) t[r - 3] = dt / (double) calls;
                }
                System.out.printf("vbosize %-17s: %6.2f ns/call (min %.2f, max %.2f), checksum %d%n",
                    scen == 0 ? "orphan, 4 KB x2" : "subdata, 1K-1M x4", median(t), t[0], t[6], first);
            }
        } finally {
            zombie.core.VBO.GLVertexBufferObject.funcs = was;
            PresentLog.renderThread = wasThread;
            UploadSizer.resetForTest();
        }
    }

    /** bakeNoSync's program record through the shims, with the render thread set to this one. */
    static void glrecord() throws Exception {
        final int offCalls = 50_000_000, frames = 20_000, perFrame = 1_000;
        NoSync.resetForTest();
        double[] ns = new double[7];
        for (int r = 0; r < 3 + 7; r++) {
            long t0 = System.nanoTime();
            int hits = 0;
            for (int i = 0; i < offCalls; i++) {
                GpuPatches.onUseProgram(i & 15);
                if (GpuPatches.onGetInteger(0x8B8D)) hits++;
            }
            long dt = System.nanoTime() - t0;
            if (hits != 0) throw new IllegalStateException("glrecord: key off answered");
            if (r >= 3) ns[r - 3] = dt / (double) offCalls;
        }
        System.out.printf("glrecord key off              : %6.2f ns/pair (min %.2f, max %.2f)%n", median(ns), ns[0], ns[6]);

        Thread wasThread = PresentLog.renderThread;
        PresentLog.renderThread = Thread.currentThread();
        try {
            double[] t = new double[7];
            long firstSum = 0;
            for (int r = 0; r < 3 + 7; r++) {
                NoSync.resetForTest();
                NoSync.guard.on = true;
                long sum = 0;
                long t0 = System.nanoTime();
                for (int f = 0; f < frames; f++) {
                    for (int i = 0; i < perFrame; i++) {
                        GpuPatches.onUseProgram((i * 7 + f) & 15);
                        int m = i % 5;
                        if (m == 0) {
                            boolean skip = GpuPatches.onGetInteger(0x8B8D);
                            sum = sum * 31 + GpuPatches.onGetIntegerExit(0x8B8D, -5) + (skip ? 1 : 0);
                        } else if (m == 2) {
                            boolean skip = GpuPatches.onGetInteger(0x0BA2);
                            sum = sum * 31 + GpuPatches.onGetIntegerExit(0x0BA2, i) + (skip ? 1 : 0);
                        }
                    }
                }
                long dt = System.nanoTime() - t0;
                if (r == 0) firstSum = sum;
                else if (sum != firstSum) throw new IllegalStateException("glrecord: checksum changed between runs");
                if (NoSync.programAnswered != (long) frames * (perFrame / 5)) throw new IllegalStateException("glrecord: wrong number of answers");
                if (r >= 3) t[r - 3] = dt / ((double) frames * (perFrame + 4 * (perFrame / 5)));
            }
            System.out.printf("glrecord on                   : %6.2f ns/call (min %.2f, max %.2f), checksum %d%n", median(t), t[0], t[6], firstSum);
        } finally {
            PresentLog.renderThread = wasThread;
            NoSync.resetForTest();
        }
    }

    /** The no-op GL of the vboslots bench: a checksum of every bind, fences signalled at once. */
    static final class SlotGl implements PersistentVbo.Gl {
        long sum, fences;
        int names = 1000;
        final java.nio.ByteBuffer shared = java.nio.ByteBuffer.allocateDirect(65536).order(java.nio.ByteOrder.nativeOrder());
        public boolean capable() { return true; }
        public int genBuffer() { return ++names; }
        public void storage(int type, long size) { }
        public java.nio.ByteBuffer mapPersistent(int type, long size) { return shared.duplicate().order(java.nio.ByteOrder.nativeOrder()); }
        public void bind(int type, int name) { sum = sum * 31 + name + type; }
        public void unmapAndDelete(int type, int name) { }
        public void deleteBuffer(int name) { }
        public long fence() { return ++fences; }
        public boolean signalled(long f) { return true; }
        public boolean await(long f, long ns) { return true; }
        public void deleteFence(long f) { }
        public int error() { return 0; }
        public long context() { return 1; }
    }

    static void vboslots() throws Exception {
        final int offCalls = 50_000_000, frames = 3_000, perFrame = 300, ring = 128;
        PersistentVbo.resetForTest();
        double[] ns = new double[7];
        for (int r = 0; r < 3 + 7; r++) {
            long t0 = System.nanoTime();
            int hits = 0;
            for (int i = 0; i < offCalls; i++) {
                if (GpuPatches.onMapEnter(null)) hits++;
                if (GpuPatches.onUnmapEnter(null)) hits++;
            }
            long dt = System.nanoTime() - t0;
            if (hits != 0) throw new IllegalStateException("vboslots: key off took a map");
            if (r >= 3) ns[r - 3] = dt / (double) offCalls;
        }
        System.out.printf("vboslots key off              : %6.2f ns/pair (min %.2f, max %.2f)%n", median(ns), ns[0], ns[6]);

        Class<?> rbc = Class.forName("zombie.core.SpriteRenderer$RingBuffer");
        java.lang.reflect.Constructor<?> rbk = rbc.getDeclaredConstructor(); rbk.setAccessible(true);
        Thread wasThread = PresentLog.renderThread;
        PresentLog.renderThread = Thread.currentThread();
        try {
            for (int scen = 0; scen < 2; scen++) {
                double[] t = new double[7];
                long firstSum = 0, firstWaits = -1;
                for (int r = 0; r < 3 + 7; r++) {
                    PersistentVbo.resetForTest();
                    SlotGl g = new SlotGl();
                    PersistentVbo.gl = g;
                    PersistentVbo.guard.on = true;
                    PersistentVbo.frameSync = scen == 0;
                    PersistentVbo.wantSlots = 4;
                    PersistentVbo.ringBytes = 12_500_000L;
                    zombie.core.VBO.GLVertexBufferObject[] vbo = new zombie.core.VBO.GLVertexBufferObject[ring], ibo = new zombie.core.VBO.GLVertexBufferObject[ring];
                    Object[] rbs = new Object[ring];
                    for (int i = 0; i < ring; i++) {
                        vbo[i] = new zombie.core.VBO.GLVertexBufferObject(65536, 34962, 35040);
                        ibo[i] = new zombie.core.VBO.GLVertexBufferObject(5460, 34963, 35040);
                        rbs[i] = rbk.newInstance();
                        benchSet(rbs[i], "vbo", vbo); benchSet(rbs[i], "ibo", ibo); benchSet(rbs[i], "sequence", i);
                    }
                    long t0 = System.nanoTime();
                    int k = 0;
                    for (int f = 0; f < frames; f++) {
                        for (int i = 0; i < perFrame; i++, k++) {
                            int seq = k & (ring - 1);
                            slotBatch(vbo[seq]); slotBatch(ibo[seq]);
                            GpuPatches.onRingRendered(rbs[seq]);
                        }
                        GpuPatches.onSwapDone();
                    }
                    long dt = System.nanoTime() - t0;
                    if (!PersistentVbo.guard.on) throw new IllegalStateException("vboslots: the feature went off");
                    if (scen == 1 && g.fences < 2L * frames * perFrame) throw new IllegalStateException("vboslots: per-batch mode made no batch fences");
                    if (scen == 0 && g.fences > 2L * frames) throw new IllegalStateException("vboslots: frame mode made batch fences");
                    long sum = g.sum;
                    if (r == 0) { firstSum = sum; firstWaits = PersistentVbo.waits; }
                    else if (sum != firstSum || PersistentVbo.waits != firstWaits) throw new IllegalStateException("vboslots: checksum changed between runs");
                    if (r >= 3) t[r - 3] = dt / (2.0 * frames * perFrame);
                }
                System.out.printf("vboslots %-21s: %6.2f ns/map+unmap (min %.2f, max %.2f), checksum %d, waits %d, stalls %d%n",
                    scen == 0 ? "frame fences 128x4" : "batch fences 128x4", median(t), t[0], t[6], firstSum, firstWaits, PersistentVbo.stalls);
            }
        } finally {
            PresentLog.renderThread = wasThread;
            PersistentVbo.resetForTest();
        }
    }

    /** One buffer of a batch, as the ring does it: map, take the returned buffer, unmap. */
    static void slotBatch(zombie.core.VBO.GLVertexBufferObject v) {
        if (!GpuPatches.onMapEnter(v)) throw new IllegalStateException("vboslots: map refused");
        if (GpuPatches.onMapExit(null) == null) throw new IllegalStateException("vboslots: no buffer");
        if (!GpuPatches.onUnmapEnter(v) || !GpuPatches.onUnmapExit(false)) throw new IllegalStateException("vboslots: unmap refused");
    }

    /** t.A: static final ArrayList L (3 elements) and static long run(int n): the sum of n reads of L.size(). */
    static byte[] scratchClass() {
        var self = java.lang.constant.ClassDesc.of("t.A");
        var al = java.lang.constant.ClassDesc.of("java.util.ArrayList");
        var v = java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void);
        var sz = java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_int);
        var add = java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_boolean, java.lang.constant.ConstantDescs.CD_Object);
        int pub = java.lang.classfile.ClassFile.ACC_PUBLIC, st = java.lang.classfile.ClassFile.ACC_STATIC;
        return java.lang.classfile.ClassFile.of().build(self, cb -> {
            cb.withFlags(pub);
            cb.withField("L", al, pub | st | java.lang.classfile.ClassFile.ACC_FINAL);
            cb.withMethodBody("<clinit>", v, st, b -> {
                b.new_(al).dup().invokespecial(al, "<init>", v).putstatic(self, "L", al);
                for (int i = 0; i < 3; i++) b.getstatic(self, "L", al).aconst_null().invokevirtual(al, "add", add).pop();
                b.return_();
            });
            cb.withMethodBody("run", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_long, java.lang.constant.ConstantDescs.CD_int), pub | st, b -> {
                var top = b.newLabel(); var end = b.newLabel();
                b.lconst_0().lstore(1).iconst_0().istore(3);
                b.labelBinding(top).iload(3).iload(0).if_icmpge(end);
                b.lload(1).getstatic(self, "L", al).invokevirtual(al, "size", sz).i2l().ladd().lstore(1);
                b.iinc(3, 1).goto_(top).labelBinding(end).lload(1).lreturn();
            });
        });
    }

    static java.lang.invoke.MethodHandle scratchRun(byte[] b) throws Exception {
        ClassLoader l = new ClassLoader(DeliveryBench.class.getClassLoader()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (!n.equals("t.A")) throw new ClassNotFoundException(n);
                return defineClass(n, b, 0, b.length);
            }
        };
        Class<?> c = l.loadClass("t.A");
        Scratch.loader = l;
        return java.lang.invoke.MethodHandles.lookup().findStatic(c, "run", java.lang.invoke.MethodType.methodType(long.class, int.class));
    }

    static void scratch() throws Exception {
        WorkerList list = WorkerList.parse("revision r\nclass t.A\nscratch 0 t/A L Ljava/util/ArrayList;\n");
        Scratch.init(list);
        java.lang.invoke.MethodHandle plain = scratchRun(scratchClass());
        Scratch.init(list);
        java.lang.invoke.MethodHandle rewritten = scratchRun(WorkerTransform.transform(scratchClass(), list));
        int n = 50_000_000;
        String[] names = {"getstatic baseline", "Scratch.get, non-worker", "Scratch.get, worker, warm copy"};
        long chk = 0;
        for (int sc = 0; sc < 3; sc++) {
            java.lang.invoke.MethodHandle h = sc == 0 ? plain : rewritten;
            double[] ns = new double[7];
            long[] out = {0};
            Runnable body = () -> {
                try {
                    for (int rr = -3; rr < 7; rr++) {
                        long t0 = System.nanoTime();
                        out[0] += (long) h.invokeExact(n);
                        if (rr >= 0) ns[rr] = (System.nanoTime() - t0) / (double) n;
                    }
                } catch (Throwable t) { throw new RuntimeException(t); }
            };
            Thread t = sc == 2 ? LuaGate.worker(body, "bench-worker") : new Thread(body);
            t.start();
            t.join();
            Arrays.sort(ns);
            chk += out[0];
            System.out.printf("scratch %-32s %.2f ns/read%n", names[sc], ns[3]);
        }
        System.out.println("scratch checksum " + chk + " copies " + Scratch.copies());
    }

    static void batch() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        long chk = 0;
        int[] out = new int[2000];
        for (int w : new int[] {2, 4, 8}) {
            FrameBatch.start(w);
            for (int n : new int[] {0, 16, 256, 2000}) {
                double[] ns = new double[7];
                int reps = n < 256 ? 20_000 : 2_000;
                for (int rr = -3; rr < 7; rr++) {
                    long t0 = System.nanoTime();
                    for (int k = 0; k < reps; k++) FrameBatch.run(n, i -> out[i] = i + 1);
                    if (rr >= 0) ns[rr] = (System.nanoTime() - t0) / (double) reps;
                }
                Arrays.sort(ns);
                for (int i = 0; i < n; i++) chk += out[i];
                System.out.printf("batch trivial width %d n %4d %10.0f ns/run%n", w, n, ns[3]);
            }
            FrameBatch.stopForTest();
        }
        long[] sink = new long[2000];
        for (int w : new int[] {1, 4, 8}) {
            if (w > 1) FrameBatch.start(w);
            double[] ms = new double[7];
            for (int rr = -3; rr < 7; rr++) {
                long t0 = System.nanoTime();
                for (int k = 0; k < 20; k++) FrameBatch.run(2000, i -> { long a = i; for (int j = 0; j < 3000; j++) a = a * 31 + j; sink[i] = a; });
                if (rr >= 0) ms[rr] = (System.nanoTime() - t0) / 20e6;
            }
            Arrays.sort(ms);
            for (long v : sink) chk += v;
            System.out.printf("batch 2000 x ~5us width %d %8.3f ms/batch%n", w, ms[3]);
            if (w > 1) FrameBatch.stopForTest();
        }
        LuaGate.resetForTest();
        System.out.println("batch checksum " + chk);
    }

    static void calm() {
        final int n = 50_000_000;
        Object x = new Object();
        Calm.Facts nf = Calm.of(x);
        double[] a = new double[7], b = new double[7];
        for (int r = -3; r < 7; r++) {
            long t0 = System.nanoTime();
            boolean s = false;
            for (int i = 0; i < n; i++) s ^= Calm.test(Calm.of(x));
            long dt = System.nanoTime() - t0;
            if (r >= 0) a[r] = dt / (double) n;
            if (s) throw new IllegalStateException("calm: unexpected true");
        }
        for (int r = -3; r < 7; r++) {
            long t0 = System.nanoTime();
            boolean s = false;
            for (int i = 0; i < n; i++) s ^= Calm.test(nf);
            long dt = System.nanoTime() - t0;
            if (r >= 0) b[r] = dt / (double) n;
            if (s) throw new IllegalStateException("calm: unexpected true");
        }
        java.util.Arrays.sort(a); java.util.Arrays.sort(b);
        System.out.printf("calm of+test: %6.2f ns/call (min %.2f, max %.2f)%n", a[3], a[0], a[6]);
        System.out.printf("calm test only: %6.2f ns/call (min %.2f, max %.2f)%n", b[3], b[0], b[6]);
    }

    static void benchSet(Object o, String f, Object v) throws Exception { java.lang.reflect.Field x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v); }

    static void latch() throws Exception {
        LuaGate.resetForTest(); LuaGate.guard.on = true; LuaGate.claim();
        FrameBatch.start(5);
        long[] chk = {0};
        Latch.Apply op = (a, b, i, f) -> chk[0] += (Integer) a + i;

        final int W = 4, M = 20_000;
        Object[] boxed = new Object[W * M];
        for (int j = 0; j < boxed.length; j++) boxed[j] = j;

        final int warm = 20, timed = 7;
        double[] rec = new double[timed];
        double[] appl = new double[timed];
        int workerTasks = 0;
        for (int r = 0; r < warm + timed; r++) {
            Latch.begin(100 + r);
            long[] dts = new long[W];
            java.util.concurrent.atomic.AtomicInteger wt = new java.util.concurrent.atomic.AtomicInteger();
            FrameBatch.run(W, idx -> {
                if (Thread.currentThread() instanceof LuaGate.Worker) {
                    wt.incrementAndGet();
                    long t0 = System.nanoTime();
                    for (int k = 0; k < M; k++) Latch.record((k * W + idx) % (W * M), op, boxed[(k * W + idx) % (W * M)], null, k, 0.0f);
                    dts[idx] = System.nanoTime() - t0;
                } else {
                    for (int k = 0; k < M; k++) Latch.record((k * W + idx) % (W * M), op, boxed[(k * W + idx) % (W * M)], null, k, 0.0f);
                }
            });
            long totalDt = 0;
            for (int t = 0; t < W; t++) totalDt += dts[t];
            long ta = System.nanoTime();
            int c = Latch.applyAll();
            long da = System.nanoTime() - ta;
            if (r >= warm) {
                int wtCount = wt.get();
                rec[r - warm] = wtCount > 0 ? totalDt / (double) (wtCount * M) : 0.0;
                appl[r - warm] = c > 0 ? da / (double) c : 0.0;
            }
            workerTasks = wt.get();
        }
        FrameBatch.stopForTest();
        // direct (non-worker) cost: tight loop outside a batch, Latch inactive.
        long td0 = System.nanoTime();
        for (int k = 0; k < 20_000; k++) Latch.record(k, op, boxed[k], null, k, 0.0f);
        double directNs = (System.nanoTime() - td0) / 20_000.0;

        java.util.Arrays.sort(rec);
        java.util.Arrays.sort(appl);
        System.out.printf("latch record: %6.2f ns/record%n", rec[3]);
        System.out.printf("latch applyAll: %6.2f ns/record%n", appl[3]);
        System.out.println("latch checksum " + chk[0]);
        System.out.printf("latch worker tasks: %d%n", workerTasks);
        System.out.printf("latch direct (non-worker): %6.2f ns/record%n", directNs);
        LuaGate.resetForTest();
    }

    static void pacing() {
        Object q = new Object();
        FrameClock.frameStartNs = 1L;
        // guard off
        ChunkPacer.resetForTest();
        ChunkPacer.inUpdate = true;
        ChunkPacer.enterThread = Thread.currentThread();
        ChunkPacer.queueRef = q;
        int n = 10_000_000;
        long t0 = System.nanoTime();
        int hits = 0;
        for (int i = 0; i < n; i++) if (ChunkPacer.onPoll(q)) hits++;
        long dt0 = System.nanoTime() - t0;
        // foreign queue
        Object foreign = new Object();
        t0 = System.nanoTime();
        int fHits = 0;
        for (int i = 0; i < n; i++) if (ChunkPacer.onPoll(foreign)) fHits++;
        long dt1 = System.nanoTime() - t0;
        // deciding hold
        ChunkPacer.resetForTest();
        ChunkPacer.guard.on = true;
        ChunkPacer.divisor = 8;
        ChunkPacer.budgetNs = 1_500_000L;
        ChunkPacer.maxWait = 6;
        ChunkPacer.inUpdate = true;
        ChunkPacer.enterThread = Thread.currentThread();
        ChunkPacer.queueRef = q;
        ChunkPacer.finishedThisFrame = 1;
        ChunkPacer.meanJoinNs = 600_000L;
        t0 = System.nanoTime();
        int dHits = 0;
        for (int i = 0; i < n; i++) if (ChunkPacer.onPoll(q)) dHits++;
        long dt2 = System.nanoTime() - t0;
        System.out.printf("pacing guard off: %6.2f ns/call, foreign: %6.2f ns/call, deciding: %6.2f ns/call (hits %d/%d/%d)%n",
            dt0 / (double) n, dt1 / (double) n, dt2 / (double) n, hits, fHits, dHits);
    }
}
