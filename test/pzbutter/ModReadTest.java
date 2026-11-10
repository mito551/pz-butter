package pzbutter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** modReadAhead: the warm loop reads the four media subtrees of each active mod, once, under a shared budget. */
final class ModReadTest {
    private ModReadTest() {}

    static void run() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n"));
        ModReadAhead.init();
        check("modReadAhead defaults off", !ModReadAhead.guard.on);
        List<Object> launched = new ArrayList<>();
        ModReadAhead.LAUNCH = t -> launched.add(t);
        ModReadAhead.budget.set(0);

        // Temp tree: two read subtrees, two skipped spots.
        Path root = Files.createTempDirectory("pzbutter-readahead");
        write(root, "media/lua/a.lua", 300);
        write(root, "media/AnimSets/deep/b.xml", 200);
        write(root, "media/script/s.txt", 100);
        write(root, "media/other/x.bin", 50);
        write(root, "media/animation/c.anims", 700);
        write(root, "topfile.txt", 10);

        Settings.load(SelfTest.write("enabled=true\nmodReadAhead=true\n"));
        ModReadAhead.init();
        check("template enables the guard", ModReadAhead.guard.on);
        ModReadAhead.warm(root.toFile());
        check("warm queued exactly one thread", launched.size() == 1);
        Object threadO = launched.get(0);
        Thread t = (Thread) threadO;
        check("warm thread is daemon + low priority", t.isDaemon() && t.getPriority() == Thread.MIN_PRIORITY);
        ModReadAhead.warm(root.toFile());
        check("queue does not repeat the root", launched.size() == 1);

        // Run the walk directly for determinism.
        ModReadAhead.warmDir(root.toFile());
        long afterTree = ModReadAhead.budget.get();
        check("read exactly the four subtrees", afterTree == 300 + 200 + 100 + 700);

        // Budget cap.
        ModReadAhead.budget.set(ModReadAhead.BUDGET - 500);
        ModReadAhead.warmDir(root.toFile());
        long capped = ModReadAhead.budget.get();
        check("budget stops the reads", capped >= ModReadAhead.BUDGET - 500 && capped <= ModReadAhead.BUDGET + 65535);

        // Warm declines when the budget is already exceeded.
        long before = ModReadAhead.budget.get();
        ModReadAhead.warm(Path.of(root.toString(), "copy").toFile());
        check("over budget: no thread", launched.size() == 1 && ModReadAhead.budget.get() == before);

        // Unreadable/missing subtrees are safe.
        ModReadAhead.warmDir(new File("/nonexistent-root-for-butter-test"));
        check("missing root is safe", ModReadAhead.budget.get() >= capped);

        // guard off declines.
        Settings.load(SelfTest.write("enabled=true\n"));
        ModReadAhead.init();
        ModReadAhead.warm(root.toFile());
        check("guard off: warm declines", launched.size() == 1);

        Files.walk(root).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    }

    static void write(Path root, String rel, int size) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, (byte) 1);
        Files.write(p, data);
    }

    static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }
}
