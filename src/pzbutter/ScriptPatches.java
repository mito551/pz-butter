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

    @Patch(className = "zombie.scripting.ScriptManager", methodName = "FindItem", strictMatch = true)
    public static class FindItemOnce {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) String name,
                                    @Patch.Argument(1) boolean defaultsToBase,
                                    @Patch.Local("r") Item r) {
            if (!onceGuard.on) return false;
            try {
                r = findOnce((ScriptManager) self, name, defaultsToBase);
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
}
