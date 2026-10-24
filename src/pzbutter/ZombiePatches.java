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
}
