package pzbutter;

import java.lang.reflect.Field;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;

/** findItemOnce: FindItem's module loop can only repeat the pre-loop lookup (the loop targets the
 * same module every iteration), so the pre-loop result is the whole answer. */
public final class ScriptTest {
    private ScriptTest() {}

    static void run() throws Exception {
        defaultsGuard();
        missPathsEqualStock();
        fullTypeHit();
        keptDefaults();
        keptMatchesStockAndInvalidates();
        missMemo();
    }

    // ---- findItemMiss: a missed name is remembered per generation (cleared on scripts reload, the gen hooks) ----

    static void missMemo() throws Exception {
        Settings.load(SelfTest.write("enabled=true\nfindItemOnce=true\nfindItemMiss=true\n"));
        ScriptPatches.initOnce();
        ScriptPatches.initKept();
        ScriptPatches.initMiss();
        check("miss on, kept off", ScriptPatches.missGuard.on && !ScriptPatches.keptGuard.on);

        ScriptManager m = new ScriptManager(); // empty mod list: every lookup misses
        ScriptPatches.missed.clear();
        check("first lookup of a missing name is null", ScriptPatches.findMiss(m, "plain", true) == null);
        check("the miss is memoised", ScriptPatches.missed.containsKey(new ScriptPatches.Key("plain", true)));
        check("repeat answers null too", ScriptPatches.findMiss(m, "plain", true) == null);

        Item probe = new Item();
        ScriptBucketCollectionBridge.putFullType(m, "probe.item", probe);
        ScriptPatches.gen++; // the script-reload hooks bump the generation; the memo entry is stale
        check("a stale miss does not hide a new item", ScriptPatches.findMiss(m, "probe.item", true) == probe);
        check("a hit is never memoised", !ScriptPatches.missed.containsKey(new ScriptPatches.Key("probe.item", true)));

        Settings.load(SelfTest.write("enabled=true\nfindItemKept=true\nfindItemMiss=true\n"));
        ScriptPatches.initKept();
        ScriptPatches.initMiss();
        check("with kept on, the miss memory is left to it", ScriptPatches.findMiss(m, "probe.item", true) == probe
                && !ScriptPatches.missed.containsKey(new ScriptPatches.Key("probe.item", true)));

        Settings.load(SelfTest.write("enabled=true\n"));
        ScriptPatches.initOnce();
        ScriptPatches.initKept();
        ScriptPatches.initMiss();
        check("defaults off", !ScriptPatches.missGuard.on && !ScriptPatches.keptGuard.on);
    }

    static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }

    static void defaultsGuard() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n" + "findItemOnce=true\n"));
        ScriptPatches.initOnce();
        check("findOnce respects the flag", ScriptPatches.onceGuard.on);
        Settings.load(SelfTest.write("enabled=true\n" + "findItemOnce=false\n"));
        ScriptPatches.initOnce();
        check("findItemOnce=false leaves the guard off", !ScriptPatches.onceGuard.on);
        Settings.load(SelfTest.write("enabled=true\n"));
        ScriptPatches.initOnce();
        check("findItemOnce default is on", ScriptPatches.onceGuard.on);
    }

    static void missPathsEqualStock() throws Exception {
        ScriptManager m = new ScriptManager();
        for (String name : new String[] {"plain", "a.b", "Base.bogus", "", "Base"}) {
            for (boolean flag : new boolean[] {true, false}) {
                check("findOnce matches stock for " + name + " flag " + flag,
                        ScriptPatches.findOnce(m, name, flag) == m.FindItem(name, flag));
            }
        }
        check("plain call matches stock", ScriptPatches.findOnce(m, "plain", true) == m.FindItem("plain"));
    }

    static void fullTypeHit() throws Exception {
        ScriptManager m = new ScriptManager();
        Item probe = new Item();
        ScriptBucketCollectionBridge.putFullType(m, "probe.item", probe);
        check("findOnce resolves a full type", ScriptPatches.findOnce(m, "probe.item", true) == probe);
        check("stock resolves the same full type", m.FindItem("probe.item", true) == probe);
    }

    static void keptDefaults() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n" + "findItemKept=true\n"));
        ScriptPatches.initKept();
        check("findItemKept template turns the cache on", ScriptPatches.keptGuard.on);
        Settings.load(SelfTest.write("enabled=true\n"));
        ScriptPatches.initKept();
        check("findItemKept default is off", !ScriptPatches.keptGuard.on);
    }

    static void keptMatchesStockAndInvalidates() throws Exception {
        Settings.load(SelfTest.write("enabled=true\n" + "findItemKept=true\n"));
        ScriptPatches.initKept();
        ScriptPatches.kept.clear();
        ScriptManager m = new ScriptManager();
        Item probe = new Item();
        ScriptBucketCollectionBridge.putFullType(m, "probe.item", probe);
        check("kept first call matches stock", ScriptPatches.findKept(m, "probe.item", true) == probe);
        check("kept repeat is the cached answer", ScriptPatches.findKept(m, "probe.item", true) == probe);
        ScriptBucketCollectionBridge.dropFullType(m, "probe.item");
        check("kept keeps its answer until invalidation", ScriptPatches.findKept(m, "probe.item", true) == probe);
        ScriptPatches.gen++;
        check("a generation bump drops kept answers", ScriptPatches.findKept(m, "probe.item", true) == null);
        ScriptBucketCollectionBridge.putFullType(m, "probe.item", probe);
        ScriptPatches.gen++;
        check("kept recomputes after invalidation", ScriptPatches.findKept(m, "probe.item", true) == probe);
        ScriptBucketCollectionBridge.dropFullType(m, "probe.item");
        m.moduleList.add(new zombie.scripting.objects.ScriptModule());
        check("an item or module count change drops kept answers", ScriptPatches.findKept(m, "probe.item", true) == null);
        Settings.load(SelfTest.write("enabled=true\n"));
        ScriptPatches.initKept();
    }
}

/** Reflection glue for the tests: seeds the fresh manager's items collection. */
final class ScriptBucketCollectionBridge {
    private ScriptBucketCollectionBridge() {}

    static Object collection(ScriptManager m) throws Exception {
        return ScriptPatches.items(m);
    }

    static void putFullType(ScriptManager m, String fullType, Item probe) throws Exception {
        Object items = ScriptPatches.items(m);
        Field f = items.getClass().getSuperclass().getDeclaredField("fullTypeToScriptMap");
        f.setAccessible(true);
        java.util.HashMap<String, Item> map = (java.util.HashMap<String, Item>) f.get(items);
        map.put(fullType, probe);
    }

    static void dropFullType(ScriptManager m, String fullType) throws Exception {
        Object items = ScriptPatches.items(m);
        Field f = items.getClass().getSuperclass().getDeclaredField("fullTypeToScriptMap");
        f.setAccessible(true);
        java.util.HashMap<String, Item> map = (java.util.HashMap<String, Item>) f.get(items);
        map.remove(fullType);
    }
}