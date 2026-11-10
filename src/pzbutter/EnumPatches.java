package pzbutter;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Task 13 (enumParseCache): {@code StringUtils.tryParseEnum} and the NetworkVariables {@code fromString}
 * family answer from hash maps of the enum names / field values instead of a throw-caught
 * {@code Enum.valueOf} or a linear scan; same answer for a name that exists and for one that does not.
 */
public final class EnumPatches {
    private EnumPatches() {}

    public static final Guard guard = new Guard("enumParseCache");

    static void init() {
        try {
            guard.on = Settings.flag("enumParseCache");
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    static final ConcurrentHashMap<Class<?>, HashMap<String, Object>> NAME = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Class<?>, HashMap<String, Object>> FIELD = new ConcurrentHashMap<>();

    /** ValueOf-shaped lookup: exact names, miss (or null name) answers with the stock default. */
    public static Object byName(Class<?> type, String name, Object def) {
        try {
            HashMap<String, Object> m = NAME.get(type);
            if (m == null) {
                m = new HashMap<>();
                for (Object c : type.getEnumConstants()) m.put(((Enum<?>) c).name(), c);
                NAME.putIfAbsent(type, m);
                m = NAME.get(type);
            }
            return name == null ? def : m.getOrDefault(name, def);
        } catch (Throwable t) {
            guard.fail(t);
            return def;
        }
    }

    /**
     * fromString-shaped lookup over a public String field (the NetworkVariables family). Inputs are
     * lower-cased once (all three stock paths do). Keys keep the field value as-is unless
     * {@code lowerKeys} (the equalsIgnoreCase pair), where the first constant in values() order wins.
     */
    public static Object byField(Class<?> type, String field, boolean lowerKeys, String input, Object def) {
        try {
            HashMap<String, Object> m = FIELD.get(type);
            if (m == null) {
                Field f = type.getDeclaredField(field);
                f.setAccessible(true);
                m = new HashMap<>();
                for (Object c : type.getEnumConstants()) {
                    String v = (String) f.get(c);
                    m.putIfAbsent(lowerKeys ? v.toLowerCase() : v, c);
                }
                FIELD.putIfAbsent(type, m);
                m = FIELD.get(type);
            }
            return m.getOrDefault(input == null ? null : input.toLowerCase(), def);
        } catch (Throwable t) {
            guard.fail(t);
            return def;
        }
    }

    @Patch(className = "zombie.util.StringUtils", methodName = "tryParseEnum", strictMatch = true)
    public static class TryParse {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) Class<?> type, @Patch.Argument(1) String name,
                                    @Patch.Argument(2) Object def, @Patch.Local("r") Object r) {
            if (!guard.on) return false;
            try {
                r = byName(type, name, def);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) Enum<?> ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (Enum<?>) r;
        }
    }

    @Patch(className = "zombie.network.NetworkVariables$ThumpType", methodName = "fromString")
    public static class ThumpParse {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String s, @Patch.Local("r") Object r) {
            if (!guard.on) return false;
            try {
                r = byField(zombie.network.NetworkVariables.ThumpType.class, "thumpType", true, s,
                    zombie.network.NetworkVariables.ThumpType.TTNone);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) zombie.network.NetworkVariables.ThumpType ret,
                                @Patch.Local("r") Object r) {
            if (r != null) ret = (zombie.network.NetworkVariables.ThumpType) r;
        }
    }

    @Patch(className = "zombie.network.NetworkVariables$WalkType", methodName = "fromString")
    public static class WalkParse {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String s, @Patch.Local("r") Object r) {
            if (!guard.on) return false;
            try {
                r = byField(zombie.network.NetworkVariables.WalkType.class, "walkType", true, s,
                    zombie.network.NetworkVariables.WalkType.WT1);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) zombie.network.NetworkVariables.WalkType ret,
                                @Patch.Local("r") Object r) {
            if (r != null) ret = (zombie.network.NetworkVariables.WalkType) r;
        }
    }

    @Patch(className = "zombie.network.NetworkVariables$ZombieState", methodName = "fromString")
    public static class ZombieParse {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) String s, @Patch.Local("r") Object r) {
            if (!guard.on) return false;
            try {
                r = byField(zombie.network.NetworkVariables.ZombieState.class, "zombieState", false, s,
                    zombie.network.NetworkVariables.ZombieState.Idle);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) zombie.network.NetworkVariables.ZombieState ret,
                                @Patch.Local("r") Object r) {
            if (r != null) ret = (zombie.network.NetworkVariables.ZombieState) r;
        }
    }
}
