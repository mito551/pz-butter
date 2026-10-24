package pzbutter;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.constantpool.StringEntry;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.HashSet;
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

    static boolean active() { return state == State.ACTIVE; }

    /** Status line text for Main.main. */
    static String status() {
        return switch (state) {
            case ACTIVE -> "active (" + classCount + " classes, " + scratchCount + " scratch fields)";
            case NEEDS_RESTART -> "needs a restart";
            case OFF -> "off (" + reason + ")";
        };
    }

    private static volatile int classCount, scratchCount;

    /** ZB PREMAIN entry (via PreMain). Never throws. */
    static void premain(String[] args) {
        ran = true;
        try {
            Settings.load();
            var inst = ZbAccess.instrumentation();
            var loaded = new HashSet<String>();
            if (inst != null) for (Class<?> c : inst.getAllLoadedClasses()) loaded.add(c.getName());   // before anything else loads
            var list = WorkerList.load(gameRevision());
            var d = decide(gameRevision(), list, Settings.num("frameThreads", 0, 0, 32), Settings.flag("luaWorkerGate"),
                    Settings.enabled, ManagementFactory.getRuntimeMXBean().getInputArguments(), inst != null, loaded);
            if (!d.install()) { reason = d.reason(); state = State.OFF; return; }
            Scratch.init(list);
            var log = new LogSink(new File(System.getProperty("user.home"), "Zomboid/PZButter/preload.log"), 1 << 20);
            var t = new WorkerTransform(list, log);
            try (var in = ClassLoader.getSystemResourceAsStream(list.classes().get(0).replace('.', '/') + ".class")) {
                WorkerTransform.transform(in.readAllBytes(), list);   // warm-up: load the transformer's own classes first
            }
            inst.addTransformer(t, false);
            var loader = ClassLoader.getSystemClassLoader();
            for (String c : list.classes()) Class.forName(c, false, loader);
            for (String c : list.classes()) {
                boolean ok = false;
                try {
                    Class.forName(c, false, loader).getDeclaredField(WorkerTransform.MARKER);
                    ok = true;
                } catch (NoSuchFieldException e) { }
                if (!ok) {
                    inst.removeTransformer(t);
                    reason = c + " not transformed"; state = State.OFF;
                    return;
                }
            }
            classCount = list.classes().size();
            scratchCount = list.scratch().size();
            state = State.ACTIVE;
        } catch (Throwable t) {
            reason = "premain: " + t; state = State.OFF;
        }
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
