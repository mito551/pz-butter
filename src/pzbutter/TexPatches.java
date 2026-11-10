package pzbutter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Texture-table race hooks: the collector and the game's texture-table writers share one lock, and the
 * collector scans an ordered snapshot instead of the live table (chatIconsRace).
 */
public final class TexPatches {
    private TexPatches() {}

    public static final Guard guard = new Guard("chatIconsRace");

    /** Shared by collector and writers; nothing else may run inside it. */
    public static final Object LOCK = new Object();

    static void init() {
        try {
            guard.on = Settings.flag("chatIconsRace");
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    /** The game's shared texture table (s_sharedTextureTable, final: one map for the whole run). */
    @SuppressWarnings("unchecked")
    public static HashMap<String, Object> table() throws Exception {
        Field f = zombie.core.textures.Texture.class.getDeclaredField("s_sharedTextureTable");
        f.setAccessible(true);
        return (HashMap<String, Object>) f.get(null);
    }

    private static HashSet<Object> nullTextures() throws Exception {
        Field f = zombie.core.textures.Texture.class.getDeclaredField("nullTextures");
        f.setAccessible(true);
        return (HashSet<Object>) f.get(null);
    }

    /** Texture.collectAllIcons over an ordered snapshot of the table; true = the maps are filled. */
    public static boolean collectAllIconsSnapshot(HashMap<String, String> names, HashMap<String, String> icons) {
        try {
            ArrayList<String> keys;
            synchronized (LOCK) {
                keys = new ArrayList<>(table().keySet());
            }
            for (String key : keys) {
                if (!key.startsWith("media/ui/Container_") && !key.startsWith("Item_")) continue;
                String name;
                if (key.startsWith("Item_")) {
                    name = key.replaceFirst("Item_", "");
                } else {
                    name = key.replaceFirst("media/ui/Container_", "").replaceAll("\\.png", "");
                    zombie.debug.DebugType.General.debugln("Adding %s, value = %s", name.toLowerCase(), key);
                }
                names.put(name.toLowerCase(), name);
                icons.put(name.toLowerCase(), key);
            }
            return true;
        } catch (Throwable t) {
            guard.fail(t);
            return false;
        }
    }

    /** setSharedTextureInternal body under the collector's lock; true = the put happened. */
    public static boolean putSharedUnderLock(String name, Object tex) {
        try {
            synchronized (LOCK) {
                table().put(name, tex);
            }
            return true;
        } catch (Throwable t) {
            guard.fail(t);
            return false;
        }
    }

    /** onTexturePacksChanged body under the collector's lock; true = the clears happened. */
    public static boolean clearSharedUnderLock() {
        try {
            synchronized (LOCK) {
                nullTextures().clear();
                table().clear();
            }
            return true;
        } catch (Throwable t) {
            guard.fail(t);
            return false;
        }
    }

    @Patch(className = "zombie.core.textures.Texture", methodName = "collectAllIcons")
    public static class CollectIcons {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) HashMap<String, String> names,
                                    @Patch.Argument(1) HashMap<String, String> icons) {
            if (!guard.on) return false;
            return TexPatches.collectAllIconsSnapshot(names, icons);
        }
    }

    @Patch(className = "zombie.core.textures.Texture", methodName = "setSharedTextureInternal")
    public static class SetShared {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String name, @Patch.Argument(1) zombie.core.textures.Texture tex) {
            if (!guard.on) return false;
            return TexPatches.putSharedUnderLock(name, tex);
        }
    }

    @Patch(className = "zombie.core.textures.Texture", methodName = "onTexturePacksChanged")
    public static class PacksChanged {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            if (!guard.on) return false;
            return TexPatches.clearSharedUnderLock();
        }
    }
}
