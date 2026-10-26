package pzbutter;

import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.util.CappedConcurrentQueue;

public final class ChunkPacer {
    private ChunkPacer() {}

    static final Guard guard = new Guard("chunkPacing");

    public static volatile boolean inUpdate;
    public static volatile Thread enterThread;
    public static volatile Object queueRef;

    public static volatile int divisor;
    public static volatile long budgetNs;

    public static volatile long lastFrameNo = -1;
    public static volatile int finishedThisFrame;
    public static volatile boolean heldThisFrame;
    public static volatile long meanJoinNs;

    // stats
    public static volatile long joins;
    public static volatile long framesHeld;

    private static volatile long joinStartNs;

    static void init() {
        try {
            divisor = Settings.num("chunkHandoffDivisor", 0, 0, 100);
            budgetNs = (long) Settings.num("chunkHandoffFrameBudgetUs", 0, 0, 100000) * 1000L;
            // chunkHandoffSlackMaxWait not built: the first join of each frame already guarantees progress.
            guard.on = divisor > 0 || budgetNs > 0;
        } catch (Throwable t) {
            guard.fail(t);
            guard.on = false;
        }
    }

    /** Pure decision. */
    public static boolean hold(int finishedThisFrame, int queued, int gridWidth, long sinceFrameStartNs, long meanJoinNs, int divisor, long budgetNs) {
        if (finishedThisFrame < 1) return false;
        if (queued > Math.max(gridWidth, 16)) return false;
        boolean divisorHolds = divisor > 0 && finishedThisFrame >= 1 + queued / divisor;
        boolean budgetHolds = budgetNs > 0 && sinceFrameStartNs <= 1_000_000_000L && sinceFrameStartNs + meanJoinNs > budgetNs;
        return divisorHolds || budgetHolds;
    }

    public static void enter() {
        try {
            inUpdate = true;
            enterThread = Thread.currentThread();
            if (queueRef == null) queueRef = IsoChunk.loadGridSquare;
            long fn = FrameClock.frameNo;
            if (fn != lastFrameNo) {
                lastFrameNo = fn;
                finishedThisFrame = 0;
                heldThisFrame = false;
            }
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void exit() {
        try {
            inUpdate = false;
            enterThread = null;
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void joinStart() {
        try {
            if (!inUpdate) return;
            if (Thread.currentThread() != enterThread) return;
            joinStartNs = System.nanoTime();
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void joinEnd() {
        try {
            if (!inUpdate) return;
            if (Thread.currentThread() != enterThread) return;
            long s = joinStartNs;
            if (s != 0) {
                joinStartNs = 0;
                long dur = System.nanoTime() - s;
                if (meanJoinNs == 0) meanJoinNs = dur;
                else meanJoinNs = (meanJoinNs * 15 + dur) / 16;
                finishedThisFrame++;
                joins++;
            }
        } catch (Throwable t) { guard.fail(t); }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean onPoll(Object queue) {
        if (!guard.on) return false;
        if (!inUpdate) return false;
        if (Thread.currentThread() != enterThread) return false;
        if (queue != queueRef) return false;
        if (FrameClock.frameStartNs == 0) return false;
        try {
            int queued = 0;
            if (queue instanceof CappedConcurrentQueue) {
                queued = ((CappedConcurrentQueue) queue).size();
            }
            int gw = IsoChunkMap.chunkGridWidth;
            long since = System.nanoTime() - FrameClock.frameStartNs;
            boolean h = hold(finishedThisFrame, queued, gw, since, meanJoinNs, divisor, budgetNs);
            if (h) {
                if (!heldThisFrame) {
                    heldThisFrame = true;
                    framesHeld++;
                }
                return true;
            }
            return false;
        } catch (Throwable t) {
            guard.fail(t);
            return false;
        }
    }

    public static String drain() {
        long j = joins, m = meanJoinNs, fh = framesHeld;
        joins = 0; framesHeld = 0;
        return String.format("[PZButter] chunk pacing: %d joins (mean %.2f ms), %d frames held", j, m / 1e6, fh);
    }

    static void resetForTest() {
        inUpdate = false;
        enterThread = null;
        queueRef = null;
        lastFrameNo = -1;
        finishedThisFrame = 0;
        heldThisFrame = false;
        meanJoinNs = 0;
        joins = 0;
        framesHeld = 0;
        joinStartNs = 0;
        divisor = 0;
        budgetNs = 0;
        guard.on = false;
    }
}
