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

    static final class Snapshot {
        Object draw;
        int x, y;
    }
    // ponytail: one render-thread snapshot avoids allocation; use fresh objects if snapshots ever need retaining.
    private static final Snapshot FOUND = new Snapshot();

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

    /** Render thread: a consistent snapshot, or null. Consume it before the next find; the object is reused. */
    static Snapshot find(long frameNo) {
        if (frameNo < 0) return null;
        int s0 = seq;
        for (int i = 0; i < N; i++) {
            if (frame[i] == frameNo) {
                Object d = draw[i];
                int x = rx[i], y = ry[i];
                if (seq != s0) return null;
                FOUND.draw = d;
                FOUND.x = x;
                FOUND.y = y;
                return FOUND;
            }
        }
        return null;
    }
}
