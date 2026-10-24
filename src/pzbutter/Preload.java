package pzbutter;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.StringEntry;
import java.util.List;
import java.util.Set;

/** The ZB preload gate: whether the frame-worker class transformer is installed. Decision is pure; see decide. */
final class Preload {
    private Preload() {}

    enum State { ACTIVE, NEEDS_RESTART, OFF }

    record Decision(boolean install, String reason) {}

    static volatile State state = State.NEEDS_RESTART;
    static volatile String reason = "";
    static volatile boolean ran;

    /** The first failing check is the reason. jvmArgs and loadedClassNames are never null. */
    static Decision decide(String gameRevision, WorkerList list, int frameThreads, boolean luaWorkerGate, boolean enabled,
                           List<String> jvmArgs, boolean instrumentationPresent, Set<String> loadedClassNames) {
        if (!enabled) return no("enabled=false");
        if (frameThreads <= 1) return no("frameThreads=" + frameThreads);
        if (!luaWorkerGate) return no("luaWorkerGate=false");
        if (!instrumentationPresent) return no("no ZombieBuddy Instrumentation");
        if (list == null) return no("no list for revision " + gameRevision);
        if (!list.revision().equals(gameRevision)) return no("revision " + gameRevision + " != list " + list.revision());
        for (String a : jvmArgs) {
            if (a.startsWith("-XX:AOTCache") || a.startsWith("-XX:SharedArchiveFile")
                    || (a.startsWith("-XX:AOTMode=") && !a.equals("-XX:AOTMode=off")))
                return no("AOT cache " + a);
        }
        for (String c : list.classes())
            if (loadedClassNames.contains(c)) return no(c + " loaded before the preload");
        return new Decision(true, "");
    }

    private static Decision no(String why) { return new Decision(false, why); }

    /** The game's REVISION from zombie/GitVersion.class, read as bytes so the class is never loaded. "" on failure. */
    static String gameRevision() {
        try (var in = ClassLoader.getSystemResourceAsStream("zombie/GitVersion.class")) {
            var cls = ClassFile.of().parse(in.readAllBytes());
            for (var field : cls.fields()) {
                if (!field.fieldName().stringValue().equals("REVISION")) continue;
                var attr = field.findAttribute(Attributes.constantValue()).orElse(null);
                return attr == null ? "" : ((StringEntry) attr.constant()).stringValue();
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }
}
