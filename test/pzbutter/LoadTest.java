package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/** Plan 11a checks (world load: storyKidsRoomMemo). Run from SelfTest.main. */
final class LoadTest {
    private LoadTest() {}

    static void run() throws Exception {
        try { runAll(); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new Exception(t); }
    }

    static void runAll() throws Throwable {
        kidsMemoHitsSecondCall();
        kidsMemoClearedPerBuilding();
        kidsMemoOtherThreadPassesThrough();
        kidsMemoOffStock();
        kidsMemoInactiveOutsidePass();
        kidsMemoClearedOnThrow();
    }

    // ---- storyKidsRoomMemo ----

    static void kidsMemoHitsSecondCall() {
        LoadFixes.resetForTest();
        LoadFixes.kidsGuard.on = true;
        LoadFixes.kidsEnter();
        Object room = new Object();
        assert LoadFixes.kidsCached(room) == null;
        LoadFixes.kidsStore(room, true);
        assert Boolean.TRUE.equals(LoadFixes.kidsCached(room));
        assert Boolean.TRUE.equals(LoadFixes.kidsCached(room)); // second call cached
        LoadFixes.kidsExit();
        LoadFixes.resetForTest();
    }

    static void kidsMemoClearedPerBuilding() {
        LoadFixes.resetForTest();
        LoadFixes.kidsGuard.on = true;
        LoadFixes.kidsEnter();
        Object room = new Object();
        LoadFixes.kidsStore(room, true);
        assert Boolean.TRUE.equals(LoadFixes.kidsCached(room));
        LoadFixes.kidsClear();
        assert LoadFixes.kidsCached(room) == null;
        LoadFixes.kidsExit();
        LoadFixes.resetForTest();
    }

    static void kidsMemoOtherThreadPassesThrough() throws InterruptedException {
        LoadFixes.resetForTest();
        LoadFixes.kidsGuard.on = true;
        LoadFixes.kidsEnter();
        Object room = new Object();
        // first caller is this (test) thread: it owns the memo
        LoadFixes.kidsStore(room, false);
        assert Boolean.FALSE.equals(LoadFixes.kidsCached(room));
        Thread t = new Thread(() -> LoadFixes.kidsStore(room, true));
        t.start(); t.join(5000);
        assert !t.isAlive();
        assert Boolean.FALSE.equals(LoadFixes.kidsCached(room)); // other thread did not store
        // cached read from another thread passes through (null)
        boolean[] got = { false };
        Thread t2 = new Thread(() -> got[0] = (LoadFixes.kidsCached(room) == null));
        t2.start(); t2.join(5000);
        assert !t2.isAlive() && got[0];
        LoadFixes.kidsExit();
        LoadFixes.resetForTest();
    }

    static void kidsMemoOffStock() {
        LoadFixes.resetForTest();
        LoadFixes.kidsGuard.on = false;
        Object room = new Object();
        LoadFixes.kidsStore(room, true);
        assert LoadFixes.kidsCached(room) == null;
        LoadFixes.kidsClear();
        assert Settings.render(new java.util.Properties()).contains("\nstoryKidsRoomMemo=false\n");
        LoadFixes.resetForTest();
    }

    static void kidsMemoInactiveOutsidePass() {
        LoadFixes.resetForTest();
        LoadFixes.kidsGuard.on = true;
        Object room = new Object();
        // outside an active pass: store is ignored and cache misses
        LoadFixes.kidsStore(room, true);
        assert LoadFixes.kidsCached(room) == null;
        LoadFixes.kidsClear(); // no-op outside a pass
        assert LoadFixes.kidsCached(room) == null;
        LoadFixes.resetForTest();
    }

    static void kidsMemoClearedOnThrow() {
        try {
            Patch.OnExit a = LoadPatches.KidsRoomClear.class.getMethod("exit").getAnnotation(Patch.OnExit.class);
            assert a.onThrowable() == Throwable.class;
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
