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
        System.out.println("ok");
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
}
