package pzbutter;

import java.lang.reflect.Field;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.scripting.ScriptBucketCollection;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.Item;
import zombie.scripting.objects.ScriptModule;

/**
 * Script engine hooks. ScriptManager.FindItem's module loop never targets the iterated module
 * (only the pre-loop module), so its result cannot differ from the pre-loop one.
 */
public final class ScriptPatches {
    private ScriptPatches() {}

    public static final Guard onceGuard = new Guard("findItemOnce");

    static void initOnce() {
        try {
            onceGuard.on = Settings.flag("findItemOnce");
        } catch (Throwable t) {
            onceGuard.fail(t);
        }
    }

    static Field itemsField;

    static ScriptBucketCollection<?> items(ScriptManager m) throws Exception {
        if (itemsField == null) {
            Field f = ScriptManager.class.getDeclaredField("items");
            f.setAccessible(true);
            itemsField = f;
        }
        return (ScriptBucketCollection<?>) itemsField.get(m);
    }

    /** Stock FindItem(path, defaultsToBase) minus the loop, which can only return the pre-loop answer. */
    public static Item findOnce(ScriptManager m, String name, boolean defaultsToBase) throws Exception {
        if (name.contains(".")) {
            ScriptBucketCollection<?> items = items(m);
            if (items.hasFullType(name)) {
                return (Item) items.getFullType(name);
            }
        }
        ScriptModule mod = m.getModule(name, defaultsToBase);
        if (mod == null) return null;
        return mod.getItem(ScriptManager.getItemName(name));
    }

    // findItemKept: the kept cache. Answers live per (name, defaultsToBase) and are dropped when
    // the generation counter moves (bumped by every writer of the tables the lookup reads) or
    // when the item or module counts change.

    public static final Guard keptGuard = new Guard("findItemKept");
    static volatile long gen;
    static final Object MISS = new Object();
    record Key(String name, boolean base) {}
    record Kept(long gen, long counts, Object item) {}
    static final java.util.concurrent.ConcurrentHashMap<Key, Kept> kept = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.ConcurrentHashMap<ScriptManager, ScriptBucketCollection<?>> itemsMemo =
            new java.util.concurrent.ConcurrentHashMap<>();
    static Field allScriptsField;
    public static final Object LOCK = new Object();

    static void initKept() {
        try {
            keptGuard.on = Settings.flag("findItemKept");
        } catch (Throwable t) {
            keptGuard.fail(t);
        }
    }

    static ScriptBucketCollection<?> itemsMemo(ScriptManager m) throws Exception {
        ScriptBucketCollection<?> got = itemsMemo.get(m);
        if (got != null) return got;
        ScriptBucketCollection<?> items = items(m);
        itemsMemo.put(m, items);
        return items;
    }

    static long counts(ScriptManager m, ScriptBucketCollection<?> items) throws Exception {
        if (allScriptsField == null) {
            Field f = ScriptBucketCollection.class.getDeclaredField("allScripts");
            f.setAccessible(true);
            allScriptsField = f;
        }
        return ((long) ((java.util.List<?>) allScriptsField.get(items)).size() << 32) | m.moduleList.size();
    }

    /** Kept answer of the findOnce lookup for (name, defaultsToBase). */
    public static Item findKept(ScriptManager m, String name, boolean defaultsToBase) throws Exception {
        Key k = new Key(name, defaultsToBase);
        ScriptBucketCollection<?> items = itemsMemo(m);
        for (;;) {
            Kept e = kept.get(k);
            long g = gen;
            if (e != null && e.gen == g && e.counts == counts(m, items)) {
                return e.item == MISS ? null : (Item) e.item;
            }
            Item item = findOnce(m, name, defaultsToBase);
            kept.put(k, new Kept(g, counts(m, items), item == null ? MISS : item));
            if (gen == g) return item;
        }
    }

    public static void bumpGen() {
        synchronized (LOCK) { gen++; }
    }

    @Patch(className = "zombie.scripting.ScriptManager", methodName = "FindItem", strictMatch = true)
    public static class FindItemOnce {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) String name,
                                    @Patch.Argument(1) boolean defaultsToBase,
                                    @Patch.Local("r") Item r) {
            if (!onceGuard.on) return false;
            try {
                r = keptGuard.on ? findKept((ScriptManager) self, name, defaultsToBase)
                        : findOnce((ScriptManager) self, name, defaultsToBase);
                return true;
            } catch (Throwable t) {
                onceGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) Item ret, @Patch.Local("r") Item r) {
            if (r != null) ret = r;
        }
    }

    @Patch(className = "zombie.scripting.ScriptManager", methodName = "Reset")
    public static class SmReset {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }

    @Patch(className = "zombie.scripting.ScriptManager", methodName = "ParseScript")
    public static class SmParseScript {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }

    @Patch(className = "zombie.scripting.objects.ScriptModule", methodName = "Reset")
    public static class ModReset {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }

    @Patch(className = "zombie.scripting.objects.ScriptModule", methodName = "Load")
    public static class ModLoad {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }

    @Patch(className = "zombie.scripting.ScriptBucket", methodName = "reset")
    public static class BucketReset {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }

    @Patch(className = "zombie.scripting.ScriptBucket", methodName = "LoadScripts")
    public static class BucketLoadScripts {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }

    @Patch(className = "zombie.scripting.ScriptBucketCollection", methodName = "reset")
    public static class CollReset {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }

    @Patch(className = "zombie.scripting.ScriptBucketCollection", methodName = "LoadScripts")
    public static class CollLoadScripts {
        @Patch.OnEnter
        public static void enter() { bumpGen(); }
        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() { bumpGen(); }
    }
}
