package pzbutter;

import java.util.IdentityHashMap;

/**
 * World-load keys (Plan 11a). {@code storyKidsRoomMemo} memoises {@code RoomDef.isKidsRoom()} per room for one building's
 * story pass; the rest of the family lives alongside (Task 2-3).
 */
public final class LoadFixes {
    private LoadFixes() {}

    /** storyKidsRoomMemo feature guard. */
    public static final Guard kidsGuard = new Guard("storyKidsRoomMemo");

    /** Identity-keyed memo: a room is the key, its computed kids-room answer is the value. */
    private static final IdentityHashMap<Object, Boolean> kidsMemo = new IdentityHashMap<>();

    /** The thread that first touched the memo (the story pass's thread). Other threads pass through. */
    private static volatile Thread kidsOwner;

    public static void init() {
        kidsGuard.on = Settings.flag("storyKidsRoomMemo");
    }

    /** Reset for unit tests (called by LoadTest). */
    public static void resetForTest() {
        kidsGuard.on = false;
        kidsMemo.clear();
        kidsOwner = null;
    }

    /** True when the current thread is the memo's owner (or becomes it on the first call). */
    private static boolean ownerThread() {
        Thread t = Thread.currentThread();
        Thread o = kidsOwner;
        if (o == null) {
            synchronized (LoadFixes.class) {
                o = kidsOwner;
                if (o == null) { kidsOwner = o = t; }
            }
        }
        return t == o;
    }

    /** A cached answer for {@code room}, or null when uncached, off, or on another thread. */
    public static Boolean kidsCached(Object room) {
        if (!kidsGuard.on) return null;
        if (!ownerThread()) return null;
        synchronized (kidsMemo) { return kidsMemo.get(room); }
    }

    public static void kidsStore(Object room, boolean v) {
        if (!kidsGuard.on) return;
        if (!ownerThread()) return;
        synchronized (kidsMemo) { kidsMemo.put(room, v); }
    }

    public static void kidsClear() {
        if (!kidsGuard.on) return;
        if (!ownerThread()) return;
        synchronized (kidsMemo) { kidsMemo.clear(); }
    }
}
