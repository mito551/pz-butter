package pzbutter;

/** Plan 10a checks (streaming and vehicles). Run from SelfTest.main. */
final class StreamTest {
    private StreamTest() {}

    static void run() throws Exception {
        try { runAll(); }
        catch (Exception | Error e) { throw e; }
        catch (Throwable t) { throw new Exception(t); }
    }

    static void runAll() throws Throwable {
        gloveBoxSkipsOnlyNullContainer();
        gloveBoxOffIsStock();
        streamDefaultsWritten();
    }

    static void gloveBoxSkipsOnlyNullContainer() {
        assert VehicleFixes.skipGloveBox(true, true);
        assert !VehicleFixes.skipGloveBox(true, false);
        assert !VehicleFixes.skipGloveBox(false, true);
        assert !VehicleFixes.skipGloveBox(false, false);
    }

    static void gloveBoxOffIsStock() {
        VehicleFixes.resetForTest();
        assert !VehiclePatches.onGloveBox(new Object());
        assert VehicleFixes.gloveSkipped == 0;
        VehicleFixes.resetForTest();
    }

    static void streamDefaultsWritten() {
        java.io.File f = SelfTest.tmp("stream.properties");
        f.delete();
        Settings.load(f);
        String t = "";
        try { t = java.nio.file.Files.readString(f.toPath()); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
        assert t.contains("\ngloveBoxFix=false\n") : t;
    }
}