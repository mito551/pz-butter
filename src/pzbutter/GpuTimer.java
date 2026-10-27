package pzbutter;

/**
 * bakeBudgetGpu: the GPU's time for a frame, from two timestamp queries (render frame start, just before the swap). A ring of four
 * query pairs; frame k's pair is read at frame k+3 and only when the driver says both results are available, otherwise that sample is
 * skipped. Nothing here waits for the GPU. {@link #lastGpuMs} is -1 until the first sample. Render thread only (lastGpuMs is volatile for
 * the game thread's bake planning).
 */
final class GpuTimer {
    private GpuTimer() {}

    static final Guard guard = new Guard("bakeBudgetGpu");

    /** True when the bake budget itself reads lastGpuMs (bakeBudgetGpu + bakeBudgetAdaptive). */
    static volatile boolean bakeMode = false;

    /** The last complete sample in ms, -1 before the first. */
    static volatile double lastGpuMs = -1;

    static final int RING = 4, LAG = 3;

    /** GL timestamp queries; the game uses LWJGL, tests a fake. */
    interface Queries {
        boolean capable();
        void stamp(int id);
        boolean available(int id);
        long value(int id);
        int[] gen(int n);
    }

    static final class LwjglQueries implements Queries {
        public boolean capable() {
            org.lwjgl.opengl.GLCapabilities c = org.lwjgl.opengl.GL.getCapabilities();
            return c.OpenGL33 || c.GL_ARB_timer_query;
        }
        public void stamp(int id) { org.lwjgl.opengl.GL33.glQueryCounter(id, 0x8E28); }
        public boolean available(int id) { return org.lwjgl.opengl.GL15.glGetQueryObjecti(id, 0x8867) != 0; }
        public long value(int id) { return org.lwjgl.opengl.GL33.glGetQueryObjectui64(id, 0x8866); }
        public int[] gen(int n) {
            java.nio.IntBuffer b = java.nio.ByteBuffer.allocateDirect(n * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            org.lwjgl.opengl.GL15.glGenQueries(b);
            int[] a = new int[n];
            b.get(a);
            return a;
        }
    }

    static Queries q = new LwjglQueries();

    private static int[] ids;                                  // 2 per slot: begin, end
    private static final long[] slotFrame = {-1, -1, -1, -1};  // the frame whose stamps a slot holds
    private static final boolean[] begun = new boolean[RING], ended = new boolean[RING];
    private static long frameNo;
    private static boolean inFrame;

    /** Samples read, and samples skipped because the driver had not finished them (or the pair was incomplete). */
    static long samples, skipped;
    /** Sum of the samples in ms (gpuStats prints the minute's mean). */
    static double sumMs;

    static void init() {
        bakeMode = Settings.flag("bakeBudgetGpu") && Settings.flag("bakeBudgetAdaptive");
        guard.on = bakeMode || Settings.flag("gpuPstate");
        ids = null;
        frameNo = 0;
        inFrame = false;
        lastGpuMs = -1;
        java.util.Arrays.fill(slotFrame, -1);
        java.util.Arrays.fill(begun, false);
        java.util.Arrays.fill(ended, false);
        if (guard.on) System.out.println("[PZButter] bakeBudgetGpu on: GPU frame time from timestamp queries feeds the adaptive bake budget");
    }

    static int slot(long frame) { return (int) (frame & 3); }

    /** The frame whose pair is read at {@code frame}; below 0 there is nothing to read yet. */
    static long readable(long frame) { return frame - LAG; }

    /** Render frame start: reads the pair of the frame three back when it is ready, then stamps this frame's begin. */
    static void frameBegin() {
        if (inFrame) { inFrame = false; frameNo++; }          // a frame whose end never came (its pair stays incomplete and is skipped)
        if (ids == null) {
            if (!q.capable()) {
                guard.on = false;
                System.out.println("[PZButter] bakeBudgetGpu: this driver has no timestamp queries (OpenGL 3.3 or GL_ARB_timer_query), key off");
                return;
            }
            ids = q.gen(RING * 2);
        }
        long old = readable(frameNo);
        if (old >= 0) {
            int s = slot(old);
            if (slotFrame[s] == old && begun[s] && ended[s]) {
                if (q.available(ids[2 * s]) && q.available(ids[2 * s + 1])) {
                    long b = q.value(ids[2 * s]), e = q.value(ids[2 * s + 1]);
                    if (e >= b) { double ms = (e - b) / 1e6; lastGpuMs = ms; sumMs += ms; samples++; }
                    else skipped++;
                } else skipped++;
            } else skipped++;
            begun[s] = false;
            ended[s] = false;
        }
        int s = slot(frameNo);
        q.stamp(ids[2 * s]);
        slotFrame[s] = frameNo;
        begun[s] = true;
        ended[s] = false;
        inFrame = true;
    }

    /** Just before the swap: stamps the end of the frame begun by frameBegin; the next frame starts. */
    static void frameEnd() {
        if (!inFrame) return;
        int s = slot(frameNo);
        q.stamp(ids[2 * s + 1]);
        ended[s] = true;
        inFrame = false;
        frameNo++;
    }

    /** The load the bake budget reacts to: the larger of the game thread's step and the GPU's frame time, once there is one. */
    static double loadMs(double stepMs) {
        double g = lastGpuMs;
        return (bakeMode && guard.on && g >= 0) ? Math.max(stepMs, g) : stepMs;
    }

    static void resetForTest() {
        resetForTestKeepQueries();
        q = new LwjglQueries();
    }

    static void resetForTestKeepQueries() {
        guard.on = false;
        bakeMode = false;
        ids = null;
        frameNo = 0;
        inFrame = false;
        lastGpuMs = -1;
        samples = skipped = 0;
        sumMs = 0;
        java.util.Arrays.fill(slotFrame, -1);
        java.util.Arrays.fill(begun, false);
        java.util.Arrays.fill(ended, false);
    }
}
