package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Plan 8 hooks (zombies and entities): one holder per game method. Advice is inlined into the game class, so it reaches only
 * public members: each holder's advice is one call into a public static shim here, which catches its own Throwable into the
 * feature's Guard. Every shim acts only on the game thread and returns at its first static read while its key is off.
 */
public final class ZombiePatches {
    private ZombiePatches() {}

    public static void init() {
        FacingFix.init();
        ZombieLod.init();
        losGuard.on = Settings.flag("playerLosFast");
        CullSort.init();
        ZombieMisc.init();
    }

    /** IsoGameCharacter.setForwardDirection(float, float) entry: true skips the body (the character keeps its facing). */
    public static boolean onForward(float x, float y) {
        try { return FacingFix.onSet(x, y); }
        catch (Throwable t) { FacingFix.guard.fail(t); return false; }
    }

    /** Binds the float pair, so the (float, float) overload only; the Vector2 overload calls it and needs no hook of its own. */
    @Patch(className = "zombie.characters.IsoGameCharacter", methodName = "setForwardDirection")
    public static class ForwardDirection {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) float x, @Patch.Argument(1) float y) { return ZombiePatches.onForward(x, y); }
    }

    // ---- numberZombiesBlended, zombieLodFixed3d, zombieModelAddBudgetUs: the cull loop ----

    /** IsoWorld.sceneCullZombies entry: writes the blend count and opens the cull's counters (game thread, a key on). */
    public static void onCullEnter() {
        if (!ZombieLod.blendGuard.on && !ZombieLod.capGuard.on && !ZombieLod.budgetGuard.on) return;
        try {
            if (Thread.currentThread() == FrameClock.mainThread) ZombieLod.enterCull();
        } catch (Throwable t) { ZombieLod.capGuard.fail(t); }
    }

    /** IsoWorld.sceneCullZombies exit (also on a throw): closes a cull this thread opened, whatever the keys say now. */
    public static void onCullExit() {
        if (!ZombieLod.inCull) return;
        try {
            if (Thread.currentThread() == ZombieLod.cullThread) ZombieLod.exitCull();
        } catch (Throwable t) { ZombieLod.capGuard.fail(t); }
    }

    /**
     * IsoGameCharacter.setSceneCulled entry: the argument the call runs with. Outside a cull (one static read), off the thread that
     * opened it, and nested inside another setSceneCulled the argument is the caller's. A grant of the cull loop (culled false) past
     * the cap or the budget becomes true: stock's own culled branch.
     */
    public static boolean onCulledEnter(Object self, boolean culled) {
        if (!ZombieLod.inCull) return culled;
        try {
            if (Thread.currentThread() != ZombieLod.cullThread) return culled;
            if (ZombieLod.depth++ > 0 || culled) return culled;
            boolean added = ((zombie.characters.IsoGameCharacter) self).isAddedToModelManager();
            if (ZombieLod.decide(false, added)) {
                ZombieLod.inflightRewrite = true;
                return true;
            }
            if (!added && ZombieLod.budgetGuard.on) {
                ZombieLod.inflightAdded = false;
                ZombieLod.inflightT0 = System.nanoTime();
            }
            return false;
        } catch (Throwable t) {
            ZombieLod.capGuard.fail(t);
            ZombieLod.inflightRewrite = false;
            ZombieLod.inflightT0 = 0;
            return culled;
        }
    }

    /** IsoGameCharacter.setSceneCulled exit (also on a throw): finishes what the entry shim began for the outermost call. */
    public static void onCulledExit(Object self) {
        if (!ZombieLod.inCull) return;
        try {
            if (Thread.currentThread() != ZombieLod.cullThread) return;
            if (ZombieLod.depth > 0 && --ZombieLod.depth > 0) return;
            boolean rewritten = ZombieLod.inflightRewrite;
            long t0 = ZombieLod.inflightT0;
            if (!rewritten && t0 == 0) return;
            boolean added = ZombieLod.inflightAdded;
            ZombieLod.inflightRewrite = false;
            ZombieLod.inflightT0 = 0;
            if (rewritten) {
                // stock's culled branch also ends the blend: the call stock would have made past its own limit
                zombie.characters.IsoGameCharacter c = (zombie.characters.IsoGameCharacter) self;
                if (c.hasAnimationPlayer()) c.getAnimationPlayer().doBlending = false;
            } else {
                ZombieLod.granted(added, System.nanoTime() - t0);
            }
        } catch (Throwable t) { ZombieLod.capGuard.fail(t); }
    }

    @Patch(className = "zombie.iso.IsoWorld", methodName = "sceneCullZombies")
    public static class SceneCull {
        @Patch.OnEnter
        public static void enter() { ZombiePatches.onCullEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { ZombiePatches.onCullExit(); }
    }

    /** The only overload; IsoMovingObject.setSceneCulled is a different class and is not hooked. */
    @Patch(className = "zombie.characters.IsoGameCharacter", methodName = "setSceneCulled")
    public static class SceneCulled {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self, @Patch.Argument(value = 0, readOnly = false) boolean culled) {
            culled = ZombiePatches.onCulledEnter(self, culled);
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object self) { ZombiePatches.onCulledExit(self); }
    }

    // ---- zombieSimLodTiles, zombieSimLodSteps: one more update tier ----

    /**
     * MovingObjectUpdateScheduler.getUpdateSchedulerSimulationLevelForObject exit: the level the game chose, lowered by the extra
     * steps when the object is farther than the key's tiles from every local player, then raised back to the object's minimum.
     * The scheduler off, a server, a player, an object already at the lowest level and a thread other than the game's get the game's.
     */
    public static zombie.UpdateSchedulerSimulationLevel onSimLevel(Object scheduler, Object obj, zombie.UpdateSchedulerSimulationLevel ret) {
        if (!ZombieLod.simGuard.on || ret == null) return ret;
        try {
            if (Thread.currentThread() != FrameClock.mainThread) return ret;
            if (ret == zombie.UpdateSchedulerSimulationLevel.SIXTEENTH) return ret;
            if (!((zombie.MovingObjectUpdateScheduler) scheduler).isEnabled() || zombie.network.GameServer.server) return ret;
            zombie.iso.IsoMovingObject o = (zombie.iso.IsoMovingObject) obj;
            float minDist = 1e8f;
            zombie.characters.IsoPlayer[] ps = zombie.characters.IsoPlayer.players;
            for (int i = 0, n = zombie.characters.IsoPlayer.numPlayers; i < n; i++) {
                zombie.characters.IsoPlayer p = ps[i];
                if (p == null) continue;
                if (p == o) return ret;
                float d = o.DistTo(p);
                if (d < minDist) minDist = d;
            }
            zombie.UpdateSchedulerSimulationLevel l = ZombieLod.lower(ret, minDist, ZombieLod.simTiles, ZombieLod.simSteps);
            return l == ret ? ret : l.max(o.getMinimumSimulationLevel());
        } catch (Throwable t) {
            ZombieLod.simGuard.fail(t);
            return ret;
        }
    }

    /** The private classifier; no onThrowable, so its own exception passes unchanged. */
    @Patch(className = "zombie.MovingObjectUpdateScheduler", methodName = "getUpdateSchedulerSimulationLevelForObject")
    public static class SimLevel {
        @Patch.OnExit
        public static void exit(@Patch.This Object s, @Patch.Argument(0) Object obj,
                                @Patch.Return(readOnly = false) zombie.UpdateSchedulerSimulationLevel ret) {
            ret = ZombiePatches.onSimLevel(s, obj, ret);
        }
    }

    // ---- playerLosFast: the sight pass's seen-before checks ----

    static final Guard losGuard = new Guard("playerLosFast");

    /**
     * IsoPlayer.updateLOS entry: swaps the player's {@code lastSpotted} Stack for a {@link SpottedStack} holding the same elements,
     * once per player (the getter returns the new one; a Java mod holding the old object sees a list that no longer changes).
     */
    public static void onLosEnter(Object player) {
        if (!losGuard.on) return;
        try {
            if (Thread.currentThread() != FrameClock.mainThread) return;
            zombie.characters.IsoPlayer p = (zombie.characters.IsoPlayer) player;
            java.util.Stack<zombie.iso.IsoMovingObject> s = p.getLastSpotted();
            if (s != null && !(s instanceof SpottedStack)) p.setLastSpotted(new SpottedStack<>(s));
        } catch (Throwable t) { losGuard.fail(t); }
    }

    /** IsoPlayer.updateLOS exit (also on a throw): nothing yet (the timing call joins it with entityTiming). */
    public static void onLosExit() {}

    /** IsoAnimal declares its own updateLOS, so only IsoPlayer's body is instrumented. */
    @Patch(className = "zombie.characters.IsoPlayer", methodName = "updateLOS")
    public static class UpdateLos {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) { ZombiePatches.onLosEnter(self); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { ZombiePatches.onLosExit(); }
    }

    // ---- zombieCullSortFast: the 3D-model sort ----

    /**
     * TimSort.doSort entry: the comparator the sort runs with. The game's {@code compScoreToPlayer} (found by its class the first
     * time, bound once) on the game thread, for two or more elements, becomes a comparator over scores worked out once; every other
     * comparator, thread and range is the caller's. A score that throws lets the game's own sort run (and throw as it would).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static java.util.Comparator onSortEnter(Object[] a, java.util.Comparator c, int lo, int hi) {
        if (!CullSort.guard.on) return c;
        try {
            java.util.Comparator s = CullSort.stock;
            if (c != s && (s != null || c == null || c.getClass() != CullSort.scoreClass)) return c;
            if (Thread.currentThread() != FrameClock.mainThread || hi - lo < 2) return c;
            if (s == null) CullSort.bind(c);
            return CullSort.prepare(a, lo, hi, CullSort.score);
        } catch (Throwable t) {
            CullSort.guard.fail(t);
            return c;
        }
    }

    /** TimSort.doSort exit (also on a throw): drops the stored scores of a comparator ours made. */
    public static void onSortExit(java.util.Comparator c) {
        if (!(c instanceof CullSort.Prepared)) return;
        try { CullSort.done(c); }
        catch (Throwable t) { CullSort.guard.fail(t); }
    }

    /** The only overload with a Comparator. Other callers (render code) pass other comparators and cost one compare. */
    @Patch(className = "zombie.entity.util.TimSort", methodName = "doSort")
    public static class CullSortHook {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object[] a, @Patch.Argument(value = 1, readOnly = false) java.util.Comparator c,
                                 @Patch.Argument(2) int lo, @Patch.Argument(3) int hi) {
            c = ZombiePatches.onSortEnter(a, c, lo, hi);
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Argument(1) java.util.Comparator c) { ZombiePatches.onSortExit(c); }
    }

    // ---- statsNoBox, popmanFlagsFlyweight: two allocation savers ----

    /** Stats.get entry: -1 runs stock; else the answer packed by ZombieMisc.statsGet. Any thread. */
    public static long onStatsGet(Object self, Object stat) {
        if (!ZombieMisc.statsGuard.on) return -1;
        try { return ZombieMisc.statsGet(self, stat); }
        catch (Throwable t) { ZombieMisc.statsGuard.fail(t); return -1; }
    }

    /** ZombieStateFlags.fromInt entry: the shared object for 0 to 63, else null (stock builds its own). Any thread. */
    public static Object onFromInt(int v) {
        if (!ZombieMisc.flagsGuard.on) return null;
        try { return ZombieMisc.flags(v); }
        catch (Throwable t) { ZombieMisc.flagsGuard.fail(t); return null; }
    }

    /** The only overload. The answer rides in a long local (bit 32 set) so nothing is boxed. */
    @Patch(className = "zombie.characters.Stats", methodName = "get")
    public static class StatsGet {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object stat, @Patch.Local("r") long r) {
            r = ZombiePatches.onStatsGet(self, stat);
            return r >= 0;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) float ret, @Patch.Local("r") long r) {
            if (r >= 0) ret = Float.intBitsToFloat((int) r);
        }
    }

    /** The only fromInt (static). */
    @Patch(className = "zombie.popman.ZombieStateFlags", methodName = "fromInt")
    public static class FlagsFromInt {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int v, @Patch.Local("f") Object f) {
            f = ZombiePatches.onFromInt(v);
            return f != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) zombie.popman.ZombieStateFlags ret, @Patch.Local("f") Object f) {
            if (f != null) ret = (zombie.popman.ZombieStateFlags) f;
        }
    }
}
