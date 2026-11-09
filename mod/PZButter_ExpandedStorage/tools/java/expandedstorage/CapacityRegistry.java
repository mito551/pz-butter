package expandedstorage;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import zombie.inventory.ItemContainer;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;

/** Sprite name -> container capacity, applied to containers as their objects are created or loaded. */
public final class CapacityRegistry {
    // Concurrent: chunk loading calls IsoObject.load from worker threads.
    static final Map<String, Integer> CAPACITY_BY_SPRITE = new ConcurrentHashMap<>();

    // Set once the patched game code has actually reached us; Lua uses it to stand down.
    private static volatile boolean seen;

    static {
        try (InputStream in = CapacityRegistry.class.getResourceAsStream("/expandedstorage/capacities.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                for (Map.Entry<Object, Object> e : props.entrySet()) {
                    try {
                        CAPACITY_BY_SPRITE.put(String.valueOf(e.getKey()), Integer.parseInt(String.valueOf(e.getValue()).trim()));
                    } catch (NumberFormatException bad) {
                        System.err.println("[ExpandedStorageZB] bad capacity for " + e.getKey() + ": " + e.getValue());
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (CAPACITY_BY_SPRITE.isEmpty()) {
            System.err.println("[ExpandedStorageZB] no capacities loaded; Lua fallback stays active");
        }
    }

    private CapacityRegistry() {}

    /** True once the game hook has run and there is something to apply. */
    public static boolean active() {
        return seen && !CAPACITY_BY_SPRITE.isEmpty();
    }

    public static void apply(IsoObject object) {
        if (!seen) seen = true;

        // Most objects have no container: bail out before any string work.
        ItemContainer container = object.getContainer();
        if (container == null) return;

        IsoSprite sprite = object.getSprite();
        if (sprite == null) return;

        String name = sprite.getName();
        if (name == null) return;

        Integer capacity = CAPACITY_BY_SPRITE.get(name);
        if (capacity != null && container.getCapacity() != capacity) {
            container.setCapacity(capacity);
        }
    }
}
