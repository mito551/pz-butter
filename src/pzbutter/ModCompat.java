package pzbutter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleParameterAnnotationsAttribute;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * modCompat (SPEC 3.10): other Java mods that patch a game method PZ Butter's holders patch, where at least one side
 * replaces stock. ZombieBuddy already knows every approved jar when our main runs (Loader.g_jarLoadStatus); their @Patch
 * annotations are read straight from the class files, nothing is loaded. Every failure ends in one log line and no change.
 */
final class ModCompat {
    private ModCompat() {}

    /** A @Patch class: the game class and method pattern it targets; where = the jar file name, or Outer.Holder for ours. */
    record Target(String className, String methodName, boolean replaces, String where) {}

    /** The classes whose nested classes are PZ Butter's holders. */
    static final Class<?>[] OUTERS = {Patches.class, DeliveryPatches.class, LuaPatches.class, UiPatches.class, MapPatches.class,
        SoundPatches.class, WorldPatches.class, ChunkPatches.class, LogPatches.class};

    private static final String PATCH = "Lme/zed_0xff/zombie_buddy/Patch;";
    private static final String ON_ENTER = "Lme/zed_0xff/zombie_buddy/Patch$OnEnter;";
    private static final String RETURN = "Lme/zed_0xff/zombie_buddy/Patch$Return;";
    private static final String ARGUMENT = "Lme/zed_0xff/zombie_buddy/Patch$Argument;";
    private static final byte[] PATCH_ASCII = "me/zed_0xff/zombie_buddy/Patch".getBytes(StandardCharsets.US_ASCII);

    // ---- reading @Patch annotations without loading the class ----

    /**
     * The @Patch of one class file: className and methodName, and whether the patch replaces stock (isAdvice false, an
     * OnEnter with skipOn true, a Return or Argument with readOnly false). Empty for a class without @Patch.
     */
    static List<Target> scanClass(byte[] classFile, String where) {
        if (classFile == null || indexOf(classFile, PATCH_ASCII) < 0) return List.of();   // most classes: skip the parse
        ClassModel cm = ClassFile.of().parse(classFile);
        String cls = null, method = null;
        boolean replaces = false, patched = false;
        for (Attribute<?> a : cm.attributes()) {
            if (!(a instanceof RuntimeVisibleAnnotationsAttribute rva)) continue;
            for (Annotation an : rva.annotations()) {
                if (!an.className().stringValue().equals(PATCH)) continue;
                patched = true;
                for (var e : an.elements()) {
                    String n = e.name().stringValue();
                    if (n.equals("className") && e.value() instanceof AnnotationValue.OfString s) cls = s.stringValue();
                    else if (n.equals("methodName") && e.value() instanceof AnnotationValue.OfString s) method = s.stringValue();
                    else if (n.equals("isAdvice") && e.value() instanceof AnnotationValue.OfBoolean b && !b.booleanValue()) replaces = true;
                }
            }
        }
        if (!patched || cls == null || method == null) return List.of();
        for (MethodModel mm : cm.methods()) {
            for (Attribute<?> a : mm.attributes()) {
                if (a instanceof RuntimeVisibleAnnotationsAttribute rva) {
                    for (Annotation an : rva.annotations())
                        if (an.className().stringValue().equals(ON_ENTER) && flagIs(an, "skipOn", true, false)) replaces = true;
                } else if (a instanceof RuntimeVisibleParameterAnnotationsAttribute rpa) {
                    for (List<Annotation> one : rpa.parameterAnnotations())
                        for (Annotation an : one) {
                            String n = an.className().stringValue();
                            if ((n.equals(RETURN) || n.equals(ARGUMENT)) && flagIs(an, "readOnly", false, true)) replaces = true;
                        }
                }
            }
        }
        return List.of(new Target(cls, method, replaces, where));
    }

