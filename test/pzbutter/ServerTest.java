package pzbutter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import zombie.characters.animals.AnimalDefinitions;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoObject;

/** Server tick log: frames.out columns, a complete last line after stop, and the stage on a slow tick. */
final class ServerTest {
    private ServerTest() {}

    static void run() {
        tickLineFormat();
        tickFlushOnStop();
        stageOnSlowTick();
        sendGuardSkipsOnlyBroken();
        mergeSameAsStock();
        prewarmNames();
    }

    private static void tickLineFormat() {
        File f = SelfTest.tmp("server/ticks.out");
        ServerTick.resetForTest();
        ServerTick.openLog(f);
        ServerTick.beginAt(1_000_000_000L, 0L);
        ServerTick.endAt(1_016_000_000L, 8_000_000L);      // 16 ms tick, first interval 0
        ServerTick.beginAt(1_032_000_000L, 0L);
        ServerTick.endAt(1_048_000_000L, 7_500_000L);      // interval 32 ms, step 16 ms
        ServerTick.flush();

        String[] lines = text(f).split("\n");
        assert lines.length == 2 : text(f);
        String[] a = lines[0].split(" ");
        assert a.length == 5 : lines[0];
        assert Long.parseLong(a[0]) > 0;
        assert Long.parseLong(a[1]) == 1;
        assert Float.parseFloat(a[2]) == 0f;
        assert Float.parseFloat(a[3]) == 16f;
        assert Float.parseFloat(a[4]) == 8f;
        String[] b = lines[1].split(" ");
        assert Long.parseLong(b[1]) == 2;
        assert Float.parseFloat(b[2]) == 32f && Float.parseFloat(b[3]) == 16f && Float.parseFloat(b[4]) == 7.5f;
        ServerTick.stop();
    }

    private static void tickFlushOnStop() {
        File f = SelfTest.tmp("server/stop.out");
        ServerTick.resetForTest();
        ServerTick.openLog(f);
        ServerTick.beginAt(2_000_000_000L, 0L);
        ServerTick.endAt(2_005_000_000L, 1_000_000L);
        ServerTick.stop();   // no flush call, no one-second mark
        String s = text(f);
        assert s.endsWith("\n") : "last line incomplete: " + s;
        assert s.split("\n").length == 1 : s;
    }

    private static void stageOnSlowTick() {
        File f = SelfTest.tmp("server/stage.out");
        ServerTick.resetForTest();
        ServerTick.openLog(f);
        ServerTick.beginAt(3_000_000_000L, 0L);
        ServerTick.netDoneAt(3_120_000_000L);               // 120 of 130 ms before preupdate
        ServerTick.endAt(3_130_000_000L, 100_000_000L);
        ServerTick.stop();
        String s = text(f);
        assert s.contains("stage=net 120.000") : s;
    }

    private static void sendGuardSkipsOnlyBroken() {
        ServerFixes.resetForTest();
        ItemContainer c = new ItemContainer();
        assert !ServerFixes.sendable(c);
        assert !ServerFixes.sendable(null);
        assert ServerFixes.skipSend(c);
        assert ServerFixes.skipped() == 1;
        // the same container becomes sendable the moment it gains a parent: the next call is not skipped
        c.parent = new IsoObject();
        assert ServerFixes.sendable(c);
        assert !ServerFixes.skipSend(c);
        assert ServerFixes.skipped() == 1;
    }

    private static void mergeSameAsStock() {
        assert !ServerFixes.batch(127) && ServerFixes.batch(128);
        List<String> base = new ArrayList<>();
        for (int i = 0; i < 120; i++) base.add("old" + i);
        List<String> add = new ArrayList<>();
        for (int i = 0; i < 30; i++) add.add(base.get(i * 3));      // already present
        for (int i = 0; i < 167; i++) add.add("new" + i);
        for (int i = 0; i < 10; i++) add.add(base.get(100 + i));
        add.add(50, null);
        add.add(100, null);
        add.add(150, null);

        Set<String> remove = new LinkedHashSet<>();
        for (int i = 0; i < 10; i++) remove.add(add.get(i * 7));
        for (int i = 0; i < 5; i++) remove.add(base.get(i));

        List<String> stockList = new ArrayList<>(base);
        Set<String> stockRemove = new LinkedHashSet<>(remove);
        for (String it : add) {
            if (it == null) continue;
            stockRemove.remove(it);
            if (!stockList.contains(it)) stockList.add(it);
        }

        List<String> merged = new ArrayList<>(base);
        Set<String> mergedRemove = new LinkedHashSet<>(remove);
        ServerFixes.mergeLists(merged, add, mergedRemove);

        assert merged.equals(stockList) : "order differs";
        assert mergedRemove.equals(stockRemove) : "remove set differs";
    }

    private static void prewarmNames() {
        List<String> names = ServerFixes.prewarmNames();
        for (String core : new String[] {"player", "player-vehicle", "zombie", "zombie-crawler"})
            assert names.contains(core) : core;
        assert names.size() == new LinkedHashSet<>(names).size() : "duplicate name";
        try {
            ArrayList<AnimalDefinitions> defs = AnimalDefinitions.getAnimalDefsArray();
            if (defs != null) for (AnimalDefinitions d : defs)
                if (d != null && d.animset != null && !d.animset.isEmpty()) assert names.contains(d.animset) : d.animset;
        } catch (Throwable ignored) { }   // script data is not loaded in the test JVM
    }

    private static String text(File f) {
        try { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1); }
        catch (IOException e) { throw new AssertionError(e); }
    }
}
