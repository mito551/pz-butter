package pzbutter;

/**
 * Input timing (SPEC 3.8). The render thread polls keyboard and mouse once after each hand-off (first poll wins),
 * and GLFW's key state only changes when events are pumped, after each swap; the game reads that state at the
 * end of its next frame.
 * inputLatch: before the game thread swaps the input caches, it asks the render thread for a fresh pump and
 * re-poll (merged, so presses and wheel steps since the swap are kept) and waits for it, bounded.
 * keyboardFresh: GameKeyboard.update swaps first and reads the new state, as the mouse already does.
 * frameStartGate: a frame starts only once the previous frame is on screen, bounded by one period.
 */
final class InputLatch {
    static final Guard guard = new Guard("input latch");
    static final long WAIT_MAX_NS = 2_000_000L;
    static volatile boolean latch, fresh, gate;

    private static volatile long requested, served;
    private static long requestedForFrame = -1;
    static final Parker waitServed = new Parker();   // the game thread waits here for a served request
    static final Parker waitShown = new Parker();    // the game thread waits here for the previous frame's swap

    private static final Object[] polled = new Object[4];
    private static final long[] pollNs = new long[4];
    private static int next;

    private InputLatch() {}

    static void init() {
        latch = Settings.flag("inputLatch");
        fresh = Settings.flag("keyboardFresh");
        gate = Settings.flag("frameStartGate");
        guard.on = latch || fresh || gate || Settings.enabled && Settings.frameLog;   // frameLog alone: measure the input age
    }

    /** held[i] |= now[i]: a key or button seen down since the swap stays down until the next swap. */
    static void mergeDown(boolean[] held, boolean[] now) {
        if (held == null || now == null) return;
        for (int i = Math.min(held.length, now.length) - 1; i >= 0; i--) held[i] |= now[i];
    }

    /** Wheel steps since the swap add up. */
    static int mergeWheel(int held, int now) { return held + now; }

    /** A re-poll only when asked for and the state already had its first poll since the swap. */
    static boolean shouldRepoll(boolean polledSinceSwap, boolean requested) { return polledSinceSwap && requested; }

    /** Render thread: a state was polled at t. */
    static void notePoll(Object state, long t) {
        int i = find(state);
        if (i < 0) { i = next; next = (next + 1) % polled.length; polled[i] = state; }
        pollNs[i] = t;
    }

    /** The age at frameStart of the input in state; 0 when its poll time is unknown. */
    static long ageAt(Object state, long frameStart) {
        int i = find(state);
        return i < 0 ? 0 : Math.max(0, frameStart - pollNs[i]);
    }

    private static int find(Object s) {
        if (s == null) return -1;
        for (int i = 0; i < polled.length; i++) if (polled[i] == s) return i;
        return -1;
    }

    /** ns to wait at the start of a frame until frame wantFrame is on screen; never past one period after it started. */
    static long gateWaitNs(long now, long lastPresented, long wantFrame, long prevFrameStartNs, long periodNs) {
        if (lastPresented >= wantFrame) return 0;
        return Math.max(0, prevFrameStartNs + periodNs - now);
    }

    /** Game thread: ask for a fresh poll once per frame. True when this call made the request. */
    static boolean request(long frameNo) {
        if (requestedForFrame == frameNo) return false;
        requestedForFrame = frameNo;
        requested++;
        return true;
    }

    /** The longest the game thread waits for a fresh poll: an eighth of a frame, at most 2 ms (0.5 ms uncapped). */
    static long waitBoundNs(long periodNs) { return periodNs <= 0 ? 500_000L : Math.min(WAIT_MAX_NS, periodNs / 8); }

    /** Game thread: wait (at most maxNs) until the render thread has served the request. */
    static void awaitServed(long maxNs) {
        long deadline = System.nanoTime() + maxNs;
        long want = requested;
        while (served < want) {
            long left = deadline - System.nanoTime();
            if (left <= 0) return;
            waitServed.park(left);
        }
    }

    /** Render thread: is a request waiting? */
    static boolean pending() { return served < requested; }

    /** Render thread: the request is done. */
    static void markServed() {
        served = requested;
        waitServed.wake();
    }
}
