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
        scenePhases();
        sceneMarksOnce();
        sceneUnsetIdle();
        sceneEndEarly();
        sceneSecondLoadResets();
        sceneQuitTiming();
        autoContinueOnce();
        devKeysDefaults();
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
        assert t.contains("\ndevAutoContinue=false\n") : t;
        assert t.contains("\ndevScene=off\n") : t;
        assert t.contains("\ndevSceneSettleSec=20\n") : t;
        assert t.contains("\ndevSceneSec=60\n") : t;
        assert t.contains("\ndevSceneQuit=false\n") : t;
        assert t.contains("\ndevPopulation=0\n") : t;
    }

    static void scenePhases() {
        assert SceneDriver.phaseAt(0, 20, 60) == SceneDriver.Phase.SETTLE;
        assert SceneDriver.phaseAt(19_999, 20, 60) == SceneDriver.Phase.SETTLE;
        assert SceneDriver.phaseAt(20_000, 20, 60) == SceneDriver.Phase.WINDOW;
        assert SceneDriver.phaseAt(79_999, 20, 60) == SceneDriver.Phase.WINDOW;
        assert SceneDriver.phaseAt(80_000, 20, 60) == SceneDriver.Phase.DONE;
    }

    static void sceneMarksOnce() throws Exception {
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        Path f = SelfTest.tmp("scene/marks.out").toPath();
        SceneDriver.marksFile = f;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000); // settle
        SceneDriver.tick(1_000 + 20_000); // window -> start
        SceneDriver.tick(1_000 + 80_000); // done -> end
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
        assert lines.size() == 2 : lines;
        assert lines.get(0).startsWith("mark scene.start ") : lines.get(0);
        assert lines.get(1).startsWith("mark scene.end ") : lines.get(1);
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void sceneUnsetIdle() {
        Settings.load(SelfTest.write("enabled=true\ndevScene=off\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        SceneDriver.onWorldReady();
        SceneDriver.tick(0);
        SceneDriver.tick(100_000);
        assert SceneDriver.phase() == SceneDriver.Phase.IDLE;
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void sceneEndEarly() throws Exception {
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        Path f = SelfTest.tmp("scene/early.out").toPath();
        SceneDriver.marksFile = f;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000);
        SceneDriver.tick(1_000 + 20_000); // window
        SceneDriver.end("player dead");
        int before = java.nio.file.Files.exists(f) ? java.nio.file.Files.readAllLines(f).size() : 0;
        assert before == 2 : before;
        SceneDriver.tick(1_000 + 80_000); // DONE now writes nothing
        int after = java.nio.file.Files.readAllLines(f).size();
        assert after == before : after;
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void sceneSecondLoadResets() throws Exception {
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\ndevSceneQuit=true\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        final int[] quits = {0};
        SceneDriver.quit = () -> quits[0]++;
        Path f = SelfTest.tmp("scene/second.out").toPath();
        SceneDriver.marksFile = f;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1000);
        SceneDriver.tick(1000 + 20_000);
        SceneDriver.tick(1000 + 80_000);
        SceneDriver.onWorldReady();
        SceneDriver.tick(2000);
        SceneDriver.tick(2000 + 20_000);
        SceneDriver.tick(2000 + 80_000);
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
        assert lines.size() == 4 : lines;
        assert lines.get(0).startsWith("mark scene.start ") : lines.get(0);
        assert lines.get(1).startsWith("mark scene.end ") : lines.get(1);
        assert lines.get(2).startsWith("mark scene.start ") : lines.get(2);
        assert lines.get(3).startsWith("mark scene.end ") : lines.get(3);
        // no quit from the first scene fires after the reset
        SceneDriver.tick(1000 + 80_000 + 3_000);
        assert quits[0] == 0 : quits[0];
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void sceneQuitTiming() throws Exception {
        final int[] quits = {0};
        // normal end: quit 3 s after scene.end, once, on the main thread's tick
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\ndevSceneQuit=true\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        SceneDriver.quit = () -> quits[0]++;
        SceneDriver.marksFile = SelfTest.tmp("scene/quit.out").toPath();
        SceneDriver.onWorldReady();
        SceneDriver.tick(1000);
        SceneDriver.tick(21_000);
        SceneDriver.tick(81_000); // scene.end
        SceneDriver.tick(83_999);
        assert quits[0] == 0 : quits[0];
        SceneDriver.tick(84_000);
        SceneDriver.tick(90_000);
        assert quits[0] == 1 : quits[0];
        // early end (R2): quit 3 s after end(reason)
        SceneDriver.onWorldReady();
        SceneDriver.tick(100_000);
        SceneDriver.tick(120_000);
        SceneDriver.end("player dead", 130_000);
        SceneDriver.tick(132_999);
        assert quits[0] == 1 : quits[0];
        SceneDriver.tick(133_000);
        assert quits[0] == 2 : quits[0];
        // devSceneQuit=false: never
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        SceneDriver.quit = () -> quits[0]++;
        SceneDriver.marksFile = SelfTest.tmp("scene/quit2.out").toPath();
        SceneDriver.onWorldReady();
        SceneDriver.tick(1000);
        SceneDriver.tick(21_000);
        SceneDriver.tick(81_000);
        SceneDriver.tick(200_000);
        assert quits[0] == 2 : quits[0];
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void autoContinueOnce() {
        assert SceneDriver.shouldContinue(true, false);
        assert !SceneDriver.shouldContinue(true, true);
        assert !SceneDriver.shouldContinue(false, false);
        assert !SceneDriver.shouldContinue(false, true);
    }

    static void devKeysDefaults() {
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.init();
        assert !SceneDriver.guard.on;
    }
}