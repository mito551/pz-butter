package pzbutter;

/** Plan 7 checks (Task 1: keys and template). Run from SelfTest.main. Later tasks append their calls to run(). */
final class LuaGateTest {
    private LuaGateTest() {}

    static void run() throws Exception {
        plan7KeysDefaults();
        settingsTemplateHasPlan7Keys();
    }

    static void plan7KeysDefaults() {
        // the written template itself, master on: every Plan 7 key at its shipped value
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        for (String k : new String[] {"luaWorkerGate", "luaFileWriteThrottle", "logDedupe"}) assert !Settings.flag(k) : k;
        assert Settings.text("modProfile", "?").equals("custom") && Settings.text("modCompat", "?").equals("off");
        assert Settings.flag("uiRetainedMods");
    }

    static void settingsTemplateHasPlan7Keys() {
        String t = Settings.render(new java.util.Properties());
        for (String k : new String[] {"luaWorkerGate=false", "luaFileWriteThrottle=false", "logDedupe=false", "modProfile=custom", "modCompat=off"})
            assert t.contains("\n" + k + "\n") : k;
        assert t.indexOf("\nluaInternConstants=true\n") < t.indexOf("\nluaWorkerGate=false\n");
    }
}
