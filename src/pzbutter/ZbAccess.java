package pzbutter;

import java.lang.instrument.Instrumentation;

/** The one place that reads ZombieBuddy's internal Instrumentation field. Null when it is not there. */
final class ZbAccess {
    private ZbAccess() {}

    static Instrumentation instrumentation() { return instrumentation("me.zed_0xff.zombie_buddy.Loader"); }

    static Instrumentation instrumentation(String loaderClassName) {
        try {
            var f = Class.forName(loaderClassName, false, ClassLoader.getSystemClassLoader()).getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            return f.get(null) instanceof Instrumentation i ? i : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The set of mod ids in ZombieBuddy's current mod set, or null when it cannot be read. */
    static java.util.Set<String> modSet() { return modSet("me.zed_0xff.zombie_buddy.Loader"); }

    static java.util.Set<String> modSet(String loaderClassName) {
        try {
            var f = Class.forName(loaderClassName, false, ClassLoader.getSystemClassLoader()).getDeclaredField("g_curr_modSet");
            f.setAccessible(true);
            Object o = f.get(null);
            if (!(o instanceof java.util.Set<?> s)) return null;
            java.util.Set<String> out = new java.util.HashSet<>();
            for (Object x : s) if (x != null) out.add(String.valueOf(x));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
