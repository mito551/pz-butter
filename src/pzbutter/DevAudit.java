package pzbutter;

import java.lang.instrument.Instrumentation;
import java.util.HashMap;

/**
 * Dev diagnostic, only with -Dpzbutter.patchAudit=true|retransform: for the holder targets ZombieBuddy never patched
 * (audit 2026-10-28/31), print whether each class is loaded and modifiable, at preload, at main and at world load.
 * "retransform" also asks the JVM to retransform the loaded ones at main, so ZombieBuddy's transformer sees them again.
 */
final class DevAudit {
    private DevAudit() {}

    /** The never-patched targets' classes, then two patched controls. */
    static final String[] CLASSES = {
        "zombie.vehicles.BaseVehicle", "zombie.core.skinnedmodel.animation.AnimationPlayer", "zombie.util.CappedConcurrentQueue",
        "zombie.Lua.LuaManager$GlobalObject", "zombie.iso.IsoMetaGrid", "zombie.iso.objects.IsoLightSwitch",
        "zombie.core.textures.TextureFBO", "zombie.audio.FMODParameter", "zombie.entity.util.TimSort",
        "zombie.iso.IsoGridSquare", "zombie.characters.IsoPlayer",
    };

    /** Read at the first call (preload): the game replaces the system properties later on. */
    private static String mode;
    private static boolean read;

    static void report(String where) {
        if (!read) { read = true; mode = System.getProperty("pzbutter.patchAudit"); }
        if (mode == null) return;
        try {
            Instrumentation inst = ZbAccess.instrumentation();
            if (inst == null) { System.out.println("[PZButter] patchAudit " + where + ": no ZombieBuddy Instrumentation"); return; }
            var loaded = new HashMap<String, Class<?>>();
            for (Class<?> c : inst.getAllLoadedClasses()) loaded.put(c.getName(), c);
            for (String name : CLASSES) {
                Class<?> c = loaded.get(name);
                System.out.println("[PZButter] patchAudit " + where + ": " + name + " loaded=" + (c != null)
                    + (c == null ? "" : " modifiable=" + inst.isModifiableClass(c) + " loader=" + c.getClassLoader()));
            }
            if (!"retransform".equals(mode) || !"main".equals(where)) return;
            for (String name : CLASSES) {
                Class<?> c = loaded.get(name);
                if (c == null || !inst.isModifiableClass(c)) continue;
                try { inst.retransformClasses(c); System.out.println("[PZButter] patchAudit retransform " + name + ": ok"); }
                catch (Throwable t) { System.out.println("[PZButter] patchAudit retransform " + name + ": " + t); }
            }
        } catch (Throwable t) {
            System.out.println("[PZButter] patchAudit " + where + ": " + t);
        }
    }
}
