package pzbutter;

import java.lang.invoke.VarHandle;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.GameWindow;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;

/**
 * One patch holder per hooked game method; each calls the frame-delivery features in a fixed order
 * (ZombieBuddy gives no order between separate advices on one method). ZombieBuddy inlines advice into the
 * game class, so each advice is one call to a public static method here; those do the work and catch Throwable.
 */
public final class DeliveryPatches {
    private DeliveryPatches() {}

    private static VarHandle currentTime, accumulator;
    private static boolean bound;

    /** GameWindow.mainThreadStep entry (game thread, between frames too). */
    public static void onMainThreadStep() {
        if (!LimiterSleep.guard.on) return;
        try {
            if (!bound) {   // GameWindow is loaded by now; binding at mod load would load it early
                currentTime = Game.staticLong(GameWindow.class, "currentTime");
                accumulator = Game.staticLong(GameWindow.class, "accumulator");
                bound = true;
            }
            if (currentTime == null || accumulator == null) { LimiterSleep.guard.on = false; return; }
            int fps = PerformanceSettings.getLockFPS();
            if (!LimiterSleep.active(PerformanceSettings.instance.isFramerateUncapped(), fps)) return;
            long s = LimiterSleep.sleepNanos(System.nanoTime(), (long) currentTime.get(), (long) accumulator.get(),
                1_000_000_000L / fps, LimiterSleep.SPIN_NS);
            if (s > 0) LimiterSleep.park(s + LimiterSleep.extraDelayNs);
        } catch (Throwable t) { LimiterSleep.guard.fail(t); }
    }

    /** GameWindow.frameStep exit (game thread), after FrameClock.exit. */
    public static void onFrameEnd() { PresentLog.tick(); }

    /** MainThread.invokeOnMainThread / invokeQueryOnMainThread / queueInvokeOnMainThread entry (any thread). */
    public static void onMainQueue() { LimiterSleep.wake(); }

    /** SpriteRenderer.pushFrameDown entry (game thread). */
    public static void onPush() {
        if (!FrameStamps.guard.on) return;
        try { FrameStamps.onPush(SpriteRenderer.instance.getPopulatingState()); }
        catch (Throwable t) { FrameStamps.guard.fail(t); }
    }

    /** SpriteRenderer.acquireStateForRendering exit (render thread), with the state it returns. */
    public static void onAcquire(Object state) {
        if (!FrameStamps.guard.on || state == null) return;
        try { FrameStamps.onAcquire(state); }
        catch (Throwable t) { FrameStamps.guard.fail(t); }
    }

    /** Display.swapBuffers entry (render thread). */
    public static void onSwapStart() {
        if (!PresentLog.guard.on) return;
        try { PresentLog.swapStart(); } catch (Throwable t) { PresentLog.guard.fail(t); }
    }

    /** Display.swapBuffers exit (render thread). */
    public static void onSwapEnd() {
        if (!PresentLog.guard.on) return;
        try { PresentLog.swapEnd(); } catch (Throwable t) { PresentLog.guard.fail(t); }
    }

    @Patch(className = "zombie.GameWindow", methodName = "mainThreadStep")
    public static class Step {
        @Patch.OnEnter
        public static void enter() { onMainThreadStep(); }
    }

    // anything queued for the main thread wakes the limiter, so callers that wait for it are not held a frame
    @Patch(className = "zombie.MainThread", methodName = "invokeOnMainThread")
    public static class InvokeWake {
        @Patch.OnEnter
        public static void enter() { onMainQueue(); }
    }

    @Patch(className = "zombie.MainThread", methodName = "invokeQueryOnMainThread")
    public static class QueryWake {
        @Patch.OnEnter
        public static void enter() { onMainQueue(); }
    }

    @Patch(className = "zombie.MainThread", methodName = "queueInvokeOnMainThread")
    public static class QueueWake {
        @Patch.OnEnter
        public static void enter() { onMainQueue(); }
    }

    @Patch(className = "zombie.core.SpriteRenderer", methodName = "pushFrameDown")
    public static class Push {
        @Patch.OnEnter
        public static void enter() { onPush(); }
    }

    @Patch(className = "zombie.core.SpriteRenderer", methodName = "acquireStateForRendering")
    public static class Acquire {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Return Object state) { onAcquire(state); }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "swapBuffers")
    public static class Swap {
        @Patch.OnEnter
        public static void enter() { onSwapStart(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onSwapEnd(); }
    }
}
