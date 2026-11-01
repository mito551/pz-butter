package pzbutter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.ToLongFunction;

/** Plan 11b Task 2: vehicle zones de-duplicated in one pass (loaderCpuFixes, vehicle-zone part). */
public final class VehicleZones {
    private VehicleZones() {}

    public static final Guard guard = new Guard("loaderCpuFixes");

    /** Key for the compared fields (x, y, h, w) of one zone. */
    record Key(long xy, long wh) {}

    /**
     * Removes exactly the zones stock's restart-from-zero loop removes, in a single pass. {@code debug} is called
     * {@code debug.accept(removed, kept)} in stock's order, and only when non-null, so the string is built only when
     * the caller has debug on.
     */
    public static <Z> void dedupe(List<Z> zones, ToLongFunction<Z> keyXY, ToLongFunction<Z> keyWH, BiConsumer<Z, Z> debug) {
        if (zones == null || zones.size() < 2) return;
        HashMap<Key, Z> first = new HashMap<>();
        ArrayList<Z> kept = new ArrayList<>(zones.size());
        for (Z z : zones) {
            Key k = new Key(keyXY.applyAsLong(z), keyWH.applyAsLong(z));
            Z survivor = first.get(k);
            if (survivor == null && !first.containsKey(k)) {
                first.put(k, z);
                kept.add(z);
            } else {
                Z firstZone = first.get(k);
                if (debug != null) debug.accept(z, firstZone);
            }
        }
        zones.clear();
        zones.addAll(kept);
    }

    static void init() {
        guard.on = Settings.flag("loaderCpuFixes");
    }

    static void resetForTest() {
        guard.on = false;
    }
}