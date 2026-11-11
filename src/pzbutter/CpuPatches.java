package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * lowerCpuMode (SPEC 3.x): sleep after each main, lighting and render pass while the window
 * is not in front or the game is paused. Modes: 1 = paused or inactive; 2 = only when paused
 * AND inactive; 3 = always, frame cap by sleeping 1000/fps - spent - 2 ms (at least 1 ms);
 * 4 = never. A fully inactive game sleeps 32 ms (main), 100 ms (lighting), 16 ms (render);
 * otherwise 1 ms. lowerCpuMode = 0 / anything unreadable = stock.
 */
public final class CpuPatches {
    public static final Guard guard = new Guard("lowerCpuMode");

    public static final int MAIN = 0, RENDER = 1, LIGHT = 2;
    public static final int MAIN_DEEP_MS = 32, LIGHT_DEEP_MS = 100, RENDER_DEEP_MS = 16;
    public static final long SLEEP_MS = 1, CAP_SLACK_MS = 2;

    CpuPatches() {}

    static void init() {
        try {
            guard.on = Settings.num("lowerCpuMode", 0, 0, 4) >= 1 && Settings.num("lowerCpuMode", 0, 0, 4) <= 3;
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    public static java.util.function.BooleanSupplier PAUSED = () -> zombie.GameTime.isGamePaused();
    public static java.util.function.BooleanSupplier ACTIVE = () -> org.lwjglx.opengl.Display.isActive();
    public static java.util.function.IntSupplier FPS = () -> zombie.core.PerformanceSettings.getLockFPS();
    public static java.util.function.LongConsumer SLEEP = ms -> {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    public static int mode() {
        try {
            return Settings.num("lowerCpuMode", 0, 0, 4);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long deepMs(int thread) {
        return thread == MAIN ? MAIN_DEEP_MS : thread == LIGHT ? LIGHT_DEEP_MS : RENDER_DEEP_MS;
    }

    /** ms to sleep after one pass; 0 = stock (never). Modes 1/2 never sleep in an active unpaused window. */
    public static long sleepMs(int thread, long spentMs) {
        int m = mode();
        if (m <= 0 || m == 4) return 0;
        boolean paused = PAUSED.getAsBoolean();
        boolean active = ACTIVE.getAsBoolean();
        if (!active) {
            if (m == 2) return paused ? SLEEP_MS : 0;
            return deepMs(thread);
        }
        if (m == 3) {
            int fps = FPS.getAsInt();
            if (fps <= 0) return SLEEP_MS;
            return Math.max(SLEEP_MS, (1000 / fps) - spentMs - CAP_SLACK_MS);
        }
        return paused ? SLEEP_MS : 0;
    }

    public static long spent(long enterNs) {
        return Math.max(0, (System.nanoTime() - enterNs) / 1_000_000L);
    }

    public static long mainEnterNs, renderEnterNs, lightEnterNs;

    @Patch(className = "zombie.GameWindow", methodName = "mainThreadStep")
    public static class MainPass {
        @Patch.OnEnter
        public static void enter() {
            mainEnterNs = System.nanoTime();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            if (!guard.on) return;
            try {
                long ms = sleepMs(MAIN, spent(mainEnterNs));
                if (ms > 0) SLEEP.accept(ms);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    @Patch(className = "zombie.core.opengl.RenderThread", methodName = "renderStep")
    public static class RenderPass {
        @Patch.OnEnter
        public static void enter() {
            renderEnterNs = System.nanoTime();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            if (!guard.on) return;
            try {
                long ms = sleepMs(RENDER, spent(renderEnterNs));
                if (ms > 0) SLEEP.accept(ms);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    @Patch(className = "zombie.iso.LightingThread", methodName = "runInner")
    public static class LightPass {
        @Patch.OnEnter
        public static void enter() {
            lightEnterNs = System.nanoTime();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            if (!guard.on) return;
            try {
                long ms = sleepMs(LIGHT, spent(lightEnterNs));
                if (ms > 0) SLEEP.accept(ms);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }
}
