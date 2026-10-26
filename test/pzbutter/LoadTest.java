package pzbutter;

/** Plan 11a: world-load keys. */
final class LoadTest {
    private LoadTest() {}

    static void run() {
        idChecksSkippedOnlyOutsideDebug();
        idChecksOffStock();
        loadDefaultsWritten();
    }

    static void idChecksSkippedOnlyOutsideDebug() {
        assert LoadFixes.skipIdCheck(false);
        assert !LoadFixes.skipIdCheck(true);
    }

    /** Off: the shim returns before touching any game class, and the walk runs. */
    static void idChecksOffStock() {
        LoadFixes.idGuard.on = false;
        assert !LoadPatches.onIdCheck();
    }

    static void loadDefaultsWritten() {
        assert Settings.render(new java.util.Properties()).contains("\nskipIdChecks=false\n");
    }
}
