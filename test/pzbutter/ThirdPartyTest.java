package pzbutter;

/** Checks for the KI5 part-mod gate (Plan 19). Run with -ea. */
public final class ThirdPartyTest {
    private ThirdPartyTest() {}

    public static void run() {
        gateWindow();
        gateOff();
        flushOnEndEvery();
        ThirdParty.resetForTest();
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
