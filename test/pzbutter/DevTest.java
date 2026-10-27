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
        routeParse();
        routeBlankIsEmpty();
        routeBadIsNull();
        walkNextWaypoint();
        walkFailedEndsScene();
        virtualKeyHold();
        virtualKeysReleasedOnEnd();
        steerChoice();
        driveNoCarEndsScene();
        virtualKeysOffStock();
        ringBatches();
        ringOnCircle();
        populationOnce();
        populationOffNoSpawn();
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
        assert t.contains("\ndevRoute=\n") : t;
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

    static void routeParse() {
        int[][] r = SceneDriver.parseRoute("10,0;10,10;0,10");
        assert r != null && r.length == 3;
        assert r[0][0] == 10 && r[0][1] == 0;
        assert r[1][0] == 10 && r[1][1] == 10;
        assert r[2][0] == 0 && r[2][1] == 10;
    }

    static void routeBlankIsEmpty() {
        int[][] r = SceneDriver.parseRoute("");
        assert r != null && r.length == 0;
        int[][] r2 = SceneDriver.parseRoute("   ;  ");
        assert r2 != null && r2.length == 0;
    }

    static void routeBadIsNull() {
        assert SceneDriver.parseRoute("10,0;bad") == null;
        assert SceneDriver.parseRoute("1,2,3") == null;
    }

    static void walkNextWaypoint() {
        assert SceneWalk.next(0, 3) == 1;
        assert SceneWalk.next(2, 3) == 0;
        assert SceneWalk.next(0, 1) == 0;
    }

    static void walkFailedEndsScene() throws Exception {
        Settings.load(SelfTest.write("enabled=true\ndevScene=walk\ndevRoute=1,0\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        SceneWalk.Fake fake = new SceneWalk.Fake(SceneWalk.Result.FAILED);
        SceneDriver.action = new SceneWalk(fake);
        Path f = SelfTest.tmp("scene/walkfail.out").toPath();
        SceneDriver.marksFile = f;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000);
        SceneDriver.tick(1_000 + 20_000);
        SceneDriver.tick(1_000 + 20_016);
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
        assert lines.size() == 2 : lines;
        assert lines.get(1).contains("failed: path failed") : lines.get(1);
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void virtualKeyHold() {
        VirtualKeys.releaseAll();
        assert VirtualKeys.heldCount == 0;
        VirtualKeys.hold(30);
        assert VirtualKeys.held(30);
        assert VirtualKeys.heldCount == 1;
        VirtualKeys.hold(30); // idempotent
        assert VirtualKeys.heldCount == 1;
        VirtualKeys.release(30);
        assert !VirtualKeys.held(30);
        assert VirtualKeys.heldCount == 0;
        VirtualKeys.release(30); // no negative
        assert VirtualKeys.heldCount == 0;
        VirtualKeys.hold(1); VirtualKeys.hold(2);
        VirtualKeys.releaseAll();
        assert !VirtualKeys.held(1) && !VirtualKeys.held(2);
        assert VirtualKeys.heldCount == 0;
    }

    static void virtualKeysReleasedOnEnd() {
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        VirtualKeys.hold(5);
        SceneDriver.onWorldReady();
        assert VirtualKeys.heldCount == 0;
        VirtualKeys.hold(5);
        Path f = SelfTest.tmp("scene/vk.out").toPath();
        SceneDriver.marksFile = f;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000);
        SceneDriver.tick(1_000 + 20_000); // window
        VirtualKeys.hold(6);
        SceneDriver.end("player dead");
        assert VirtualKeys.heldCount == 0;
        // quit path
        VirtualKeys.hold(7);
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\ndevSceneQuit=true\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        SceneDriver.quit = () -> { };
        SceneDriver.marksFile = SelfTest.tmp("scene/vk2.out").toPath();
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000);
        SceneDriver.tick(21_000);
        SceneDriver.tick(81_000);
        VirtualKeys.hold(8);
        SceneDriver.tick(84_000);
        assert VirtualKeys.heldCount == 0;
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
        VirtualKeys.releaseAll();
    }

    static void steerChoice() {
        assert SceneDrive.steer(0, 0, 0, 60) == SceneDrive.FORWARD;
        assert SceneDrive.steer(0, 45, 0, 60) == (SceneDrive.FORWARD | SceneDrive.LEFT);
        assert SceneDrive.steer(0, -45, 0, 60) == (SceneDrive.FORWARD | SceneDrive.RIGHT);
        assert SceneDrive.steer(0, 5, 0, 60) == SceneDrive.FORWARD;
        assert SceneDrive.steer(0, 0, 100, 60) == 0;
    }

    static void driveNoCarEndsScene() throws Exception {
        Settings.load(SelfTest.write("enabled=true\ndevScene=drive\ndevRoute=1,0\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        SceneDrive.Fake fake = new SceneDrive.Fake();
        fake.vehicle = null;
        SceneDriver.action = new SceneDrive(fake);
        Path f = SelfTest.tmp("scene/drivenocar.out").toPath();
        SceneDriver.marksFile = f;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000);
        SceneDriver.tick(1_000 + 20_000);
        java.util.List<String> lines = java.nio.file.Files.readAllLines(f);
        assert lines.size() == 2 : lines;
        assert lines.get(1).contains("failed: no car") : lines.get(1);
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void virtualKeysOffStock() {
        VirtualKeys.releaseAll();
        assert VirtualKeys.heldCount == 0;
    }

    static void ringBatches() {
        int[][] rp = SceneDriver.ringPoints(25, 10, 16, 0, 0);
        assert rp.length == 3 : rp.length;
        assert rp[0][2] == 10 : rp[0][2];
        assert rp[1][2] == 10 : rp[1][2];
        assert rp[2][2] == 5 : rp[2][2];
    }

    static void ringOnCircle() {
        int[][] rp = SceneDriver.ringPoints(25, 10, 16, 100, 200);
        for (int[] p : rp) {
            double d = Math.sqrt((p[0] - 100.0) * (p[0] - 100.0) + (p[1] - 200.0) * (p[1] - 200.0));
            assert Math.abs(d - 16.0) <= 1.0 : d;
        }
    }

    static void populationOnce() {
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\ndevPopulation=25\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        final int[] calls = {0};
        SceneDriver.Spawner fake = (x, y, z, c) -> { calls[0]++; return true; };
        SceneDriver.spawner = fake;
        Path f = SelfTest.tmp("pop/once.out").toPath();
        SceneDriver.marksFile = f;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000); // settle start -> spawn
        int afterFirst = calls[0];
        SceneDriver.tick(1_010);
        assert calls[0] == afterFirst : calls[0];
        SceneDriver.tick(1_000 + 20_000); // window
        SceneDriver.onWorldReady(); // new world load
        SceneDriver.tick(2_000);
        assert calls[0] > afterFirst : calls[0];
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }

    static void populationOffNoSpawn() {
        Settings.load(SelfTest.write("enabled=true\ndevScene=stand\ndevPopulation=0\n"));
        SceneDriver.resetForTest();
        SceneDriver.init();
        final int[] calls = {0};
        SceneDriver.Spawner fake = (x, y, z, c) -> { calls[0]++; return true; };
        SceneDriver.spawner = fake;
        SceneDriver.onWorldReady();
        SceneDriver.tick(1_000);
        assert calls[0] == 0 : calls[0];
        Settings.load(SelfTest.write("enabled=true\n"));
        SceneDriver.resetForTest();
    }
}
