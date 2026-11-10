package pzbutter;

/** windowPump: a daemon pumps the OS message queue while the game thread holds a frame during the join (Windows only). */
final class WindowPumpTest {
    private WindowPumpTest() {}

    static void run() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        WindowPump.init();
        check("windowPump defaults off", !WindowPump.guard.on);
        check("no pump thread on defaults", WindowPump.pump == null);

        Settings.load(SelfTest.write("enabled=true\nwindowPump=true\n"));
        WindowPump.init();
        check("template enables the guard", WindowPump.guard.on);
        check("pump thread still null on Linux", WindowPump.pump == null);

        check("cycle with no frames is idle", WindowPump.cycle(1000L) == WindowPump.Cycle.IDLE);
        WindowPump.mark();
        check("mark records the last frame", WindowPump.lastFrame() > 0);
        long last = WindowPump.lastFrame();
        check("a fresh frame clears the hold", WindowPump.cycle(last + 299L) == WindowPump.Cycle.IDLE && WindowPump.holdStartedAt() == 0);
        check("a stale frame holds", WindowPump.cycle(last + 20000L) == WindowPump.Cycle.HOLD && WindowPump.holdStartedAt() > 0);
        long hold = WindowPump.holdStartedAt();
        check("six minutes ends the daemon", WindowPump.cycle(hold + 6L * 60_000L) == WindowPump.Cycle.EXIT);

        WindowPump.lastFrameMs = last + 40000L;
        check("a fresh frame clears the stale hold", WindowPump.cycle(last + 40000L + 100L) == WindowPump.Cycle.IDLE && WindowPump.holdStartedAt() == 0);

        zombie.GameWindow.closeRequested = true;
        WindowPump.closeReset();
        check("close request reset while holding", !zombie.GameWindow.closeRequested);

        Settings.load(SelfTest.write("enabled=true\n"));
        WindowPump.init();
        check("defaults restored", !WindowPump.guard.on);
    }

    static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }
}
