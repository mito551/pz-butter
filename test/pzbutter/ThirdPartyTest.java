package pzbutter;

/** Checks for the KI5 part-mod gate (Plan 17). Run with -ea. */
public final class ThirdPartyTest {
    private ThirdPartyTest() {}

    public static void run() {
        gateWindow();
        gateOff();
        flushOnEndEvery();
        activeReadsList();
        onFollowsTheFixMod();
        ThirdParty.resetForTest();
    }

    /** Plan 17 Task 0: a fix mod is on when it is in the game's activated-mods list, read once per Lua load. */
    static void activeReadsList() {
        ThirdParty.resetForTest();
        int[] reads = {0};
        ThirdParty.modIds = () -> { reads[0]++; return java.util.List.of("PZButter", "\\PZButter_KI5"); };
        assert ThirdParty.active("PZButter_KI5") : "listed mod is active (a leading backslash is ignored)";
        assert !ThirdParty.active("PZButter_DamnLib") : "unlisted mod is not active";
        assert reads[0] == 1 : "the list is read once, then cached";
        ThirdParty.resetActive();
        ThirdParty.modIds = () -> java.util.List.of();
        assert !ThirdParty.active("PZButter_KI5") : "a new Lua load reads the list again";
        ThirdParty.modIds = () -> { throw new IllegalStateException("no file system yet"); };
        ThirdParty.resetActive();
        assert !ThirdParty.active("PZButter_KI5") : "an unreadable list means not active";
    }

    /** The throttle is on while PZButter_KI5 is enabled, off when it is not or when modCompat=auto forced it off. */
    static void onFollowsTheFixMod() {
        ThirdParty.resetForTest();
        Side.forTest(false, false);
        ThirdParty.modIds = () -> java.util.List.of("PZButter", "PZButter_KI5");
        assert ThirdParty.on() : "on while the fix mod is enabled";
        Settings.forcedOff.add("mod:PZButter_KI5");
        assert !ThirdParty.on() : "modCompat=auto switched it off";
        Settings.forcedOff.remove("mod:PZButter_KI5");
        ThirdParty.resetActive();
        ThirdParty.modIds = () -> java.util.List.of("PZButter");
        assert !ThirdParty.on() : "off while the fix mod is not enabled";
        Side.forTest(true, false);
        ThirdParty.resetActive();
        ThirdParty.modIds = () -> java.util.List.of("PZButter", "PZButter_KI5");
        assert !ThirdParty.on() : "off on a server";
        Side.resetForTest();
    }

    static void gateWindow() {
        ThirdParty.resetForTest();
        ThirdParty.testOn = true;
        ThirdParty.beginEvery();
        assert ThirdParty.partModGate(1, 2, 1_000) : "first call passes";
        assert !ThirdParty.partModGate(1, 2, 1_050) : "same part inside 100 ms is held";
        assert ThirdParty.partModGate(1, 2, 1_100) : "next window passes";
        assert ThirdParty.partModGate(1, 3, 1_050) : "another part has its own window";
        assert ThirdParty.partModGate(2, 2, 1_050) : "another vehicle has its own window";
        ThirdParty.endEvery();
    }

    static void gateOff() {
        ThirdParty.resetForTest();
        ThirdParty.testOn = false;
        ThirdParty.beginEvery();
        assert ThirdParty.partModGate(7, 1, 1_000) : "off always passes";
        assert ThirdParty.partModGate(7, 1, 1_001) : "off does not hold";
        ThirdParty.endEvery();

        ThirdParty.resetForTest();
        ThirdParty.testOn = true;
        assert ThirdParty.partModGate(8, 1, 1_000) : "no every handler means pass";
        assert ThirdParty.partModGate(8, 1, 1_001) : "no every handler never holds";
    }

    static void flushOnEndEvery() {
        ThirdParty.resetForTest();
        ThirdParty.testOn = true;
        ThirdParty.beginEvery();
        assert ThirdParty.partModGate(9, 9, 2_000) : "first call passes";
        assert !ThirdParty.partModGate(9, 9, 2_050) : "held once";
        ThirdParty.notePart(9, 9, null, null);
        assert ThirdParty.pendingForTest() == 1 : "held state remembered";
        ThirdParty.endEvery();
        assert ThirdParty.pendingForTest() == 0 : "endEvery flushes the held state";
    }
}
