package pzbutter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Carries each game frame's number, start time and input age from the game thread (when it hands a render state
 * over in SpriteRenderer.pushFrameDown) to the render thread (when it acquires that state). States are matched by
 * identity in an open-addressed table; each slot is a seqlock, so the render thread never reads a half-written
 * record. Nothing is allocated per frame. (Low-probability variant b of 2026-10-20.)
 */
final class FrameStamps {
    static final Guard guard = new Guard("frame stamps");

    static long pendingInputAgeNs;   // game thread: the input age of the frame being built
    static long renderFrameNo = -1, renderFrameStartNs, renderInputAgeNs;   // render thread: the frame being drawn

    private static final int SLOTS = 32, MASK = SLOTS - 1;   // power of two, holds the 8 states with room to probe
    private static final Object[] keys = new Object[SLOTS];
    private static final long[] data = new long[SLOTS * 4];  // per slot: seq, frame, start, age
    private static final VarHandle K = MethodHandles.arrayElementVarHandle(Object[].class);
    private static final VarHandle D = MethodHandles.arrayElementVarHandle(long[].class);

    private FrameStamps() {}

    /** Game thread only. */
    static void onPush(Object s) {
        try {
            if (s == null) return;
            int i = System.identityHashCode(s) & MASK;
            for (int n = 0; n < SLOTS; n++, i = (i + 1) & MASK) {
                Object k = (Object) K.getOpaque(keys, i);
                if (k == s) break;
                if (k == null) { K.setOpaque(keys, i, s); break; }
                if (n == SLOTS - 1) return;   // full: drop, never throw
            }
            int b = i * 4;
            long q = (long) D.getOpaque(data, b);
            D.setOpaque(data, b, q + 1);           // odd: write in progress
            VarHandle.releaseFence();
            D.setOpaque(data, b + 1, FrameClock.frameNo + 1);
            D.setOpaque(data, b + 2, FrameClock.frameStartNs);
            D.setOpaque(data, b + 3, pendingInputAgeNs);
            VarHandle.releaseFence();
            D.setOpaque(data, b, q + 2);           // even: stable
        } catch (Throwable t) { /* never throw */ }
    }

    /** Render thread only. */
    static void onAcquire(Object s) {
        long f = -1, st = 0, a = 0;
        try {
            if (s != null) {
                int i = System.identityHashCode(s) & MASK;
                for (int n = 0; n < SLOTS; n++, i = (i + 1) & MASK) {
                    Object k = (Object) K.getOpaque(keys, i);
                    if (k == null) break;
                    if (k != s) continue;
                    int b = i * 4;
                    for (int tries = 0; tries < 10_000; tries++) {   // bounded: a writer that died mid-write must not hang the render thread
                        long q1 = (long) D.getOpaque(data, b);
                        VarHandle.acquireFence();
                        long f1 = (long) D.getOpaque(data, b + 1), s1 = (long) D.getOpaque(data, b + 2), a1 = (long) D.getOpaque(data, b + 3);
                        VarHandle.acquireFence();
                        if ((q1 & 1) == 0 && q1 == (long) D.getOpaque(data, b)) {
                            if (q1 != 0) { f = f1; st = s1; a = a1; }   // q1 == 0: key seen, never stamped
                            break;
                        }
                        Thread.onSpinWait();
                    }
                    break;
                }
            }
        } catch (Throwable t) { f = -1; st = 0; a = 0; }
        renderFrameNo = f; renderFrameStartNs = st; renderInputAgeNs = a;
    }
}
