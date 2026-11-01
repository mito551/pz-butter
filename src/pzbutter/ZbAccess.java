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

    /**
     * Re-applies ZombieBuddy's patches to the holder targets already loaded when PZ Butter's main runs. Classes loaded in
     * an early startup window get neither ZombieBuddy's new-load transform nor its retransform pass, so their holders never
     * install (2026-10-28/31: BaseVehicle, AnimationPlayer, LuaManager$GlobalObject and others; which ones varies run to run).
     * A JVM retransform runs ZombieBuddy's transformer on them again. Returns how many classes were retransformed.
     */
    static int retransformLoadedTargets() {
        Instrumentation inst = instrumentation();
        if (inst == null) return -1;
        var targets = new java.util.HashSet<String>();
        for (ModCompat.Target t : ModCompat.ours()) targets.add(t.className());
        retransformTargets = targets;
        return retransform(inst);
    }

    /** The holder targets' class names, and the ones already re-applied (one entry per class). */
    private static java.util.Set<String> retransformTargets;
    private static final java.util.Set<String> retransformed = new java.util.HashSet<>();
    /** Second pass pending: classes that load while the first pass runs miss their new-load patch as well. */
    static volatile boolean secondPass;

    private static int retransform(Instrumentation inst) {
        int n = 0;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (!retransformTargets.contains(c.getName()) || retransformed.contains(c.getName()) || !inst.isModifiableClass(c)) continue;
            retransformed.add(c.getName());
            try { inst.retransformClasses(c); n++; }
            catch (Throwable t) { System.out.println("[PZButter] patches: retransform " + c.getName() + " failed: " + t); }
        }
        secondPass = true;
        return n;
    }

    /** First frame (main thread): re-apply to the targets loaded while the first pass ran. One flag check afterwards. */
    static void retransformSecondPass() {
        if (!secondPass) return;
        secondPass = false;
        try {
            Instrumentation inst = instrumentation();
            if (inst == null || retransformTargets == null) return;
            int n = retransform(inst);
            secondPass = false;
            System.out.println("[PZButter] patches: re-applied to " + n + " more classes at the first frame");
        } catch (Throwable t) {
            System.out.println("[PZButter] patches: second pass failed: " + t);
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
