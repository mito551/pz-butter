package pzbutter;

import java.io.IOException;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline tool (kept in the test tree, never shipped): walks the invokes reachable from root methods through the game's
 * own packages and writes the worker list: a `class` line for every class that reads a mutable `static final` object,
 * a `scratch` line per such field. Run: {@code java -cp build/test-classes:<game jars> pzbutter.ScratchList <jar> <revision> <owner.name:desc>...}.
 */
final class ScratchList {
    private ScratchList() {}

    private static final String[] WALK = {"zombie/", "se/krka/", "org/lwjgl/util/vector/", "org/joml/"};
    // Lua, sound, physics and network owners are side effects, not scratch: the walk stops there
    private static final String[] STOP = {"zombie/Lua/", "zombie/network/", "zombie/core/network/", "zombie/audio/", "zombie/core/physics/", "zombie/core/raknet/",
        // packages frame workers never run
        "zombie/ui/", "zombie/core/opengl/", "zombie/core/textures/", "zombie/core/Styles/", "zombie/core/sprite/", "zombie/core/SpriteRenderer",
        "zombie/iso/sprite/", "zombie/iso/fboRenderChunk/", "zombie/worldMap/", "zombie/scripting/", "zombie/debug/", "zombie/gameStates/",
        "zombie/input/", "org/lwjgl/opengl/", "org/lwjglx/"};
    private static final Set<String> IMMUTABLE = Set.of("java/lang/String", "java/lang/Class", "java/lang/Boolean", "java/lang/Byte",
        "java/lang/Character", "java/lang/Short", "java/lang/Integer", "java/lang/Long", "java/lang/Float", "java/lang/Double",
        "java/util/regex/Pattern", "java/util/Locale");

    private static final Set<String> READ_ONLY_ARRAYS = Set.of("equals", "hashCode", "toString", "binarySearch", "stream", "asList",
        "copyOf", "copyOfRange", "mismatch", "compare", "deepEquals", "deepHashCode", "deepToString");

    // Cross-call state, not per-call scratch: never rewritten, even though they read as mutable statics.
    //   LightingJNI.updateCounter: compared across update/buildingsChanged calls.
    //   LotHeader.LOT_PACK_MAGIC: readers (POTLotPack.load/save) are not rewritten.
    //   MethodArguments.minPoolSize: pool accounting.
    // Cross-call state, not per-call scratch: never rewritten, even though they read as mutable statics.
    //   LightingJNI.updateCounter: compared across update/buildingsChanged calls.
    //   LotHeader.LOT_PACK_MAGIC/LOTPACK_MAGIC: readers (POTLotPack.load/save) are not rewritten.
    //   MethodArguments.minPoolSize: pool accounting.
    //   IsoGridSquare.SURFACE_OFFSETS: persistent surface-offset table grown per square.
    //   IsoRoofFixer.interiorAirSpaces: persistent interior-air-space cache.
    private static boolean excluded(Field f) {
        if (f.name().equals("updateCounter") && f.owner().endsWith("/LightingJNI")) return true;
        if ((f.name().equals("LOT_PACK_MAGIC") || f.name().equals("LOTPACK_MAGIC")) && f.owner().endsWith("/LotHeader")) return true;
        if (f.name().equals("minPoolSize")) return true;
        if (f.name().equals("SURFACE_OFFSETS") && f.owner().endsWith("/IsoGridSquare")) return true;
        if (f.name().equals("interiorAirSpaces") && f.owner().endsWith("/IsoRoofFixer")) return true;
        return false;
    }

    record Ref(String owner, String name, String desc) {}

    private static final class Index {
        final Map<String, ClassModel> classes = new HashMap<>();
        final Map<String, List<String>> subs = new HashMap<>();

