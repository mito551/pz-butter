package pzbutter;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLCapabilities;

/**
 * gpuMaxFrames (SPEC 3.8): a fence after each swap; before the next swap the render thread waits (bounded) for the
 * fence gpuMaxFrames swaps back, so at most that many frames are queued for the GPU. Render thread only.
 */
final class GpuQueue {
    static final Guard guard = new Guard("gpuMaxFrames");
    static volatile int maxFrames;
    static final long WAIT_NS = 50_000_000L;

    private static long[] fences;
    private static long swapNo;
    private static boolean checked;

    private GpuQueue() {}

    static void init() {
        maxFrames = Settings.num("gpuMaxFrames", 2, 0, 8);
        guard.on = Settings.enabled && maxFrames > 0 && !Settings.forcedOff.contains("gpuMaxFrames");   // number keys don't check the master switch themselves; forced off (modCompat=auto) answers the default 2, which is on
    }

    /** The swap whose fence swap k waits for, or -1. */
    static long waitIndex(long k, int n) { return k - n < 0 ? -1 : k - n; }

    static int slot(long k, int n) { return (int) (k % (n + 1)); }

    /** Before the swap. */
    static void beforeSwap() {
        if (!checked) {
            checked = true;
            GLCapabilities c = GL.getCapabilities();
            if (!c.OpenGL32 && !c.GL_ARB_sync) {
                guard.on = false;
                System.out.println("[PZButter] gpuMaxFrames off: no GL sync objects");
                return;
            }
            fences = new long[maxFrames + 1];
        }
        if (fences == null) return;
        long w = waitIndex(swapNo, maxFrames);
        if (w < 0) return;
        int s = slot(w, maxFrames);
        if (fences[s] != 0) {
            GL32.glClientWaitSync(fences[s], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, WAIT_NS);
            GL32.glDeleteSync(fences[s]);
            fences[s] = 0;
        }
    }

    /** After the swap. */
    static void afterSwap() {
        if (fences == null) return;
        int s = slot(swapNo, maxFrames);
        if (fences[s] != 0) GL32.glDeleteSync(fences[s]);
        fences[s] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        swapNo++;
    }
}
