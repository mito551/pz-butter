package pzbutter;

import java.nio.file.Files;
import java.nio.file.Path;

final class DevTest {
    private DevTest() {}

    static void run() throws Exception {
        markNames();
        markLine();
        markWriteAppends();
        devOffNothing();
        defaultsHaveKeys();
    }

    static void markNames() {
        assert DevMarks.nextName(1).equals("w1.start");
        assert DevMarks.nextName(2).equals("w1.end");
        assert DevMarks.nextName(3).equals("w2.start");
        assert DevMarks.nextName(4).equals("w2.end");
    }

    static void markLine() {
        assert DevMarks.line("w1.start", 1700000000123L, null).equals("mark w1.start 1700000000123");
        assert DevMarks.line("scene.end", 5L, "failed: no car").equals("mark scene.end 5 failed: no car");
    }

    static void markWriteAppends() throws Exception {
        Path f = SelfTest.tmp("devmarks/x.out").toPath();
        DevMarks.write(f, "first");
        DevMarks.write(f, "second");
        java.util.List<String> lines = Files.readAllLines(f);
        assert lines.size() == 2 : lines.size();
        assert lines.get(0).equals("first") : lines.get(0);
        assert lines.get(1).equals("second") : lines.get(1);
    }

    static void devOffNothing() {
        Settings.load(SelfTest.write("enabled=true\n"));
        DevMarks.resetForTest();
        DevMarks.init();
        DevMarks.keyboardReads = 0;
        DevMarks.poll();
        assert DevMarks.keyboardReads == 0;
        Settings.load(SelfTest.write("enabled=false\n"));
        DevMarks.resetForTest();
    }

    static void defaultsHaveKeys() {
        String t = Settings.render(new java.util.Properties());
        assert t.contains("\ndevMark=false\n") : t;
        assert t.contains("\ndevMarkKey=F9\n") : t;
    }
}