package pzbutter;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.constant.ClassDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless spike for the ZombieBuddy preload path (see harness/preload-check.sh).
 * {@link #premain} is called from a throwaway preload jar's {@code <pkg>.PreMain.premain(String[])};
 * {@link #main} is the JVM main class and prints the answers. Never touches the game's main class.
 */
public final class PreloadProbe {
    static final String[] CLASSES = {
        "zombie.characters.IsoZombie", "zombie.characters.IsoGameCharacter", "zombie.iso.IsoMovingObject",
        "zombie.core.skinnedmodel.animation.AnimationPlayer", "se.krka.kahlua.integration.LuaCaller"};
    static final String TARGET = "zombie.characters.IsoZombie";
    static final String MARKER = "pzbMarker";

    static final Set<String> loadedBefore = new TreeSet<>();
    static final List<String> seenClasses = new ArrayList<>();
    static volatile boolean t1SawTarget, t2SawTarget, t2SawMarkerOnRetransform;
    static final AtomicInteger t2Calls = new AtomicInteger();
    static volatile Instrumentation inst;
    static volatile String premainError;

    /** Called reflectively-free from the throwaway jar's PreMain; must never throw. */
    public static void premain() {
        try {
            Instrumentation i = zbInstrumentation();
            inst = i;
            Set<String> names = new TreeSet<>();
            for (Class<?> c : i.getAllLoadedClasses()) names.add(c.getName());
            for (String n : CLASSES) if (names.contains(n)) loadedBefore.add(n);   // S3 snapshot, first thing

            // transformer 1: non-retransformable, adds the marker field, logs every class it sees
            i.addTransformer(new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader l, String name, Class<?> cls, ProtectionDomain pd, byte[] b) {
                    try {
                        synchronized (seenClasses) { seenClasses.add(name); }
                        if (!TARGET.replace('.', '/').equals(name)) return null;
                        t1SawTarget = true;
                        return ClassFile.of().transformClass(ClassFile.of().parse(b), ClassTransform.endHandler(cb ->
                            cb.withField(MARKER, ClassDesc.ofDescriptor("I"), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)));
                    } catch (Throwable t) { premainError = String.valueOf(t); return null; }
                }
            }, false);
            // transformer 2: retransformable, identity re-encode; records whether it is handed the marker
            i.addTransformer(new ClassFileTransformer() {
                @Override public byte[] transform(ClassLoader l, String name, Class<?> cls, ProtectionDomain pd, byte[] b) {
                    try {
                        if (!TARGET.replace('.', '/').equals(name)) return null;
                        t2Calls.incrementAndGet();
                        if (cls == null) t2SawTarget = true;
                        else t2SawMarkerOnRetransform = ClassFile.of().parse(b).fields().stream()
                            .anyMatch(f -> f.fieldName().equalsString(MARKER));
                        return null;   // no-op
                    } catch (Throwable t) { premainError = String.valueOf(t); return null; }
                }
            }, true);
            Class.forName(TARGET, false, ClassLoader.getSystemClassLoader());
        } catch (Throwable t) { premainError = String.valueOf(t); }
    }

    /** ZombieBuddy's Instrumentation: Loader.g_instrumentation (static, package-private). */
    static Instrumentation zbInstrumentation() throws Exception {
        Field f = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
        f.setAccessible(true);
        return (Instrumentation) f.get(null);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("premain error: " + premainError);
        System.out.println("S3 loaded before premain: " + loadedBefore);
        System.out.println("S2a transformer saw IsoZombie at load: " + t1SawTarget + " (retransformable one: " + t2SawTarget + ")");
        System.out.println("transformer saw " + seenClasses.size() + " classes");
        boolean s2b = false;
        if (inst != null) {
            System.out.println("isRetransformClassesSupported: " + inst.isRetransformClassesSupported());
            Class<?> c = Class.forName(TARGET, false, ClassLoader.getSystemClassLoader());
            inst.retransformClasses(c);
            boolean field = false;
            try { c.getDeclaredField(MARKER); field = true; } catch (NoSuchFieldException | LinkageError e) { }
            System.out.println("retransform: t2 calls=" + t2Calls + ", t2 saw marker in bytes=" + t2SawMarkerOnRetransform + ", marker field present=" + field);
            s2b = t2SawMarkerOnRetransform && field;
        }
        System.out.println("S2b marker survives retransform: " + s2b);
        System.out.println("premain error (final): " + premainError);
    }
}
