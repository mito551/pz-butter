package pzbutter;

/**
 * renderIdleWait (pz-butter's own): when the game turns its render-state wait off (after leaving a world,
 * during a Lua reload), the render thread spins: acquireStateForRendering returns null and the loop yields.
 * Then it parks up to WAIT_NS instead, and wakes on the game's own notifyRenderStateQueue (a frame handed
 * over, a render-context request), the same signal the game's wait uses.
 */
final class RenderIdle {
    static final Guard guard = new Guard("renderIdleWait");
    static final long WAIT_NS = 1_000_000L;
    private static final Parker parker = new Parker();

    private RenderIdle() {}

    static void init() { guard.on = Settings.flag("renderIdleWait"); }

    static void park(long ns) { parker.park(ns); }

    static void wake() { parker.wake(); }
}
