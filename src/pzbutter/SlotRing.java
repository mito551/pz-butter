package pzbutter;

/**
 * persistentVbo, the pure part: which storage slot of a buffer may be written next, and the bookkeeping of the frame fences. No GL here;
 * {@link PersistentVbo} makes the calls these decisions ask for. A "swap number" counts the render thread's buffer swaps; a slot is
 * stamped with the swap it was written in, and a frame fence is made after each swap.
 */
final class SlotRing {
    private SlotRing() {}

    static final int FREE = 0, WAIT_FRAME = 1, FENCE_NOW = 2, WAIT_BATCH = 3;
    /** A slot written this many swaps ago is reused without asking the driver (SPEC 3.7: 8 frames). */
    static final int SAFE_SWAPS = 8;
    /** All persistently mapped ring storage together may take this much video memory. */
    static final long BUDGET = 64L << 20;

    /**
     * What to do before writing a slot. {@code written} is the swap the slot was last written in (-1: never), {@code completedSwap}
     * the newest swap whose frame fence has signalled (every older one has too). With {@code frameSync} the slot is free when its
     * swap has completed or is {@link #SAFE_SWAPS} old; the same swap means the frame's fence does not exist yet ({@code FENCE_NOW}).
     * Without it only the slot's own batch fence counts.
     */
    static int decide(long written, long currentSwap, long completedSwap, boolean frameSync, boolean batchSignalled) {
        if (written < 0) return FREE;
        if (frameSync) {
            if (written <= completedSwap || currentSwap - written >= SAFE_SWAPS) return FREE;
            return written == currentSwap ? FENCE_NOW : WAIT_FRAME;
        }
        return batchSignalled ? FREE : WAIT_BATCH;
    }

    /** Slots per buffer: the wish clamped to 1-8, lowered until n slots of every ring fit the budget (never below one). */
    static int slots(int want, long bytesPerSlotAllBuffers, long budgetBytes) {
        int n = Math.max(1, Math.min(8, want));
        while (n > 1 && n * bytesPerSlotAllBuffers > budgetBytes) n--;
        return n;
    }

    /** The frame fences' handles by swap number, 16 entries; the caller makes and deletes the GL objects. */
    static final class FenceRing {
        final long[] fence = new long[16];
        final long[] swapOf = new long[16];
        /** The newest swap whose fence has signalled; every older swap has completed too. */
        long completed = -1;
        private long latest = -1;

        FenceRing() { java.util.Arrays.fill(swapOf, -1L); }

        /** Stores the swap's fence; returns the handle it replaced (a swap 16 older), or 0. The caller deletes it. */
        long put(long swap, long handle) {
            int i = (int) (swap & 15);
            long old = swapOf[i] >= 0 ? fence[i] : 0L;
            fence[i] = handle;
            swapOf[i] = swap;
            if (swap > latest) latest = swap;
            return old;
        }

        /** The handle of this swap's fence, or 0 when it is gone (signalled, or replaced). */
        long handleFor(long swap) {
            if (swap < 0) return 0L;
            int i = (int) (swap & 15);
            return swapOf[i] == swap ? fence[i] : 0L;
        }

        /** The swaps after {@code completed} that still have a fence, oldest first; returns how many were written to out. */
        int pollOrder(long[] out) {
            int n = 0;
            for (long s = Math.max(completed + 1, latest - 15); s <= latest && n < out.length; s++)
                if (swapOf[(int) (s & 15)] == s) out[n++] = s;
            return n;
        }

        /** That swap's fence has signalled: it and every older swap have completed. The entry is cleared (the caller deletes the handle). */
        void signalled(long swap) {
            if (swap > completed) completed = swap;
            int i = (int) (swap & 15);
            if (swapOf[i] == swap) { swapOf[i] = -1L; fence[i] = 0L; }
        }
    }

    /** One buffer object's slots: the swap each was written in, the fence of each one's last batch (0 none), the slot used last. */
    static final class Buf {
        final int slots;
        final long[] written;
        final long[] batchFence;
        int cur = -1;

        Buf(int slots) {
            this.slots = slots;
            written = new long[slots];
            java.util.Arrays.fill(written, -1L);
            batchFence = new long[slots];
        }
    }

    /** The slot to write next: round robin. */
    static int next(Buf b) { return (b.cur + 1) % b.slots; }
}
