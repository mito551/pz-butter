package pzbutter;

/**
 * Carries each game frame's number, start time and input age from the game thread (when it hands a render
 * state over in SpriteRenderer.pushFrameDown) to the render thread (when it acquires that state). States are
 * matched by identity in a small ring; nothing is allocated per frame.
 */
final class FrameStamps {
    static final Guard guard = new Guard("frame stamps");
    static final int SLOTS = 8;   // the game has a handful of render states

    private static final Object[] state = new Object[SLOTS];
    private static final long[] frame = new long[SLOTS], start = new long[SLOTS], age = new long[SLOTS];
    private static int next;
    private static volatile int seq;   // written last by the game thread, read first by the render thread

    static long pendingInputAgeNs;   // game thread: the input age of the frame being built
    static long renderFrameNo = -1, renderFrameStartNs, renderInputAgeNs;   // render thread: the frame being drawn

    private FrameStamps() {}

    /** Game thread, before the populating state is handed over. The frame being built is frameNo + 1. */
    static void onPush(Object s) {
        int i = find(s);
        if (i < 0) { i = next; next = (next + 1) % SLOTS; state[i] = s; }
        frame[i] = FrameClock.frameNo + 1;
        start[i] = FrameClock.frameStartNs;
        age[i] = pendingInputAgeNs;
        seq++;
    }

    /** Render thread, with the state it acquired (null or unknown: no stamps). */
    static void onAcquire(Object s) {
        int i = seq >= 0 ? find(s) : -1;
        if (i < 0) { renderFrameNo = -1; renderFrameStartNs = 0; renderInputAgeNs = 0; return; }
        renderFrameNo = frame[i];
        renderFrameStartNs = start[i];
        renderInputAgeNs = age[i];
    }

    private static int find(Object s) {
        if (s == null) return -1;
        for (int i = 0; i < SLOTS; i++) if (state[i] == s) return i;
        return -1;
    }
}
