package pzbutter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * modReadAhead (Plan 11b). The game reads each active mod's files twice on the join path: once as plain OS reads during
 * boot and the join's animation checksum and script load. From the first {@code ZomboidFileSystem.searchFolders} inside
 * the mod load (the first place the game has resolved a mod root), one daemon thread per root reads, and discards, the
 * four subtrees the checksum and script load read (media/animation, media/AnimSets, media/lua, media/script), so the OS
 * file cache is warm when the game reads them for real. Threads run at minimum priority and stop at the shared size
 * budget; pzbutter's warm phase is the mod load itself, not a pre-menu pass (the mod list resolves there).
 */
public final class ModReadAhead {
    private ModReadAhead() {}

    public static final Guard guard = new Guard("modReadAhead");

    static void init() {
        try { guard.on = Settings.flag("modReadAhead"); } catch (Throwable t) { guard.fail(t); }
    }

    /** SPEC 1026: 1.5 GB. Volatile so the test can pin the gate; every read re-reads it. */
    public static volatile long BUDGET = 1_500_000_000L;

    /** Shared cross-thread budget: bytes read so far. */
    public static final AtomicLong budget = new AtomicLong();

    /** Test seam: the default launches and starts; the test captures instead. */
    public static volatile Consumer<Thread> LAUNCH = Thread::start;

    static final Set<String> warmed = Collections.synchronizedSet(new HashSet<>());

    static final String[] SUBTREES = {"media/animation", "media/AnimSets", "media/lua", "media/script"};

    public static void warm(File dir) {
        if (!guard.on || dir == null) return;
        try {
            if (budget.get() >= BUDGET) return;
            if (!warmed.add(dir.getPath())) return;
            Thread t = new Thread(() -> warmDir(dir), "pzbutter-modReadAhead");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            LAUNCH.accept(t);
        } catch (Throwable t) { guard.fail(t); }
    }

    static void warmDir(File root) {
        try {
            for (String sub : SUBTREES) {
                File d = new File(root, sub);
                if (d.isDirectory()) readTree(d);
            }
        } catch (Throwable t) { guard.fail(t); }
    }

    static void readTree(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (budget.get() >= BUDGET) return;
            if (f.isDirectory()) readTree(f);
            else if (f.isFile()) readFile(f);
        }
    }

    static void readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (;;) {
                int n = in.read(buf);
                if (n < 0) return;
                budget.addAndGet(n);
                if (budget.get() >= BUDGET) return;
            }
        } catch (java.io.IOException e) {
            // unreadable file: skip; the warm pass never holds the game back
        }
    }
}
