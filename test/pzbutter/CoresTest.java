package pzbutter;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

final class CoresTest {
    private CoresTest() {}

    static void run() throws Exception {
        coresParseList();
        coresParseEmpty();
        coresIntelHybrid();
        coresCapacityHybrid();
        coresNotHybridPlacesNothing();
        coresMissingSysfsOff();
        coresOffStock();
        coresNativeLookupsOff();
        coresPinSelfFullSet();
        coresWindowsCpuSetParse();
    }

    static void coresParseList() {
        BitSet b = Cores.parseList("0-3,8,10-11\n");
        for (int i : new int[]{0,1,2,3,8,10,11}) assert b.get(i);
        assert b.cardinality() == 7;
    }

    static void coresParseEmpty() {
        assert Cores.parseList("").isEmpty();
    }

    static Path fixture(String name) throws Exception {
        Path root = Files.createTempDirectory(name);
        return root;
    }

    static void coresIntelHybrid() throws Exception {
        Path root = fixture("intel");
        Files.createDirectories(root.resolve("devices/cpu_core"));
        Files.writeString(root.resolve("devices/cpu_core/cpus"), "0-15\n");
        Files.createDirectories(root.resolve("devices/cpu_atom"));
        Files.writeString(root.resolve("devices/cpu_atom/cpus"), "16-23\n");
        BitSet b = Cores.performanceLinux(root);
        for (int i = 0; i <= 15; i++) assert b.get(i);
        for (int i = 16; i <= 23; i++) assert !b.get(i);
    }

    static void coresCapacityHybrid() throws Exception {
        Path root = fixture("cap");
        Path cpu = root.resolve("devices/system/cpu");
        Files.createDirectories(cpu);
        for (int i = 0; i < 8; i++) {
            Path d = cpu.resolve("cpu" + i);
            Files.createDirectories(d);
            Files.writeString(d.resolve("cpu_capacity"), (i < 4 ? "1024" : "512") + "\n");
        }
        BitSet b = Cores.performanceLinux(root);
        for (int i = 0; i < 4; i++) assert b.get(i);
        for (int i = 4; i < 8; i++) assert !b.get(i);
    }

    static void coresNotHybridPlacesNothing() throws Exception {
        Path root = fixture("noth");
        Path cpu = root.resolve("devices/system/cpu");
        Files.createDirectories(cpu);
        for (int i = 0; i < 12; i++) {
            Path d = cpu.resolve("cpu" + i);
            Files.createDirectories(d);
            Files.writeString(d.resolve("cpu_capacity"), "1024\n");
        }
        assert Cores.performanceLinux(root) == null;
    }

    static void coresMissingSysfsOff() throws Exception {
        Path root = fixture("missing");
        assert Cores.performanceLinux(root) == null;
    }

    static void coresOffStock() {
        Settings.load(SelfTest.write("enabled=true\n"));
        Cores.resetForTest();
        Cores.init();
        assert !Cores.guard.on;
        assert Cores.nativeLookups == 0;
        Cores.resetForTest();
    }

    static void coresNativeLookupsOff() {
        // with key off, no native lookup (already covered); with not-hybrid but key on, detection returns null before pinning; no native lookup
        Settings.load(SelfTest.write("enabled=true\ncorePlacement=on\n"));
        Cores.resetForTest();
        Cores.init();
        assert Cores.nativeLookups == 0;
        Cores.resetForTest();
        Settings.load(SelfTest.write("enabled=true\n"));
    }

    static void coresPinSelfFullSet() throws Exception {
        int n = Runtime.getRuntime().availableProcessors();
        BitSet all = new BitSet();
        for (int i = 0; i < n; i++) all.set(i);
        assert Cores.pinSelf(all);
    }

    static void coresCapacity() {}

    static void coresWindowsCpuSetParse() {
        try (Arena arena = Arena.ofConfined()) {
            int entrySize = 32;
            MemorySegment buf = arena.allocate(entrySize * 4L);
            int[] ids = new int[]{256, 257, 258, 259};
            int[] eff = new int[]{1, 1, 0, 0};
            for (int i = 0; i < 4; i++) {
                long off = (long) i * entrySize;
                buf.set(JAVA_INT, off + 0, entrySize);
                buf.set(JAVA_INT, off + 4, 0);
                buf.set(JAVA_INT, off + 8, ids[i]);
                buf.set(JAVA_BYTE, off + 18, (byte) eff[i]);
            }
            int[] r = Cores.performanceIdsWindows(buf, entrySize * 4L);
            assert r != null && r.length == 2 : "expected 2 perf ids";
            assert r[0] == 256 && r[1] == 257 : "wrong ids";
            // all efficiency class 0 -> null
            MemorySegment buf2 = arena.allocate(entrySize * 4L);
            for (int i = 0; i < 4; i++) {
                long off = (long) i * entrySize;
                buf2.set(JAVA_INT, off + 0, entrySize);
                buf2.set(JAVA_INT, off + 4, 0);
                buf2.set(JAVA_INT, off + 8, 256 + i);
                buf2.set(JAVA_BYTE, off + 18, (byte) 0);
            }
            assert Cores.performanceIdsWindows(buf2, entrySize * 4L) == null;
        }
    }
}