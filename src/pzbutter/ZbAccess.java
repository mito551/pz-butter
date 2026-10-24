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
}
