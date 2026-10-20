package pzbutter;

/**
 * aimHoldMs (SPEC 3.8): how long the right button is held before aiming starts. The game's Mouse.isRightDelay()
 * is !uiCaptured[1] && button down && held >= 0.15 s; this rewrites its answer with aimHoldMs. 150 is stock, so it only
 * applies when the value differs.
 */
final class AimHold {
    static final Guard guard = new Guard("aimHoldMs");
    static volatile int holdMs = 150;

    private AimHold() {}

    static void init() {
        holdMs = Settings.num("aimHoldMs", 150, 0, 5000);
        guard.on = Settings.enabled && holdMs != 150;
    }

    /** As the game's check: not captured by UI, button down, held long enough. */
    static boolean delayed(boolean uiCaptured, boolean rightDown, float heldSeconds, int holdMs) {
        return !uiCaptured && rightDown && heldSeconds >= holdMs / 1000f;
    }
}
