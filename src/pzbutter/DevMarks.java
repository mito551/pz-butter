package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev: a key writes window marks next to the frame log (marks.out). The scene driver (a later task) uses {@link #write} too.
 */
public final class DevMarks {
    private DevMarks() {}

    public static final Guard guard = new Guard("devMark");

    /** Press counter for the mark key (press 1 -> w1.start, ...). */
    static volatile int presses;
    /** Counts polls that actually read the keyboard. Stays 0 with every dev key off. */
    public static volatile long keyboardReads;

    static int key;

    static void init() {
        guard.on = Settings.flag("devMark");
        if (!guard.on) return;
        String name = Settings.text("devMarkKey", "F9");
        key = org.lwjglx.input.Keyboard.getKeyIndex(name);
        if (key == org.lwjglx.input.Keyboard.KEY_NONE) {
            guard.on = false;
            System.out.println("[PZButter] devMark: unknown key " + name);
            return;
        }
        System.out.println("[PZButter] devMark: " + name + " writes window marks to pzbutter/marks.out");
    }

    public static String nextName(int press) {
        int window = (press + 1) / 2;
        boolean start = (press % 2) == 1;
        return "w" + window + (start ? ".start" : ".end");
    }

    public static String line(String name, long epochMs, String note) {
        StringBuilder sb = new StringBuilder("mark ").append(name).append(' ').append(epochMs);
        if (note != null) sb.append(' ').append(note);
        return sb.toString();
    }

    public static void write(String name, String note) {
        Path marks = new File(System.getProperty("user.home"), "Zomboid/pzbutter/marks.out").toPath();
        write(marks, line(name, System.currentTimeMillis(), note));
    }

    /** Package-private seam: appends one line to {@code file}. */
    static void write(Path file, String text) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (FileWriter w = new FileWriter(file.toFile(), true)) {
                w.write(text);
                w.write("\n");
            }
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    public static void poll() {
        if (!guard.on) return;
        if (Thread.currentThread() != FrameClock.mainThread) return;
        try {
            keyboardReads++;
            if (zombie.input.GameKeyboard.isKeyPressed(key)) {
                write(nextName(++presses), null);
            }
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    static void resetForTest() {
        guard.on = false;
        presses = 0;
        keyboardReads = 0;
        key = 0;
    }
}
