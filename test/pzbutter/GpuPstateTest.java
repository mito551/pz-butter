package pzbutter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class GpuPstateTest {
    private GpuPstateTest() {}

    static void run() throws Exception {
        gpuPstateUpOnLoad();
        gpuPstateDownWhenIdle();
        gpuPstateHoldsInBand();
        gpuPstateNotWritableOff();
        gpuPstateRestoresLevel();
        gpuPstateNoCard();
        defaultsAndTemplate();
        findCardTest();
        gpuPstateStepsDownAfterFourLowPolls();
        gpuPstateMeasuresButBakeIgnores();
    }

    static void gpuPstateUpOnLoad() {
        assert GpuPstate.nextLevel(2, 6, 15, 16.67, 0) == 3;
    }

    static void gpuPstateDownWhenIdle() {
        assert GpuPstate.nextLevel(2, 6, 6, 16.67, 120) == 1;
        assert GpuPstate.nextLevel(1, 6, 6, 16.67, 120) == 1;
    }

    static void gpuPstateHoldsInBand() {
        assert GpuPstate.nextLevel(3, 6, 10, 16.67, 0) == 3;
        assert GpuPstate.nextLevel(3, 6, 6, 16.67, 60) == 3;
    }

    static void gpuPstateNotWritableOff() throws Exception {
        Path root = Files.createTempDirectory("gpu");
        Path dev = root.resolve("card0/device");
        Files.createDirectories(dev);
        Files.writeString(dev.resolve("pp_dpm_sclk"), "0: 300Mhz\n1: 500Mhz\n2: 800Mhz*\n");
        Files.writeString(dev.resolve("power_dpm_force_performance_level"), "auto\n");
        File p1 = dev.resolve("pp_dpm_sclk").toFile();
        File p2 = dev.resolve("power_dpm_force_performance_level").toFile();
        p1.setWritable(false); p2.setWritable(false);
        String sclkBefore = Files.readString(dev.resolve("pp_dpm_sclk"));
        String powerBefore = Files.readString(dev.resolve("power_dpm_force_performance_level"));
        Settings.load(SelfTest.write("enabled=true\ngpuPstate=true\n"));
        GpuPstate.resetForTest();
        GpuPstate.init(root);
        assert !GpuPstate.guard.on;
        assert Files.readString(dev.resolve("pp_dpm_sclk")).equals(sclkBefore);
        assert Files.readString(dev.resolve("power_dpm_force_performance_level")).equals(powerBefore);
        p1.setWritable(true); p2.setWritable(true);
        delete(root.toFile());
    }

    static void gpuPstateRestoresLevel() throws Exception {
        Path root = Files.createTempDirectory("gpu2");
        Path dev = root.resolve("card0/device");
        Files.createDirectories(dev);
        Files.writeString(dev.resolve("pp_dpm_sclk"), "0: 300Mhz\n1: 500Mhz*\n2: 800Mhz\n");
        Files.writeString(dev.resolve("power_dpm_force_performance_level"), "auto\n");
        Settings.load(SelfTest.write("enabled=true\ngpuPstate=true\n"));
        GpuPstate.resetForTest();
        GpuPstate.init(root);
        assert GpuPstate.guard.on;
        Thread.sleep(100);
        assert Files.readString(dev.resolve("power_dpm_force_performance_level")).trim().equals("manual");
        // idempotent restore
        GpuPstate.restore();
        assert Files.readString(dev.resolve("power_dpm_force_performance_level")).trim().equals("auto");
        String after = Files.readString(dev.resolve("power_dpm_force_performance_level"));
        GpuPstate.restore();
        assert Files.readString(dev.resolve("power_dpm_force_performance_level")).equals(after);
        GpuPstate.resetForTest();
        delete(root.toFile());
    }

    static void gpuPstateNoCard() throws Exception {
        Path root = Files.createTempDirectory("gpu3");
        Settings.load(SelfTest.write("enabled=true\ngpuPstate=true\n"));
        GpuPstate.resetForTest();
        GpuPstate.init(root);
        assert !GpuPstate.guard.on;
        GpuPstate.resetForTest();
        delete(root.toFile());
    }

    static void gpuPstateStepsDownAfterFourLowPolls() {
        int cur = 3, max = 6;
        int low = 0;
        for (int i = 0; i < 3; i++) {
            int[] r = GpuPstate.step(cur, max, 6, 16.67, low);
            cur = r[0]; low = r[1];
            assert cur == 3 : "no step before 120 low frames";
        }
        int[] r = GpuPstate.step(cur, max, 6, 16.67, low);
        assert r[0] == 2 : "one step down on the 4th low poll";
        cur = r[0]; low = r[1];
        assert low == 0;
        for (int i = 0; i < 3; i++) {
            r = GpuPstate.step(cur, max, 6, 16.67, low);
            cur = r[0]; low = r[1];
            assert cur == 2 : "no second step before 120 more low frames";
        }
        r = GpuPstate.step(cur, max, 6, 16.67, low);
        assert r[0] == 1 : "second step down on the 4th more low poll";
    }

    static void gpuPstateMeasuresButBakeIgnores() {
        Settings.load(SelfTest.write("enabled=true\ngpuPstate=true\nbakeBudgetGpu=false\nbakeBudgetAdaptive=false\n"));
        GpuTimer.resetForTest();
        GpuTimer.init();
        assert GpuTimer.guard.on;
        assert !GpuTimer.bakeMode;
        GpuTimer.lastGpuMs = 12.5;
        assert GpuTimer.loadMs(5.0) == 5.0;
        GpuTimer.resetForTest();
        Settings.load(SelfTest.write("enabled=true\\n"));
    }

    static void defaultsAndTemplate() {
        Settings.load(SelfTest.write("enabled=true\n"));
        GpuPstate.resetForTest();
        GpuPstate.init();
        assert !GpuPstate.guard.on;
        String t = Settings.render(new java.util.Properties());
        assert t.contains("\ngpuPstate=false\n");
        // default key off
        Settings.load(SelfTest.write(Settings.render(new java.util.Properties()).replace("\nenabled=false\n", "\nenabled=true\n")));
        assert !Settings.flag("gpuPstate");
        Settings.load(SelfTest.write("enabled=true\n"));
    }

    static void findCardTest() throws Exception {
        Path root = Files.createTempDirectory("gpu4");
        Path dev = root.resolve("card0/device");
        Files.createDirectories(dev);
        Files.writeString(dev.resolve("pp_dpm_sclk"), "x");
        Files.writeString(dev.resolve("power_dpm_force_performance_level"), "auto");
        assert GpuPstate.findCard(root).equals(dev);
        delete(root.toFile());
        // no card
        Path root2 = Files.createTempDirectory("gpu5");
        assert GpuPstate.findCard(root2) == null;
        delete(root2.toFile());
    }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
