package pzbutter;

/**
 * cursorLatch (SPEC 3.8): the game records the cursor quad with the pointer position of its frame; the render
 * thread moves that quad to the pointer's newest position just before it draws the frame. This keeps, per
 * frame number, the cursor's TextureDraw and the position it was recorded at.
 */
final class CursorLatch {
    static final Guard guard = new Guard("cursorLatch");
    private static final int N = 8;
    private static final long[] frame = {-1, -1, -1, -1, -1, -1, -1, -1};
    private static final Object[] draw = new Object[N];
    private static final int[] rx = new int[N], ry = new int[N];
    private static int next;
    private static volatile int seq;   // written last by the game thread, read first by the render thread

    private CursorLatch() {}

    static void init() {
        guard.on = Settings.flag("cursorLatch");
        if (guard.on) FrameStamps.guard.on = true;
    }

    /** Game thread: frameNo's cursor is the TextureDraw d, drawn at (x, y). */
    static void record(long frameNo, Object d, int x, int y) {
        int i = next;
        next = (next + 1) % N;
        frame[i] = frameNo;
        draw[i] = d;
        rx[i] = x;
        ry[i] = y;
        seq++;
    }

    /** Render thread: the slot of frameNo's cursor, or -1. */
    static int find(long frameNo) {
        if (frameNo < 0 || seq < 0) return -1;
        for (int i = 0; i < N; i++) if (frame[i] == frameNo) return i;
        return -1;
    }

    static Object drawAt(int i) { return draw[i]; }

    static int xAt(int i) { return rx[i]; }

    static int yAt(int i) { return ry[i]; }
}
