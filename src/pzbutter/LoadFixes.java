package pzbutter;

import java.util.IdentityHashMap;

/**
 * World-load keys (Plan 11a). {@code storyKidsRoomMemo} memoises {@code RoomDef.isKidsRoom()} per room, but only while a
 * building's story pass is active on one thread.
 */
public final class LoadFixes {
    private LoadFixes() {}

    /** storyKidsRoomMemo feature guard. */
    public static final Guard kidsGuard = new Guard("storyKidsRoomMemo");

    /** Identity-keyed memo: a room is the key, its computed kids-room answer is the value. */
    private static final IdentityHashMap<Object, Boolean> kidsMemo = new IdentityHashMap<>();

    /** True only between a {@code RandomizedBuildingBase.randomizeBuilding} entry and exit on the owning thread. */
    private static volatile boolean kidsPassActive;

    /** The thread running the active story pass. */
    private static volatile Thread kidsOwner;

    public static void init() {
        kidsGuard.on = Settings.flag("storyKidsRoomMemo");
    }

    /** Reset for unit tests (called by LoadTest). */
    public static void resetForTest() {
        kidsGuard.on = false;
        kidsMemo.clear();
        kidsPassActive = false;
        kidsOwner = null;
    }

    /** Begin a building's story pass on the current thread: sets the owner/active flag and clears the memo. */
    public static void kidsPassEnter() { kidsBegin(); }
    public static void kidsBegin() { kidsBeginPass(); }
    public static void kidsBeginPass() { kidsEnterPass(); }
    public static void kidsEnterPass() { kidsEnter(); }
    public static void kidsEnter() {
        kidsPassActive = true;
        kidsOwner = Thread.currentThread();
        synchronized (kidsMemo) { kidsMemo.clear(); }
    }
    public static void enterPass() { kidsEnter(); }

    /** End a building's story pass: clears the active flag, owner, and memo. */
    public static void kidsPassExit() { kidsEnd(); }
    public static void kidsEnd() { kidsEndPass(); }
    public static void kidsEndPass() { kidsExitPass(); }
    public static void kidsExitPass() { kidsExit(); }
    public static void kidsExit() { kidsPassExit0(); }
    private static void kidsPassExit0() {
        kidsPassActive = false;
        kidsOwner = null;
        synchronized (kidsMemo) { kidsMemo.clear(); }
    }
    public static void exitPass() { kidsPassExit0(); }

    /** True when the current thread owns an active story pass. */
    private static boolean ownerThread() {
        return kidsPassActive && Thread.currentThread() == kidsOwner;
    }

    /** A cached answer for {@code room}, or null when uncached, off, or outside an active pass. */
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
