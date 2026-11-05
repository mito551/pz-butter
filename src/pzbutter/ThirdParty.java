package pzbutter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/** Fix-mod switch (Plan 19: a fix is on when its PZButter_<Target> mod is enabled) and the KI5 part-mod throttle helpers (SPEC 6.8.1). */
public final class ThirdParty {
    static final Guard guard = new Guard("ki5PartModThrottle");
    static final long WINDOW_MS = 100;
    static volatile int everyDepth;
    static volatile Boolean testOn;

    private static final Map<Long, Long> allowed = new LinkedHashMap<>();
    private static final Map<Long, Pending> pending = new LinkedHashMap<>();
    // Test seam: endEvery() calls this once per suppressed (vehicle, part).
    static BiConsumer<BaseVehicle, VehiclePart> sender = (v, p) -> v.transmitPartModData(p);

    private ThirdParty() {}

    /** The game's activated mods: LuaManager.GlobalObject.getActivatedMods() returns this same list. Test seam. */
    static java.util.function.Supplier<java.util.List<String>> modIds = () -> zombie.ZomboidFileSystem.instance.getModIDs();
    private static volatile java.util.Set<String> activeIds;

    /** True when mod {@code modId} is enabled. The list is read once per Lua load (LuaPatches.onLuaLoad resets it). */
    public static boolean active(String modId) {
        java.util.Set<String> ids = activeIds;
        if (ids == null) {
            try {
                ids = new java.util.HashSet<>();
                for (String id : modIds.get()) if (id != null) ids.add(id.startsWith("\\") ? id.substring(1) : id);
            } catch (Throwable t) {
                return false;   // not readable yet (before the file system starts): read again on the next call
            }
            activeIds = ids;
        }
        return ids.contains(modId);
    }

    static void resetActive() { activeIds = null; }

    static boolean on() {
        Boolean t = testOn;
        return t != null ? t : Settings.flag("ki5PartModThrottle");
    }

    public static void beginEvery() {
        int d = everyDepth;
        everyDepth = d < 1000 ? d + 1 : d;
    }

    public static void endEvery() {
        int d = everyDepth;
        if (d > 0) everyDepth = d - 1;
        if (everyDepth == 0) flush();
    }

    /** True when the call may go through; false when it is suppressed for this window. */
    public static boolean partModGate(long vehicleId, int part, long nowMs) {
        try {
            if (everyDepth <= 0 || !on()) return true;
            long key = key(vehicleId, part);
            Long at = allowed.get(key);
            if (at == null || nowMs - at >= WINDOW_MS || nowMs < at) {
                allowed.put(key, nowMs);
                return true;
            }
            return false;
        } catch (Throwable t) {
            guard.fail(t);
            return true;
        }
    }

    /** Remember a suppressed call so the last state can be sent when the window closes. */
    public static void notePart(long vehicleId, int part, BaseVehicle v, VehiclePart p) {
        try {
            pending.put(key(vehicleId, part), new Pending(v, p));
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    static void flush() {
        if (pending.isEmpty()) return;
        Map<Long, Pending> copy = new LinkedHashMap<>(pending);
        pending.clear();
        for (Pending p : copy.values()) {
            try {
                if (p.v != null && p.part != null) sender.accept(p.v, p.part);
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    static long key(long vehicleId, int part) {
        return (vehicleId << 32) ^ (part & 0xffffffffL);
    }

    static int pendingForTest() {
        return pending.size();
    }

    static void resetForTest() {
        resetActive();
        modIds = () -> zombie.ZomboidFileSystem.instance.getModIDs();
        everyDepth = 0;
        testOn = null;
        allowed.clear();
        pending.clear();
        sender = (v, p) -> v.transmitPartModData(p);
    }

    private static final class Pending {
        final BaseVehicle v;
        final VehiclePart part;

        Pending(BaseVehicle v, VehiclePart part) {
            this.v = v;
            this.part = part;
        }
    }
}
