package pzbutter;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLCapabilities;

/**
 * frameQueueGpu (SPEC 3.12): GPU timestamps around SpriteRenderer.postRender give each frame's GPU drawing time,
 * which the swap hold adds to a frame's readiness. A ring of RING query pairs is read two frames late, so the
 * render thread never waits for the GPU. Render thread only (the GL context is current there).
 */
final class GpuTimer {
    static final Guard guard = new Guard("frameQueueGpu");
    static final int RING = 4;
    static volatile long lastGpuNs = -1;   // -1: not measured (unavailable or not yet)

    private static int[] ids;
    private static long frame;
    private static boolean checked;

    private GpuTimer() {}

    static void init() { guard.on = Settings.flag("frameQueueGpu") && SwapHold.guard.on; }

    static int slot(long f) { return (int) (f % RING); }

    /** The slot of the frame two back, or -1 before there is one. */
    static int readSlot(long f) { return f < 2 ? -1 : slot(f - 2); }

    static boolean available(boolean arbTimerQuery, boolean gl33) { return arbTimerQuery || gl33; }

    static void begin() {
        if (!checked) {
            checked = true;
            GLCapabilities c = GL.getCapabilities();
            if (!available(c.GL_ARB_timer_query, c.OpenGL33)) {
                guard.on = false;
                System.out.println("[PZButter] frameQueueGpu off: no GL timer queries");
                return;
            }
            ids = new int[2 * RING];
            GL15.glGenQueries(ids);
        }
        if (ids == null) return;
        ARBTimerQuery.glQueryCounter(ids[2 * slot(frame)], ARBTimerQuery.GL_TIMESTAMP);
    }

    static void end() {
        if (ids == null) return;
        ARBTimerQuery.glQueryCounter(ids[2 * slot(frame) + 1], ARBTimerQuery.GL_TIMESTAMP);
        int r = readSlot(frame);
        if (r >= 0 && GL15.glGetQueryObjecti(ids[2 * r + 1], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long t0 = ARBTimerQuery.glGetQueryObjectui64(ids[2 * r], GL15.GL_QUERY_RESULT);
            long t1 = ARBTimerQuery.glGetQueryObjectui64(ids[2 * r + 1], GL15.GL_QUERY_RESULT);
            if (t1 >= t0) lastGpuNs = t1 - t0;
        }
        frame++;
    }
}
