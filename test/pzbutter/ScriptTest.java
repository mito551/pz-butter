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
}
