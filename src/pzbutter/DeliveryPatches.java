package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeGLX;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLXNVDelayBeforeSwap;
import org.lwjglx.opengl.Display;
import zombie.GameWindow;
import zombie.core.Core;
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
        if (!LimiterSleep.guard.on && !Reflex.sleepGuard.on) return;
        try {
            if (!bound) {   // GameWindow is loaded by now; binding at mod load would load it early
                currentTime = Game.staticLong(GameWindow.class, "currentTime");
                accumulator = Game.staticLong(GameWindow.class, "accumulator");
                bound = true;
            }
            if (currentTime == null || accumulator == null) { LimiterSleep.guard.on = false; return; }
            int fps = PerformanceSettings.getLockFPS();
            if (!LimiterSleep.active(PerformanceSettings.instance.isFramerateUncapped(), fps)) return;
            long period = 1_000_000_000L / fps;
            long s = LimiterSleep.sleepNanos(System.nanoTime(), (long) currentTime.get(), (long) accumulator.get(),
                period, LimiterSleep.SPIN_NS);
            if (LimiterSleep.guard.on && s > 0) { LimiterSleep.park(s); return; }
            long extra = Reflex.sleepGuard.on ? Reflex.take(s) : 0;
            if (extra > 0) LimiterSleep.park(Math.min(extra, period));
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

    /** IngameState.enter exit / exit entry (game thread): reflexBoost. */
    public static void onWorldEnter() { Reflex.enterWorld(); }

    public static void onWorldExit() { Reflex.exitWorld(); }

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

    /** SpriteRenderer.postRender entry / exit (render thread): GPU timestamps for frameQueueGpu. */
    public static void onPostRenderStart() {
        if (!GpuTimer.guard.on) return;
        try { GpuTimer.begin(); } catch (Throwable t) { GpuTimer.guard.fail(t); }
    }

    public static void onPostRenderEnd() {
        if (!GpuTimer.guard.on) return;
        try { GpuTimer.end(); } catch (Throwable t) { GpuTimer.guard.fail(t); }
    }

    /** SpriteRenderer / RenderThread notifyRenderStateQueue exit (any thread). */
    public static void onRenderNotify() { RenderIdle.wake(); }

    /** Display.swapBuffers entry (render thread): GPU queue limit, the hold, then the swap timing. */
    public static void onSwapStart() {
        Thread me = Thread.currentThread();
        if (PresentLog.renderThread == null) PresentLog.renderThread = me;
        else if (PresentLog.renderThread != me) return;
        if (GpuQueue.guard.on) try { GpuQueue.beforeSwap(); } catch (Throwable t) { GpuQueue.guard.fail(t); }
        if (SwapHold.guard.on) holdSwap();
        if (VblankLock.guard.on) vblankWait();
        if (!PresentLog.guard.on) return;
        try { PresentLog.swapStart(); } catch (Throwable t) { PresentLog.guard.fail(t); }
    }

    private static void holdSwap() {
        try {
            if (!SwapHold.holding() || FrameStamps.renderFrameNo < 0) return;
            int fps = PerformanceSettings.getLockFPS();
            int cap = PerformanceSettings.instance.isFramerateUncapped() ? 0 : fps;
            if (cap <= 0) return;
            long period = 1_000_000_000L / cap, start = FrameStamps.renderFrameStartNs, now = System.nanoTime();
            long gpu = SwapHold.gpu && GpuTimer.guard.on ? Math.max(0, GpuTimer.lastGpuNs) : 0;
            SwapHold.learner.observe(now - start + gpu, now, period);
            long w = SwapHold.waitNs(now, start, SwapHold.holdNs(period, cap, SwapHold.queue, SwapHold.learner.holdNs(), SwapHold.config));
            if (w > 0) SwapHold.waitPrecise(w);
        } catch (Throwable t) { SwapHold.guard.fail(t); }
    }

    /** Display.swapBuffers exit (render thread). */
    public static void onSwapEnd() {
        if (Thread.currentThread() != PresentLog.renderThread) return;
        if (PresentLog.guard.on) try { PresentLog.swapEnd(); } catch (Throwable t) { PresentLog.guard.fail(t); }
        if (Reflex.sleepGuard.on) Reflex.onSwap(PresentLog.lastSwapNs);
        if (VblankLock.guard.on) VblankLock.smoothedSwapNs = Reflex.ewma(VblankLock.smoothedSwapNs, PresentLog.lastSwapNs);
        if (GpuQueue.guard.on) try { GpuQueue.afterSwap(); } catch (Throwable t) { GpuQueue.guard.fail(t); }
        if (Caps.guard.on || Vrr.guard.on) updateRefresh();
    }

    // ---- vblankLock ----

    private static long glxDisplay, glxDrawable;
    private static boolean vblankChecked;

    private static void vblankWait() {
        try {
            if (!vblankChecked) {
                vblankChecked = true;
                int platform = GLFW.glfwGetPlatform();
                long dpy = platform == GLFW.GLFW_PLATFORM_X11 ? GLFWNativeX11.glfwGetX11Display() : 0;
                boolean ext = dpy != 0 && GL.createCapabilitiesGLX(dpy).GLX_NV_delay_before_swap;
                if (!VblankLock.available(ext, platform, GLFW.GLFW_PLATFORM_X11)) {
                    VblankLock.guard.on = false;
                    System.out.println("[PZButter] vblankLock off: needs GLFW on X11 and GLX_NV_delay_before_swap");
                    return;
                }
                glxDisplay = dpy;
                glxDrawable = GLFWNativeGLX.glfwGetGLXWindow(Display.getWindow());
            }
            if (vsyncOn()) GLXNVDelayBeforeSwap.glXDelayBeforeSwapNV(glxDisplay, glxDrawable, VblankLock.seconds(VblankLock.smoothedSwapNs));
        } catch (Throwable t) { VblankLock.guard.fail(t); }
    }

    // ---- caps (Caps) ----

    private static VarHandle lockFpsField, uncappedField, vsyncField;
    private static boolean capsBound;
    private static long lastRefreshNs;

    private static void bindCaps() {
        if (capsBound) return;
        capsBound = true;
        lockFpsField = Game.staticField(PerformanceSettings.class, "lockFps", int.class);
        uncappedField = Game.staticField(PerformanceSettings.class, "uncappedFps", boolean.class);
        vsyncField = Game.staticField(Display.class, "vsyncEnabled", boolean.class);
    }

    private static boolean vsyncOn() {
        bindCaps();
        return vsyncField != null && (boolean) vsyncField.get();
    }

    private static int capNow() {
        bindCaps();
        if (lockFpsField == null || uncappedField == null || vsyncField == null) { Caps.guard.on = false; return 0; }
        return Caps.capFor((int) lockFpsField.get(), (boolean) uncappedField.get(), Caps.refreshHz,
            Caps.vrrCap && Boolean.TRUE.equals(Vrr.active()), (boolean) vsyncField.get(), Caps.reflex, Caps.reflexCapFps);
    }

    /** PerformanceSettings.getLockFPS exit: the cap the game runs at. */
    public static int capLockFps(int stock) {
        if (!Caps.guard.on) return stock;
        try { int c = capNow(); return c > 0 ? c : stock; }
        catch (Throwable t) { Caps.guard.fail(t); return stock; }
    }

    /** PerformanceSettings.isFramerateUncapped exit: a cap applied by Caps means not uncapped. */
    public static boolean capUncapped(boolean stock) {
        if (!Caps.guard.on || !stock) return stock;
        try { return capNow() <= 0; }
        catch (Throwable t) { Caps.guard.fail(t); return stock; }
    }

    /** Display.setVSyncEnabled exit (the thread with the GL context): vsyncAdaptive. */
    public static void onVsyncSet(boolean enabled) {
        if (!Caps.guard.on || !Caps.adaptive) return;
        try {
            boolean tear = GLFW.glfwExtensionSupported("GLX_EXT_swap_control_tear") || GLFW.glfwExtensionSupported("WGL_EXT_swap_control_tear");
            if (enabled && !tear) System.out.println("[PZButter] vsyncAdaptive: no swap_control_tear, swap interval stays 1");
            if (Caps.swapInterval(enabled, true, tear) == -1) GLFW.glfwSwapInterval(-1);
        } catch (Throwable t) { Caps.guard.fail(t); }
    }

    /** Render thread, at most once a second: the refresh rate and rectangle of the window's monitor. */
    private static void updateRefresh() {
        long now = System.nanoTime();
        if (lastRefreshNs != 0 && now - lastRefreshNs < 1_000_000_000L) return;
        lastRefreshNs = now;
        try {
            PointerBuffer mons = GLFW.glfwGetMonitors();
            if (mons == null) return;
            int n = mons.remaining();
            long[] ids = new long[n];
            int[][] rects = new int[n][];
            int[] px = new int[1], py = new int[1];
            for (int i = 0; i < n; i++) {
                ids[i] = mons.get(mons.position() + i);
                GLFW.glfwGetMonitorPos(ids[i], px, py);
                GLFWVidMode v = GLFW.glfwGetVideoMode(ids[i]);
                rects[i] = v == null ? new int[4] : new int[] {px[0], py[0], v.width(), v.height()};
            }
            int i = Caps.monitorAt(rects, Display.getX() + Display.getWidth() / 2, Display.getY() + Display.getHeight() / 2);
            windowMonitor = i >= 0 ? ids[i] : GLFW.glfwGetPrimaryMonitor();
            GLFWVidMode v = GLFW.glfwGetVideoMode(windowMonitor);
            if (v != null) Caps.refreshHz = v.refreshRate();
            Caps.monitorRect = i >= 0 ? rects[i] : null;
            if (Vrr.guard.on && refreshCount++ % 5 == 0) {
                Vrr.update(Caps.monitorRect, Caps.refreshHz);
                applyFullscreenWindow(Core.getInstance().isFullScreen());
            }
        } catch (Throwable t) { Caps.guard.fail(t); }
    }

    private static long windowMonitor;
    private static int refreshCount;

    // ---- borderlessFullscreen (Vrr) ----

    /** Display.setDisplayModeAndFullscreenInternal exit (render thread). */
    public static void onDisplayModeSet(boolean fullscreen) {
        if (!Vrr.guard.on) return;
        Vrr.fullscreenWindowApplied = false;
        applyFullscreenWindow(fullscreen);
    }

    private static void applyFullscreenWindow(boolean fullscreen) {
        try {
            if (Vrr.fullscreenWindowApplied
                || !Vrr.wantFullscreenWindow(Vrr.fullscreenWindow, Vrr.active(), Display.isBorderlessWindow(), fullscreen)) return;
            long m = windowMonitor != 0 ? windowMonitor : GLFW.glfwGetPrimaryMonitor();
            GLFWVidMode v = GLFW.glfwGetVideoMode(m);
            if (v == null) return;
            GLFW.glfwSetWindowMonitor(Display.getWindow(), m, 0, 0, v.width(), v.height(), v.refreshRate());
            Vrr.fullscreenWindowApplied = true;
            System.out.println("[PZButter] borderlessFullscreen: fullscreen window at " + v.width() + "x" + v.height() + "@" + v.refreshRate());
        } catch (Throwable t) { Vrr.guard.fail(t); }
    }

    /** Display.isFullscreen exit: the window made fullscreen by borderlessFullscreen is still borderless to the game. */
    public static boolean reportFullscreen(boolean stock) { return stock && !Vrr.fullscreenWindowApplied; }

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

    @Patch(className = "zombie.core.SpriteRenderer", methodName = "postRender")
    public static class PostRender {
        @Patch.OnEnter
        public static void enter() { onPostRenderStart(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onPostRenderEnd(); }
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

    @Patch(className = "zombie.gameStates.IngameState", methodName = "enter")
    public static class WorldEnter {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onWorldEnter(); }
    }

    @Patch(className = "zombie.gameStates.IngameState", methodName = "exit")
    public static class WorldExit {
        @Patch.OnEnter
        public static void enter() { onWorldExit(); }
    }

    @Patch(className = "zombie.core.PerformanceSettings", methodName = "getLockFPS")
    public static class LockFps {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) { ret = capLockFps(ret); }
    }

    @Patch(className = "zombie.core.PerformanceSettings", methodName = "isFramerateUncapped")
    public static class Uncapped {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret) { ret = capUncapped(ret); }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "setVSyncEnabled")
    public static class Vsync {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Argument(0) boolean enabled) { onVsyncSet(enabled); }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "setDisplayModeAndFullscreenInternal")
    public static class DisplayModeSet {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Argument(1) boolean fullscreen) { onDisplayModeSet(fullscreen); }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "isFullscreen")
    public static class IsFullscreen {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret) { ret = reportFullscreen(ret); }
    }

    @Patch(className = "org.lwjglx.opengl.Display", methodName = "swapBuffers")
    public static class Swap {
        @Patch.OnEnter
        public static void enter() { onSwapStart(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onSwapEnd(); }
    }
}
