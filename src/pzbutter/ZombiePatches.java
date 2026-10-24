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
}
