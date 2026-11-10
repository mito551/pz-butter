package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

import me.zed_0xff.zombie_buddy.Patch;

/** Run with java -ea. No framework: each check is a static method, failures are AssertionErrors. */
public final class SelfTest {
    private static final File DIR;
    static {
        try { DIR = Files.createTempDirectory("pzbutter-test").toFile(); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    public static void main(String[] args) throws Exception {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");
        settingsDefaultsWritten();
        settingsRead();
        settingsGarbage();
        setFromUiValid();
        setFromUiRejects();
        setFromUiUnknownKey();
        settingsCache();
        settingsMemoSeesPageWrite();
        claims();
        claimsResolve();
        claimProtocol();
        FramePhase.run();
        GetterMemo.run();
        PZButterSquares.run();
        settingsEntry();
        frameEnd();
        frameInterval();
        frameEnterTwice();
        exitAdviceSuppresses();
        openLogUnwritable();
        frameLogRotates();
        frameFailureDisables();   // last: it switches the clock off
        DeliveryTest.run();
        GuardTest.run();
        LuaBudgetTest.run();
        LuaUiTest.run();
        UiRetainTest.run();
        SoundTest.run();
        WorldTest.run();
        ChunkTest.run();
        ModelTest.run();
        NetTest.run();
        TexTest.run();
        BodyLocTest.run();
        ScriptTest.run();
        EnumTest.run();
ModScanTest.run();
        LuaGateTest.run();
        DevTest.run();
        SpikeTest.run();
        ChunkShimTest.run();
        ZombieTest.run();
        AnimSkipTest.run();
        WorkerTest.run();
        RuntimeTest.run();
        CoresTest.run();
        CensusTest.run();
        GpuTest.run();
        Bc3Test.run();
        StreamTest.run();
        LoadTest.run();
        GpuPstateTest.run();
        SideTest.run();
        ServerTest.run();
        HostTest.run();
        MpClientTest.run();
        NetStatsTest.run();
        CellSpreadTest.run();
        ThirdPartyTest.run();
        PreloadSafetyTest.run();
        VehTrackTest.run();
        delete(DIR);
        System.out.println("ok");
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    static File tmp(String name) {
        File f = new File(DIR, name);
        f.getParentFile().mkdirs();
        return f;
    }

    static File write(String text) {
        File f = tmp("s" + System.nanoTime() + ".properties");
        try (FileWriter w = new FileWriter(f)) { w.write(text); }
        catch (IOException e) { throw new RuntimeException(e); }
        return f;
    }

    // missing file: written, mod on, frame log off
    static void settingsDefaultsWritten() {
        File f = tmp("missing.properties"); f.delete();
        Settings.load(f);
        assert f.exists() && Settings.enabled && !Settings.frameLog;
        Settings.load(write("enabled=false\n"));
        assert !Settings.enabled;
    }

    static void settingsRead() {
        File f = write("enabled=true\nframeLog=yes\n");
        Settings.load(f);
        assert Settings.enabled && Settings.frameLog;
    }

    // a directory where the file should be: no throw, all off
    static void settingsGarbage() {
        Settings.load(write("enabled=on\n"));
        File d = tmp("dir.properties"); d.mkdirs();
        Settings.load(d);
        assert !Settings.enabled && !Settings.frameLog;
    }

    static String read(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    // Settings parses the same bytes once and memoises each key; a different file, forcedOff and the page's write must show through
    static void settingsCache() {
        File f = write("enabled=true\nuiRetained=true\nbakeFrameBudget=5\nzoomEase=0.1,0.2,0.3,0.4\n");
        Settings.load(f);
        assert Settings.flag("uiRetained") && Settings.num("bakeFrameBudget", 8, 1, 64) == 5;
        Settings.load(f);   // same bytes: same answers
        assert Settings.flag("uiRetained") && Settings.num("bakeFrameBudget", 8, 1, 64) == 5;
        Settings.forcedOff.add("uiRetained");   // ModCompat changes forcedOff after a load
        assert !Settings.flag("uiRetained");
        Settings.load(f);   // a load clears forcedOff; the same bytes still give the value
        assert Settings.flag("uiRetained");
        try (FileWriter w = new FileWriter(f)) { w.write("enabled=true\nuiRetained=false\nbakeFrameBudget=99\n"); }
        catch (IOException e) { throw new RuntimeException(e); }
        Settings.load(f);   // other bytes: parsed again
        assert !Settings.flag("uiRetained") && Settings.num("bakeFrameBudget", 8, 1, 64) == 64;
        Settings.load(write("enabled=true\n"));
        assert Settings.num("bakeFrameBudget", 8, 1, 64) == 8 && Settings.text("zoomEase", "x").equals("x");
    }

    // the options page writes into the loaded properties: a key read before the write must answer the new value after it
    static void settingsMemoSeesPageWrite() {
        File f = write("enabled=true\nuiTiming=false\n");
        Settings.load(f);
        assert !Settings.flag("uiTiming");
        assert "true".equals(Settings.setFromUi("uiTiming", "true"));
        assert Settings.flag("uiTiming") : "the memo kept the old answer after setFromUi";
    }

    // Plan 19 Task 6: a valid page write is stored and the file updated, atomically (no .tmp left behind)
    static void setFromUiValid() {
        File f = write("enabled=true\nuiTiming=false\n");
        Settings.load(f);
        String r = Settings.setFromUi("uiTiming", "true");
        assert "true".equals(r) && "true".equals(Settings.raw("uiTiming")) && !new File(f.getPath() + ".tmp").exists();
        assert read(f).contains("uiTiming=true");
        Settings.load(f);   // the written file loads it back
        assert "true".equals(Settings.raw("uiTiming"));
    }

    // "yes" is not the page's currency (Focus 4): the old value is returned and the file is unchanged
    static void setFromUiRejects() {
        File f = write("enabled=true\nuiTiming=false\n");
        Settings.load(f);
        String before = read(f);
        String r = Settings.setFromUi("uiTiming", "yes");
        assert "false".equals(r) && "false".equals(Settings.raw("uiTiming")) && before.equals(read(f));
    }

    // a non-boolean key is rejected too: nothing stored, nothing written
    static void setFromUiUnknownKey() {
        File f = write("enabled=true\nuiTiming=false\n");
        Settings.load(f);
        String before = read(f);
        String r = Settings.setFromUi("gpuMaxFrames", "true");
        assert "false".equals(r) && before.equals(read(f));
    }

    static void settingsEntry() {
        // entry() writes plain text the way Properties.store does, and falls back to it for the rest
        String[] samples = {"0", "0.25,0.1,0.25,1.0", "a b", "k=v", "t\tx", "u\u00e9", "#c", "", "ObjectTooltip,ISToolTip"};
        for (String s : samples) assert Settings.entry("key", s).equals(Settings.entrySlow("key", s)) : s;
        for (String line : Settings.render(new java.util.Properties()).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#"))
                assert Settings.entry(line.substring(0, eq), line.substring(eq + 1)).equals(Settings.entrySlow(line.substring(0, eq), line.substring(eq + 1))) : line;
        }
    }

    // claim protocol: 3 claims in every order -> same winner; single installs; late never evicts; tie -> smaller id
    static void claims() { runClaimChecks(); }
    static void claimsResolve() { runClaimChecks(); }
    static void claimProtocol() { runClaimChecks(); }

    static void runClaimChecks() {
        // tie -> smaller mod id
        PZBClaim.resetForTest();
        int[] inst = { 0, 0 };
        PZBClaim.claim("s", "b", 5, () -> inst[1]++, w -> {});
        PZBClaim.claim("s", "a", 5, () -> inst[0]++, w -> {});
        PZBClaim.resolve();
        assert "a".equals(PZBClaim.owner("s")) : "tie winner";
        // single claim installs
        PZBClaim.resetForTest();
        int[] n = { 0 };
        PZBClaim.claim("s2", "m", 1, () -> n[0]++, w -> {});
        PZBClaim.resolve();
        assert n[0] == 1;
        // 3 claims in all 6 orders -> same winner (core beats xeg beats vqol)
        java.util.List<String> mods = java.util.Arrays.asList("core", "xeg", "vqol");
        int[] prio = { 100, 60, 40 };
        // map mods to priorities fixed
        java.util.Map<String, Integer> pm = new java.util.HashMap<>();
        pm.put("core", 100); pm.put("xeg", 60); pm.put("vqol", 40);
        for (int i = 0; i < 6; i++) {
            PZBClaim.resetForTest();
            int[] calls = new int[3];
            int[] downs = new int[3];
            int idx = 0;
            String[] order = mods.toArray(new String[0]);
            // simple permutation by i
            if (i == 1) { order = new String[]{"core","vqol","xeg"}; }
            if (i == 2) { order = new String[]{"xeg","core","vqol"}; }
            if (i == 3) { order = new String[]{"xeg","vqol","core"}; }
            if (i == 4) { order = new String[]{"vqol","core","xeg"}; }
            if (i == 5) { order = new String[]{"vqol","xeg","core"}; }
            for (String m : order) {
                final int j = mods.indexOf(m);
                PZBClaim.claim("sec", m, pm.get(m), () -> calls[j]++, w -> downs[j]++);
            }
            PZBClaim.resolve();
            assert "core".equals(PZBClaim.owner("sec")) : "winner " + i;
            assert calls[0] == 1 && calls[1] == 0 && calls[2] == 0 : "install counts " + i;
        }
        // late claim never evicts
        PZBClaim.resetForTest();
        int[] a = { 0 }, b = { 0 };
        int[] downB = { 0 };
        PZBClaim.claim("late", "a", 10, () -> a[0]++, w -> {});
        PZBClaim.resolve();
        assert "a".equals(PZBClaim.owner("late"));
        PZBClaim.claim("late", "b", 1, () -> b[0]++, w -> { downB[0]++; });
        assert "a".equals(PZBClaim.owner("late"));
        assert a[0] == 1 && b[0] == 0;
        assert downB[0] == 1;
    }

    // 16.667 ms since the last frame started, 4.2 ms inside frameStep, 3.5 ms CPU
    static void frameEnd() {
        FrameClock.on = true;
        long n = FrameClock.frameNo;
        FrameClock.end(16_667_000L, 4_200_000L, 3_500_000L);
        assert FrameClock.frameNo == n + 1 && Math.abs(FrameClock.intervalMs - 16.667) < 1e-9
            && Math.abs(FrameClock.stepMs - 4.2) < 1e-9 && FrameClock.cpuMs == 3.5;
    }

    // the interval spans the wait outside frameStep; the step does not
    static void frameInterval() {
        FrameClock.enter(); FrameClock.exit();
        sleep(30);
        FrameClock.enter(); FrameClock.exit();
        assert FrameClock.intervalMs >= 30 && FrameClock.stepMs < 30;
    }

    // enter, (no exit), enter, exit: one frame, step measured from the second enter
    static void frameEnterTwice() {
        FrameClock.enter(); sleep(80); FrameClock.enter(); long n = FrameClock.frameNo; FrameClock.exit();
        assert FrameClock.frameNo == n + 1 && FrameClock.stepMs < 50;
        FrameClock.exit();   // no start stored: no frame
        assert FrameClock.frameNo == n + 1;
    }

    // unwritable path: no log, clock stays on, nothing thrown
    static void openLogUnwritable() {
        FrameClock.openLog(new File("/proc/pzbutter-no/frames.out"));
        assert FrameClock.on && FrameClock.log == null;
    }

    // limit lowered to 1 KB: frames.out.1 appears, frames.out restarts small
    static void frameLogRotates() {
        File f = tmp("log/frames.out"); FrameClock.logLimit = 1024; FrameClock.openLog(f);
        for (int i = 0; i < 200; i++) FrameClock.end(16_000_000L, 8_000_000L, 8_000_000L);
        FrameClock.flush();
        assert new File(f.getPath() + ".1").exists() && f.length() > 0 && f.length() < 1024;
        FrameClock.logLimit = 50L << 20;
    }

    // writer closed under it: IOExceptions -> clock off after 5 failures, nothing thrown
    static void frameFailureDisables() throws RuntimeException {
        FrameClock.openLog(tmp("fail/frames.out"));
        try { FrameClock.log.close(); } catch (IOException e) { throw new RuntimeException(e); }
        for (int i = 0; i < 5; i++) FrameClock.end(1_000_000L, 1_000_000L, 1_000_000L);
        assert !FrameClock.on;
    }

    // ByteBuddy inlines advice into frameStep; an exception from the exit advice must not reach the game
    static void exitAdviceSuppresses() {
        try {
            Patch.OnExit a = Patches.Frame.class.getMethod("exit").getAnnotation(Patch.OnExit.class);
            assert a.suppress() == Throwable.class && a.onThrowable() == Throwable.class;
        } catch (NoSuchMethodException e) { throw new AssertionError(e); }
    }

}