    /** True when the annotation's boolean element has the wanted value; dflt is the element's declared default. */
    private static boolean flagIs(Annotation an, String element, boolean wanted, boolean dflt) {
        for (var e : an.elements())
            if (e.name().stringValue().equals(element) && e.value() instanceof AnnotationValue.OfBoolean b) return b.booleanValue() == wanted;
        return dflt == wanted;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0, last = hay.length - needle.length; i <= last; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** scanClass over every class entry of a jar; an entry that does not parse is skipped. */
    static List<Target> scanJar(Path jar) throws IOException {
        List<Target> out = new ArrayList<>();
        String where = String.valueOf(jar.getFileName());
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                try (InputStream in = z.getInputStream(e)) { out.addAll(scanClass(in.readAllBytes(), where)); }
                catch (RuntimeException | LinkageError bad) { /* a class this parser cannot read patches nothing we can see */ }
            }
        }
        return out;
    }

    /** PZ Butter's own holders, from the class files of the nested classes of OUTERS. */
    static List<Target> ours() {
        List<Target> out = new ArrayList<>();
        for (Class<?> outer : OUTERS)
            for (Class<?> c : outer.getDeclaredClasses()) {
                try { out.addAll(scanClass(classBytes(c), outer.getSimpleName() + "." + c.getSimpleName())); }
                catch (IOException | RuntimeException | LinkageError e) { System.out.println("[PZButter] modCompat: cannot read " + c.getName() + ": " + e); }
            }
        return out;
    }

    private static byte[] classBytes(Class<?> c) throws IOException {
        String n = c.getName();
        try (InputStream in = c.getResourceAsStream(n.substring(n.lastIndexOf('.') + 1) + ".class")) { return in == null ? null : in.readAllBytes(); }
    }

    // ---- conflicts ----

    /** ZombieBuddy's method pattern (SyntaxSugar.methodMatcher): * all, &lt;init&gt;, /regex/, *x*, *x, x*, else equal. */
    static boolean nameMatches(String pattern, String method) {
        if (pattern.equals("*")) return true;
        if (pattern.equals("<init>")) return method.equals("<init>");
        int n = pattern.length();
        if (n >= 2 && pattern.startsWith("*") && pattern.endsWith("*")) return method.contains(pattern.substring(1, n - 1));
        if (pattern.startsWith("*")) return method.endsWith(pattern.substring(1));
        if (pattern.endsWith("*")) return method.startsWith(pattern.substring(0, n - 1));
        if (n >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) {
            try { return method.matches(pattern.substring(1, n - 1)); }
            catch (RuntimeException badRegex) { return false; }
        }
        return method.equals(pattern);
    }

    /** {ourWhere, class.method, theirWhere, side} for each pair on one game method where at least one side replaces stock. */
    static List<String[]> conflicts(List<Target> ours, List<Target> theirs) {
        List<String[]> out = new ArrayList<>();
        for (Target o : ours)
            for (Target t : theirs) {
                if (!o.className().equals(t.className()) || !nameMatches(t.methodName(), o.methodName())) continue;
                if (!o.replaces() && !t.replaces()) continue;   // two observing advices compose
                out.add(new String[] {o.where(), o.className() + "." + o.methodName(), t.where(), o.replaces() && t.replaces() ? "both" : o.replaces() ? "ours" : "theirs"});
            }
        return out;
    }

    // ---- ZombieBuddy's jar list ----

    /** off without the master switch; else modCompat as report or auto, anything else off (the profile may set it). */
    static String mode() {
        if (!Settings.enabled) return "off";
        String v = Settings.raw("modCompat");
        v = v == null ? "off" : v.trim().toLowerCase(java.util.Locale.ROOT);
        return v.equals("report") || v.equals("auto") ? v : "off";
    }

    /**
     * The approved Java mod jars ZombieBuddy knows (Loader.g_jarLoadStatus values with decision TRUE) except ours; null,
     * after one log line, when ZombieBuddy has no such list (another version).
     */
    static List<Path> jarList() {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, ModCompat.class.getClassLoader());
            VarHandle status = MethodHandles.privateLookupIn(loader, MethodHandles.lookup()).findStaticVarHandle(loader, "g_jarLoadStatus", Map.class);
            return jarsFrom((Map<?, ?>) status.get(), ownJar());
        } catch (Throwable t) {
            System.out.println("[PZButter] modCompat: no ZombieBuddy jar list");
            return null;
        }
    }

    /** jarList, empty when there is none. */
    static List<Path> otherJars() {
        List<Path> l = jarList();
        return l == null ? List.of() : l;
    }

    /** The jars of the JavaModLoadState values (record accessors decision() and jarPath()) that were loaded, minus own. */
    static List<Path> jarsFrom(Map<?, ?> status, Path own) throws Throwable {
        List<Path> out = new ArrayList<>();
        Collection<?> states = status.values();
        MethodHandle decision = null, jarPath = null;
        for (Object s : states) {
            if (s == null) continue;
            if (decision == null) {
                MethodHandles.Lookup l = MethodHandles.privateLookupIn(s.getClass(), MethodHandles.lookup());
                decision = l.findVirtual(s.getClass(), "decision", MethodType.methodType(Boolean.class));
                jarPath = l.findVirtual(s.getClass(), "jarPath", MethodType.methodType(Path.class));
            }
            if (!Boolean.TRUE.equals((Boolean) decision.invoke(s))) continue;
            Path p = (Path) jarPath.invoke(s);
            if (p != null && !isOurs(p, own)) out.add(p);
        }
        return out;
    }

    /** The jar (or directory) this class was loaded from, or null. */
    static Path ownJar() {
        try { return Path.of(ModCompat.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Throwable t) { return null; }
    }

    private static boolean isOurs(Path p, Path own) {
        try {
            if (own != null && (p.toAbsolutePath().normalize().equals(own.toAbsolutePath().normalize()) || p.toRealPath().equals(own.toRealPath()))) return true;
            if (java.nio.file.Files.isRegularFile(p))   // a copy of our jar under another path
                try (ZipFile z = new ZipFile(p.toFile())) { return z.getEntry("pzbutter/ModCompat.class") != null; }
        } catch (Throwable t) { /* unreadable: scanned (and reported) later */ }
        return false;
    }
}
