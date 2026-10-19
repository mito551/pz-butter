package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;

/** Run with java -ea. No framework: each check is a static method, failures are AssertionErrors. */
public final class SelfTest {
    private static final File DIR;
    static {
        try { DIR = Files.createTempDirectory("pzbutter-test").toFile(); }
        catch (IOException e) { throw new RuntimeException(e); }
    }

    public static void main(String[] args) {
        boolean ea = false;
        assert ea = true;
        if (!ea) throw new IllegalStateException("run with -ea");
        settingsDefaultsWritten();
        settingsRead();
        settingsGarbage();
        frameEnd();
        frameEnterTwice();
        openLogUnwritable();
        frameLogRotates();
        frameFailureDisables();   // last: it switches the clock off
        delete(DIR);
        System.out.println("ok");
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

    // missing file: written, everything off
    static void settingsDefaultsWritten() {
        File f = tmp("missing.properties"); f.delete();
        Settings.load(f);
        assert f.exists() && !Settings.enabled && !Settings.frameLog;
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

    // 16.667 ms wall, 9.5 ms CPU
    static void frameEnd() {
        FrameClock.on = true;
        long n = FrameClock.frameNo;
        FrameClock.end(16_667_000L, 9_500_000L);
        assert FrameClock.frameNo == n + 1 && Math.abs(FrameClock.wallMs - 16.667) < 1e-9 && FrameClock.cpuMs == 9.5;
    }

    // enter, enter, exit: one frame, measured from the second enter
    static void frameEnterTwice() {
        FrameClock.enter(); FrameClock.enter(); long n = FrameClock.frameNo; FrameClock.exit();
        assert FrameClock.frameNo == n + 1 && FrameClock.wallMs < 50;
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
        for (int i = 0; i < 200; i++) FrameClock.end(16_000_000L, 8_000_000L);
        FrameClock.flush();
        assert new File(f.getPath() + ".1").exists() && f.length() > 0 && f.length() < 1024;
        FrameClock.logLimit = 50L << 20;
    }

    // writer closed under it: IOExceptions -> clock off after 5 failures, nothing thrown
    static void frameFailureDisables() throws RuntimeException {
        FrameClock.openLog(tmp("fail/frames.out"));
        try { FrameClock.log.close(); } catch (IOException e) { throw new RuntimeException(e); }
        for (int i = 0; i < 5; i++) FrameClock.end(1_000_000L, 1_000_000L);
        assert !FrameClock.on;
    }
}