        Index(Path jar) throws IOException {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                for (var en = z.entries(); en.hasMoreElements(); ) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                    try {
                        ClassModel m = ClassFile.of().parse(z.getInputStream(e).readAllBytes());
                        classes.put(m.thisClass().asInternalName(), m);
                    } catch (RuntimeException bad) { /* not a parsable class: skip */ }
                }
            }
            for (ClassModel m : classes.values()) {
                m.superclass().ifPresent(s -> subs.computeIfAbsent(s.asInternalName(), k -> new ArrayList<>()).add(m.thisClass().asInternalName()));
                m.interfaces().forEach(i -> subs.computeIfAbsent(i.asInternalName(), k -> new ArrayList<>()).add(m.thisClass().asInternalName()));
            }
        }

        MethodModel declared(String owner, String name, String desc) {
            ClassModel m = classes.get(owner);
            if (m == null) return null;
            for (MethodModel mm : m.methods())
                if (mm.methodName().equalsString(name) && mm.methodType().equalsString(desc)) return mm;
            return null;
        }

        /** The method an invoke on owner finds going up the hierarchy (superclasses first, then interfaces). */
        Ref lookup(String owner, String name, String desc) {
            Set<String> seen = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(owner));
            while (!todo.isEmpty()) {
                String c = todo.poll();
                if (!seen.add(c)) continue;
                ClassModel m = classes.get(c);
                if (m == null) continue;
                MethodModel mm = declared(c, name, desc);
                if (mm != null && mm.code().isPresent()) return new Ref(c, name, desc);
                m.superclass().ifPresent(s -> todo.add(s.asInternalName()));
                m.interfaces().forEach(i -> todo.add(i.asInternalName()));
            }
            return null;
        }

        /** The declaring class of a field found going up from owner, or null. */
        String fieldOwner(String owner, String name, String desc) {
            Set<String> seen = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(owner));
            while (!todo.isEmpty()) {
                String c = todo.poll();
                if (!seen.add(c)) continue;
                ClassModel m = classes.get(c);
                if (m == null) continue;
                for (FieldModel f : m.fields()) if (f.fieldName().equalsString(name) && f.fieldType().equalsString(desc)) return c;
                m.superclass().ifPresent(s -> todo.add(s.asInternalName()));
                m.interfaces().forEach(i -> todo.add(i.asInternalName()));
            }
            return null;
        }

        /** The lookup result plus every override in a subtype. */
        List<Ref> targets(String owner, String name, String desc, boolean virtual, String[] walk) {
            List<Ref> out = new ArrayList<>();
            Ref r = lookup(owner, name, desc);
            if (r != null && walked(r.owner(), walk)) out.add(r);
            if (virtual) {
                Set<String> seen = new HashSet<>();
                Deque<String> todo = new ArrayDeque<>(subs.getOrDefault(owner, List.of()));
                while (!todo.isEmpty()) {
                    String c = todo.poll();
                    if (!seen.add(c)) continue;
                    MethodModel mm = declared(c, name, desc);
                    if (mm != null && mm.code().isPresent() && walked(c, walk)) out.add(new Ref(c, name, desc));
                    todo.addAll(subs.getOrDefault(c, List.of()));
                }
            }
            return out;
        }
    }

    private static boolean walked(String owner, String[] walk) {
        for (String p : STOP) if (owner.startsWith(p)) return false;
        if (owner.startsWith("zombie/vehicles/") && owner.substring(owner.lastIndexOf('/') + 1).contains("Render")) return false;
        for (String p : walk) if (owner.startsWith(p)) return true;
        return false;
    }

    private record Field(String owner, String name, String desc) {
        String key() { return owner + "." + name + ":" + desc; }
    }

    /** The list text, one line per element. Roots are `pkg.Owner.name:desc` (resolved up the hierarchy and into overrides). */
    static List<String> run(Path jar, List<String> roots, String revision) throws IOException { return run(jar, roots, revision, WALK); }

    private static String hOwner(DirectMethodHandleDesc h) {
        String d = h.owner().descriptorString();
        return d.substring(1, d.length() - 1);
    }

    /** As above, walking only owners with one of the given internal-name prefixes. */
    static List<String> run(Path jar, List<String> roots, String revision, String[] walk) throws IOException {
        return run(jar, roots, revision, walk, ScratchList::valueType);
    }

    /** As above, with the predicate that decides which internal type names are value scratch (the rest are `# review`). */
    static List<String> run(Path jar, List<String> roots, String revision, String[] walk, java.util.function.Predicate<String> value) throws IOException {
        Index ix = new Index(jar);
        Deque<Ref> todo = new ArrayDeque<>();
        Set<Ref> seen = new HashSet<>();
        for (String root : roots) {
            int colon = root.indexOf(':'), dot = root.lastIndexOf('.', colon);
            List<Ref> t = ix.targets(root.substring(0, dot).replace('.', '/'), root.substring(dot + 1, colon), root.substring(colon + 1), true, new String[] {""});
            if (t.isEmpty()) throw new IllegalArgumentException("root not found: " + root);
            todo.addAll(t);
        }
        Map<Field, Set<String>> readers = new HashMap<>();      // field -> reader methods ("owner.name")
        Map<Field, Set<String>> writers = new HashMap<>();
        Map<String, Set<Opcode>> stores = new HashMap<>();      // method -> array element stores it contains
        Map<Field, Set<String>> named = new HashMap<>();        // field -> owner names its reads use
        while (!todo.isEmpty()) {
            Ref r = todo.poll();
            if (!seen.add(r)) continue;
            MethodModel mm = ix.declared(r.owner(), r.name(), r.desc());
            String who = r.owner().replace('/', '.') + "." + r.name();
            for (CodeElement e : mm.code().orElseThrow()) {
                if (e instanceof InvokeInstruction i) {
                    String o = i.owner().asInternalName();
                    if (o.equals("java/lang/System") && i.name().equalsString("arraycopy")
                        || o.equals("java/util/Arrays") && !READ_ONLY_ARRAYS.contains(i.name().stringValue()))
                        stores.computeIfAbsent(who, k -> new HashSet<>()).add(Opcode.INVOKESTATIC);
                    if (walked(o, walk)) todo.addAll(ix.targets(o, i.name().stringValue(), i.type().stringValue(), i.opcode() != Opcode.INVOKESTATIC && i.opcode() != Opcode.INVOKESPECIAL, walk));
                } else if (e instanceof InvokeDynamicInstruction d) {
                    for (var arg : d.bootstrapArgs())
                        if (arg instanceof DirectMethodHandleDesc h && walked(hOwner(h), walk))
                            todo.addAll(ix.targets(hOwner(h), h.methodName(), h.invocationType().descriptorString(), false, walk));
                } else if (e instanceof java.lang.classfile.instruction.ArrayStoreInstruction a) {
                    stores.computeIfAbsent(who, k -> new HashSet<>()).add(a.opcode());
                } else if (e instanceof FieldInstruction f && (f.opcode() == Opcode.GETSTATIC || f.opcode() == Opcode.PUTSTATIC)) {
                    String decl = ix.fieldOwner(f.owner().asInternalName(), f.name().stringValue(), f.type().stringValue());
                    if (decl == null) continue;
                    Field key = new Field(decl, f.name().stringValue(), f.type().stringValue());
                    (f.opcode() == Opcode.GETSTATIC ? readers : writers).computeIfAbsent(key, k -> new TreeSet<>()).add(who);
                    if (f.opcode() == Opcode.GETSTATIC) named.computeIfAbsent(key, k -> new TreeSet<>()).add(f.owner().asInternalName());
                }
            }
        }

        TreeMap<String, Field> scratch = new TreeMap<>();
        TreeSet<String> readonly = new TreeSet<>(), review = new TreeSet<>(), shared = new TreeSet<>(), unsupported = new TreeSet<>(), written = new TreeSet<>(), classes = new TreeSet<>();
        for (var en : readers.entrySet()) {
            Field f = en.getKey();
            FieldModel fm = ix.classes.get(f.owner()).fields().stream().filter(x -> x.fieldName().equalsString(f.name())).findFirst().orElseThrow();
            int flags = fm.flags().flagsMask();
            if (!Modifier.isStatic(flags) || !Modifier.isFinal(flags)) continue;
            if (!f.desc().startsWith("L") && !f.desc().startsWith("[")) continue;
            String type = f.desc().startsWith("L") ? f.desc().substring(1, f.desc().length() - 1) : null;
            if (type != null && immutable(ix, type)) continue;
            if (excluded(f)) continue;
            String name = f.owner().replace('/', '.') + "." + f.name();
            Set<String> rs = readers.get(f);
            String info = (type == null ? f.desc() : type.replace('/', '.')) + " " + rs.size() + " " + rs.iterator().next();
            if (type != null && (type.equals("java/lang/ThreadLocal") || type.startsWith("java/util/concurrent/"))) { shared.add(name + " " + info.substring(0, info.indexOf(' '))); continue; }
            if (f.name().contains("$SwitchMap$") || type == null && primitiveArray(f.desc()) && !stored(f.desc(), rs, stores)) {
                readonly.add(name + " " + f.desc());
                continue;
            }
            if (type == null && primitiveArray(f.desc()) && !allStored(f.desc(), rs, stores)) {
                review.add(name + " " + info);
                continue;
            }
            if (type == null && !primitiveArray(f.desc())) { review.add(name + " " + info); continue; }   // object and multi-dimensional arrays: clone() is shallow
            if (type != null && !value.test(type)) { review.add(name + " " + info); continue; }
            if (type != null && !hasPublicNoArg(ix, type)) { unsupported.add(name + " " + f.desc()); continue; }
            scratch.put(f.key(), f);
        }
        for (var en : writers.entrySet()) {
            Field f = en.getKey();
            FieldModel fm = ix.classes.get(f.owner()).fields().stream().filter(x -> x.fieldName().equalsString(f.name())).findFirst().orElseThrow();
            if (!Modifier.isFinal(fm.flags().flagsMask())) written.add(f.owner().replace('/', '.') + "." + f.name() + ":" + f.desc());
        }

        List<String> out = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        int k = 0;
        // (a) every class in the jar that reads a scratch field is listed, not only those reached by the walk.
        Set<Field> scratchFields = new HashSet<>(scratch.values());
        for (ClassModel cm : ix.classes.values()) {
            boolean readsScratch = false;
            for (MethodModel mm : cm.methods()) {
                if (mm.code().isEmpty()) continue;
                for (CodeElement e : mm.code().get())
                    if (e instanceof FieldInstruction fi && fi.opcode() == Opcode.GETSTATIC) {
                        String decl = ix.fieldOwner(fi.owner().asInternalName(), fi.name().stringValue(), fi.type().stringValue());
                        if (decl != null && scratchFields.contains(new Field(decl, fi.name().stringValue(), fi.type().stringValue()))) readsScratch = true;
                    }
                if (readsScratch) break;
            }
            if (readsScratch) classes.add(cm.thisClass().asInternalName().replace('/', '.'));
        }
        for (Field f : scratch.values()) {
            Set<String> rs = readers.get(f);
            rs.forEach(w -> classes.add(w.substring(0, w.lastIndexOf('.'))));
            lines.add("scratch " + k + " " + f.owner() + " " + f.name() + " " + f.desc() + "   # "
                + (f.desc().startsWith("[") ? f.desc() : f.desc().substring(1, f.desc().length() - 1).replace('/', '.'))
                + " readers=" + rs.size() + " first=" + rs.iterator().next());
            for (String o : named.get(f))
                if (!o.equals(f.owner())) lines.add("scratch " + k + " " + o + " " + f.name() + " " + f.desc() + "   # named through a subclass");
            k++;
        }
        out.add("revision " + revision);
        out.add("# generated by pzbutter.ScratchList; roots: " + String.join(" ", roots));
        classes.forEach(c -> out.add("class " + c));
        out.addAll(lines);
        readonly.forEach(u -> out.add("# readonly " + u));
        review.forEach(u -> out.add("# review " + u));
        shared.forEach(u -> out.add("# shared " + u));
        unsupported.forEach(u -> out.add("# unsupported " + u));
        written.forEach(w -> out.add("# written " + w));
        return out;
    }

    private static boolean primitiveArray(String desc) { return desc.length() == 2 && desc.charAt(0) == '['; }

    /** Does any reader method store an element of the array's element type (bastore covers boolean and byte). */
    private static boolean stored(String desc, Set<String> readers, Map<String, Set<Opcode>> stores) {
        String el = desc.substring(1);
        Opcode op = switch (el) {
            case "I" -> Opcode.IASTORE; case "F" -> Opcode.FASTORE; case "B", "Z" -> Opcode.BASTORE; case "C" -> Opcode.CASTORE;
            case "S" -> Opcode.SASTORE; case "J" -> Opcode.LASTORE; default -> Opcode.DASTORE;
        };
        for (String r : readers) {
            if (r.endsWith(".<clinit>")) continue;
            Set<Opcode> st = stores.getOrDefault(r, Set.of());
            if (st.contains(op) || st.contains(Opcode.INVOKESTATIC)) return true;   // INVOKESTATIC: marker for an array copy or fill
        }
        return false;
    }

    /** True only when every reader method outside <clinit> stores into the array (mutating Arrays/System.arraycopy). */
    private static boolean allStored(String desc, Set<String> readers, Map<String, Set<Opcode>> stores) {
        String el = desc.substring(1);
        Opcode op = switch (el) {
            case "I" -> Opcode.IASTORE; case "F" -> Opcode.FASTORE; case "B", "Z" -> Opcode.BASTORE; case "C" -> Opcode.CASTORE;
            case "S" -> Opcode.SASTORE; case "J" -> Opcode.LASTORE; default -> Opcode.DASTORE;
        };
        boolean any = false;
        for (String r : readers) {
            if (r.endsWith(".<clinit>")) continue;
            any = true;
            Set<Opcode> st = stores.getOrDefault(r, Set.of());
            if (!(st.contains(op) || st.contains(Opcode.INVOKESTATIC))) return false;
        }
        return any;
    }

    private static boolean valueType(String t) {
        if (t.equals("zombie/iso/Vector2") || t.equals("zombie/iso/Vector3") || t.startsWith("org/lwjgl/util/vector/")) return true;
        return t.matches("org/joml/(Vector|Matrix|Quaternion)[^/]*");
    }

    private static boolean immutable(Index ix, String type) {
        if (IMMUTABLE.contains(type)) return true;
        ClassModel m = ix.classes.get(type);
        if (m == null) return false;
        String sup = m.superclass().map(s -> s.asInternalName()).orElse("");
        if (sup.equals("java/lang/Enum")) return true;
        if (sup.equals("java/lang/Record")) {   // only final primitive fields
            return m.fields().stream().filter(f -> !Modifier.isStatic(f.flags().flagsMask()))
                .allMatch(f -> Modifier.isFinal(f.flags().flagsMask()) && f.fieldType().stringValue().length() == 1);
        }
        return false;
    }

    private static boolean hasPublicNoArg(Index ix, String type) {
        ClassModel m = ix.classes.get(type);
        if (m != null) {
            int fl = m.flags().flagsMask();
            if (!Modifier.isPublic(fl) || Modifier.isAbstract(fl) || Modifier.isInterface(fl)) return false;
            MethodModel c = ix.declared(type, "<init>", "()V");
            return c != null && Modifier.isPublic(c.flags().flagsMask());
        }
        try {
            Class<?> c = Class.forName(type.replace('/', '.'), false, ScratchList.class.getClassLoader());
            if (!Modifier.isPublic(c.getModifiers()) || Modifier.isAbstract(c.getModifiers())) return false;
            c.getConstructor();
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Reached method set (`owner.name:desc`), for census and other tools. Built from the existing walk and override resolution. */
    static Set<String> reach(Path jar, List<String> roots) throws IOException {
        Index ix = new Index(jar);
        Deque<Ref> todo = new ArrayDeque<>();
        Set<Ref> seen = new HashSet<>();
        Set<String> rootPrefixes = new TreeSet<>();
        for (String root : roots) {
            int colon = root.indexOf(':'), dot = root.lastIndexOf('.', colon);
            String owner = root.substring(0, dot).replace('.', '/');
            int slash = owner.indexOf('/');
            rootPrefixes.add(slash < 0 ? owner : owner.substring(0, slash + 1));
            List<Ref> t = ix.targets(owner, root.substring(dot + 1, colon), root.substring(colon + 1), true, new String[] {""});
            if (t.isEmpty()) throw new IllegalArgumentException("root not found: " + root);
            todo.addAll(t);
        }
        List<String> walkList = new ArrayList<>(List.of(WALK));
        walkList.addAll(rootPrefixes);
        String[] walkAll = walkList.toArray(new String[0]);
        while (!todo.isEmpty()) {
            Ref r = todo.poll();
            if (!seen.add(r)) continue;
            MethodModel mm = ix.declared(r.owner(), r.name(), r.desc());
            if (mm == null || mm.code().isEmpty()) continue;
            String who = r.owner().replace('/', '.') + "." + r.name();
            for (CodeElement e : mm.code().get()) {
                if (e instanceof InvokeInstruction i) {
                    String o = i.owner().asInternalName();
                    if ((o.equals("java/lang/System") && i.name().equalsString("arraycopy"))
                        || (o.equals("java/util/Arrays") && !READ_ONLY_ARRAYS.contains(i.name().stringValue())))
                        { /* marker, no expansion */ }
                    String pref = null;
                    int sl = o.indexOf('/');
                    pref = sl < 0 ? o : o.substring(0, sl + 1);
                    boolean w = walked(o, WALK) || rootPrefixes.contains(pref);
                    if (w) todo.addAll(ix.targets(o, i.name().stringValue(), i.type().stringValue(), i.opcode() != Opcode.INVOKESTATIC && i.opcode() != Opcode.INVOKESPECIAL, walkAll));
                } else if (e instanceof InvokeDynamicInstruction d) {
                    for (var arg : d.bootstrapArgs())
                        if (arg instanceof DirectMethodHandleDesc h && walked(hOwner(h), WALK))
                            todo.addAll(ix.targets(hOwner(h), h.methodName(), h.invocationType().descriptorString(), false, WALK));
                }
            }
        }
        Set<String> out = new TreeSet<>();
        for (Ref r : seen) out.add(r.owner().replace('/', '.') + "." + r.name() + ":" + r.desc());
        return out;
    }

    public static void main(String[] args) throws IOException {
        Path hand = null;
        int i = 0;
        if (args.length >= 2 && args[0].equals("--hand")) { hand = Path.of(args[1]); i = 2; }
        if (args.length - i < 3) { System.err.println("usage: ScratchList [--hand <list file>] <jar> <revision> <owner.name:desc>..."); System.exit(2); }
        List<String> out = new ArrayList<>(run(Path.of(args[i]), List.of(args).subList(i + 2, args.length), args[i + 1]));
        if (hand != null) out.addAll(handSection(hand));
        System.out.print(String.join("\n", out) + "\n");
    }

    /** The lines of the file's `# hand begin`..`# hand end` section, inclusive (empty if none). */
    public static List<String> handSection(Path handFile) throws IOException {
        List<String> lines = Files.readAllLines(handFile);
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String raw : lines) {
            String s = raw.strip();
            if (s.equals("# hand begin")) in = true;
            if (in) out.add(raw);
            if (s.equals("# hand end")) { in = false; }
        }
        return out;
    }
}
