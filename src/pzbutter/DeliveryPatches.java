package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjglx.opengl.Display;
import zombie.GameWindow;
import zombie.core.PerformanceSettings;
import zombie.core.SpriteRenderer;
import zombie.input.GameKeyboard;
import zombie.input.KeyboardState;
import zombie.input.KeyboardStateCache;
import zombie.input.MouseState;
import zombie.input.MouseStateCache;

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

    // ---- input (InputLatch) ----

    private static VarHandle kbCache, mouseCache, kbLock, mouseLock, keyDown, mouseButtons, wheel;
    private static MethodHandle kbPolling, mousePolling;
    private static boolean inputBound;
    private static Object lastKeyboardRead;
    private static final boolean[] keysBefore = new boolean[256], buttonsBefore = new boolean[16];

    private static boolean bindInput() {
        if (!inputBound) {
            inputBound = true;
            kbCache = Game.staticField(GameKeyboard.class, "s_keyboardStateCache", KeyboardStateCache.class);
            mouseCache = Game.staticField(zombie.input.Mouse.class, "s_mouseStateCache", MouseStateCache.class);
            kbLock = Game.field(KeyboardStateCache.class, "lock", Object.class);
            mouseLock = Game.field(MouseStateCache.class, "lock", Object.class);
            keyDown = Game.field(KeyboardState.class, "keyDownStates", boolean[].class);
            mouseButtons = Game.field(MouseState.class, "buttonDownStates", boolean[].class);
            wheel = Game.field(MouseState.class, "wheelDelta", int.class);
            kbPolling = Game.method(KeyboardStateCache.class, "getStatePolling", MethodType.methodType(KeyboardState.class));
            mousePolling = Game.method(MouseStateCache.class, "getStatePolling", MethodType.methodType(MouseState.class));
        }
        boolean ok = kbCache != null && mouseCache != null && kbLock != null && mouseLock != null && keyDown != null
            && mouseButtons != null && wheel != null && kbPolling != null && mousePolling != null;
        if (!ok) InputLatch.guard.on = false;
        return ok;
    }

    /** frameStep entry, before FrameClock.enter (game thread): frameStartGate. */
    public static void onFrameGate() {
        if (!InputLatch.guard.on || !InputLatch.gate) return;
        try {
            long want = FrameClock.frameNo, prevStart = FrameClock.frameStartNs;
            if (want <= 0 || prevStart == 0) return;
            int fps = PerformanceSettings.getLockFPS();
            long period = PerformanceSettings.instance.isFramerateUncapped() || fps <= 0
                ? Math.min(Parker.MAX_NS, (long) (FrameClock.intervalMs * 1e6)) : 1_000_000_000L / fps;
            long w;
            while ((w = InputLatch.gateWaitNs(System.nanoTime(), PresentLog.lastPresentedFrameNo, want, prevStart, period)) > 0)
                InputLatch.waitShown.park(w);
        } catch (Throwable t) { InputLatch.guard.fail(t); }
    }

    /** frameStep entry, after FrameClock.enter (game thread): the input age of this frame. */
    public static void onFrameStarted() {
        if (!InputLatch.guard.on || !FrameStamps.guard.on) return;
        try { FrameStamps.pendingInputAgeNs = InputLatch.ageAt(lastKeyboardRead, FrameClock.frameStartNs); }
        catch (Throwable t) { InputLatch.guard.fail(t); }
    }

    /** GameKeyboard.update entry (game thread): keyboardFresh swaps first; remember which state update reads. */
    public static void onKeyboardUpdate() {
        if (!InputLatch.guard.on) return;
        try {
            if (!bindInput()) return;
            KeyboardStateCache c = (KeyboardStateCache) kbCache.get();
            if (InputLatch.fresh) c.swap();
            lastKeyboardRead = c.getState();
        } catch (Throwable t) { InputLatch.guard.fail(t); }
    }

    /** KeyboardStateCache.swap / MouseStateCache.swap entry (game thread): inputLatch's fresh poll, once a frame. */
    public static void onInputSwap() {
        if (!InputLatch.guard.on || !InputLatch.latch) return;
        try {
            if (!InputLatch.request(FrameClock.frameNo)) return;
            SpriteRenderer.instance.notifyRenderStateQueue();   // wakes a render thread waiting for a frame
            InputLatch.awaitServed();
        } catch (Throwable t) { InputLatch.guard.fail(t); }
    }

    /** RenderThread.flushInvokeQueue entry (render thread, every loop pass and every wake while waiting). */
    public static void onRenderFlush() {
        if (!InputLatch.guard.on || !InputLatch.latch || !InputLatch.pending()) return;
        try {
            if (!bindInput()) return;
            Display.processMessages();
            KeyboardStateCache kc = (KeyboardStateCache) kbCache.get();
            synchronized (kbLock.get(kc)) {
                KeyboardState s = (KeyboardState) kbPolling.invoke(kc);
                boolean[] a = (boolean[]) keyDown.get(s);
                boolean again = InputLatch.shouldRepoll(s.wasPolled(), true) && a != null;
                if (again) System.arraycopy(a, 0, keysBefore, 0, Math.min(a.length, keysBefore.length));
                s.poll();
                if (again) InputLatch.mergeDown((boolean[]) keyDown.get(s), keysBefore);
            }
            MouseStateCache mc = (MouseStateCache) mouseCache.get();
            synchronized (mouseLock.get(mc)) {
                MouseState s = (MouseState) mousePolling.invoke(mc);
                boolean[] b = (boolean[]) mouseButtons.get(s);
                boolean again = InputLatch.shouldRepoll(s.wasPolled(), true) && b != null;
                int wheelBefore = (int) wheel.get(s);
                if (again) System.arraycopy(b, 0, buttonsBefore, 0, Math.min(b.length, buttonsBefore.length));
                s.poll();
                if (again) {
                    InputLatch.mergeDown((boolean[]) mouseButtons.get(s), buttonsBefore);
                    wheel.set(s, InputLatch.mergeWheel(wheelBefore, (int) wheel.get(s)));
                }
            }
        } catch (Throwable t) { InputLatch.guard.fail(t); }
        finally { InputLatch.markServed(); }
    }

    /** KeyboardState.poll entry (render thread): when this state's input was read. */
    public static void onKeyboardPolled(Object state) {
        if (!InputLatch.guard.on) return;
        try { InputLatch.notePoll(state, System.nanoTime()); } catch (Throwable t) { InputLatch.guard.fail(t); }
    }

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
        if (state == null) {
            if (RenderIdle.guard.on) try { RenderIdle.park(RenderIdle.WAIT_NS); } catch (Throwable t) { RenderIdle.guard.fail(t); }
            return;
        }
        if (!FrameStamps.guard.on) return;
        try { FrameStamps.onAcquire(state); }
        catch (Throwable t) { FrameStamps.guard.fail(t); }
    }

    /** SpriteRenderer / RenderThread notifyRenderStateQueue exit (any thread). */
    public static void onRenderNotify() { RenderIdle.wake(); }

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

    @Patch(className = "zombie.input.GameKeyboard", methodName = "update")
    public static class KeyboardUpdate {
        @Patch.OnEnter
        public static void enter() { onKeyboardUpdate(); }
    }

    @Patch(className = "zombie.input.KeyboardStateCache", methodName = "swap")
    public static class KeyboardSwap {
        @Patch.OnEnter
        public static void enter() { onInputSwap(); }
    }

    @Patch(className = "zombie.input.MouseStateCache", methodName = "swap")
    public static class MouseSwap {
        @Patch.OnEnter
        public static void enter() { onInputSwap(); }
    }

    @Patch(className = "zombie.core.opengl.RenderThread", methodName = "flushInvokeQueue")
    public static class RenderFlush {
        @Patch.OnEnter
        public static void enter() { onRenderFlush(); }
    }

    @Patch(className = "zombie.input.KeyboardState", methodName = "poll")
    public static class KeyboardPolled {
        @Patch.OnEnter
        public static void enter(@Patch.This Object state) { onKeyboardPolled(state); }
    }

    // the game's own wake-up for a waiting render thread also ends renderIdleWait's park
    @Patch(className = "zombie.core.SpriteRenderer", methodName = "notifyRenderStateQueue")
    public static class SpriteNotify {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onRenderNotify(); }
    }

    @Patch(className = "zombie.core.opengl.RenderThread", methodName = "notifyRenderStateQueue")
    public static class RenderNotify {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onRenderNotify(); }
    }

    // a thread that wants the GL context waits for RenderThread.m_contextLock, which renderIdleWait's park
    // holds; waking the park first lets the render thread finish its pass and release the lock, as the spin does
    @Patch(className = "zombie.core.opengl.RenderThread", methodName = "acquireContextReentrant")
    public static class ContextWake {
        @Patch.OnEnter
        public static void enter() { onRenderNotify(); }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "swapBuffers")
    public static class Swap {
        @Patch.OnEnter
        public static void enter() { onSwapStart(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onSwapEnd(); }
    }
}
