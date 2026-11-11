package pzbutter;

/**
 * lowerCpuMode (SPEC 3.x): sleep after each main / lighting / render pass while the window
 * is not in front or the game is paused. Modes per SPEC row:
 * 1 = when paused or inactive; 2 = only when paused AND inactive; 3 = always, frame cap by
 * sleeping 1000/fps - spent - 2 ms (at least 1 ms); 4 = never. A fully inactive game sleeps
 * 32 ms (main), 100 ms (lighting), 16 ms (render); otherwise 1 ms. 0/anything else = stock.
 */
final class CpuTest {
    private CpuTest() {}

    static void run() throws Exception {
        // Stock: no mode, no sleeps, guard off.
        Settings.load(SelfTest.write("enabled=true\nlowerCpuMode=garbage\n"));
        CpuPatches.init();
        assert !CpuPatches.guard.on : "garbage mode is stock";
        assert CpuPatches.mode() == 0 : "unreadable mode = stock";
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 0 : "stock main sleeps nothing";
        assert CpuPatches.sleepMs(CpuPatches.LIGHT, 0) == 0 : "stock light sleeps nothing";

        CpuPatches.PAUSED = () -> false;
        CpuPatches.ACTIVE = () -> true;
        CpuPatches.FPS = () -> 0;
        CpuPatches.SLEEP = ms -> {};

        Settings.load(SelfTest.write("enabled=true\nlowerCpuMode=1\n"));
        CpuPatches.init();
        assert CpuPatches.guard.on : "mode 1 turns the guard on";
        assert CpuPatches.mode() == 1 : "mode 1 read back";

        // Mode 1, active window, unpaused: never (the SPEC parenthetical).
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 0 : "mode 1 active unpaused main";
        assert CpuPatches.sleepMs(CpuPatches.LIGHT, 0) == 0 : "mode 1 active unpaused light";
        assert CpuPatches.sleepMs(CpuPatches.RENDER, 0) == 0 : "mode 1 active unpaused render";
        CpuPatches.PAUSED = () -> true;
        // Mode 1, active, paused: 1 ms every pass.
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 1 : "mode 1 active paused main";
        assert CpuPatches.sleepMs(CpuPatches.LIGHT, 0) == 1 : "mode 1 active paused light";
        assert CpuPatches.sleepMs(CpuPatches.RENDER, 0) == 1 : "mode 1 active paused render";
        // Mode 1, inactive: the deep sleeps, paused or not.
        CpuPatches.ACTIVE = () -> false;
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 32 : "mode 1 inactive main deep";
        assert CpuPatches.sleepMs(CpuPatches.LIGHT, 0) == 100 : "mode 1 inactive light deep";
        assert CpuPatches.sleepMs(CpuPatches.RENDER, 0) == 16 : "mode 1 inactive render deep";

        Settings.load(SelfTest.write("enabled=true\nlowerCpuMode=2\n"));
        CpuPatches.init();
        CpuPatches.PAUSED = () -> false;
        CpuPatches.ACTIVE = () -> false;
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 0 : "mode 2 inactive unpaused: nothing";
        CpuPatches.PAUSED = () -> true;
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 1 : "mode 2 paused and inactive: 1 ms";
        assert CpuPatches.sleepMs(CpuPatches.RENDER, 0) == 1 : "mode 2 paused and inactive render";
        CpuPatches.ACTIVE = () -> true;
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 1 : "mode 2 paused active: 1 ms";

        Settings.load(SelfTest.write("enabled=true\nlowerCpuMode=3\n"));
        CpuPatches.init();
        CpuPatches.ACTIVE = () -> false;
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 32 : "mode 3 inactive main deep";
        assert CpuPatches.sleepMs(CpuPatches.LIGHT, 0) == 100 : "mode 3 inactive light deep";
        CpuPatches.ACTIVE = () -> true;
        CpuPatches.FPS = () -> 60;
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 14 : "mode 3 caps 1000/60 - 0 - 2 = 14";
        assert CpuPatches.sleepMs(CpuPatches.RENDER, 20) == 1 : "mode 3 cap floors at 1 ms";
        CpuPatches.FPS = () -> 0;
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 1 : "mode 3 without a cap: 1 ms";

        Settings.load(SelfTest.write("enabled=true\nlowerCpuMode=4\n"));
        CpuPatches.init();
        assert !CpuPatches.guard.on : "mode 4 never";
        assert CpuPatches.sleepMs(CpuPatches.MAIN, 0) == 0 : "mode 4 sleeps nothing";

        // Hooks: the advice sleeps through the seam when the mode asks (main), counts the deep values.
        Settings.load(SelfTest.write("enabled=true\nlowerCpuMode=1\n"));
        CpuPatches.init();
        long[] slept = {0};
        CpuPatches.SLEEP = ms -> slept[0] += ms;
        CpuPatches.PAUSED = () -> true;
        CpuPatches.ACTIVE = () -> false;
        CpuPatches.MainPass.enter();
        CpuPatches.MainPass.exit();
        assert slept[0] == 32 : "main hook slept the deep 32 ms";
        CpuPatches.LightPass.enter();
        CpuPatches.LightPass.exit();
        assert slept[0] == 132 : "light hook slept the deep 100 ms";
        CpuPatches.guard.on = false;
        slept[0] = 0;
        CpuPatches.MainPass.exit();
        assert slept[0] == 0 : "guard off: stock";

        Settings.load(SelfTest.write("enabled=true\n"));
        CpuPatches.init();
        assert !CpuPatches.guard.on : "stock restored";
        CpuPatches.PAUSED = null;
        CpuPatches.ACTIVE = null;
        CpuPatches.FPS = null;
        CpuPatches.SLEEP = null;
    }
}
