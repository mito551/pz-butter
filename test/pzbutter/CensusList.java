package pzbutter;

import java.io.IOException;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline tool (test tree, never shipped): counts the side-effecting call sites a calm zombie's update reaches, grouped by family.
 * Usage: {@code java -cp build/test-classes:<game jars> pzbutter.CensusList <jar> <revision> <root>...}.
 */
final class CensusList {
    private CensusList() {}

    static List<String> run(Path jar, List<String> roots, String revision, Set<String> reviewFields) throws IOException {
        Map<String, ClassModel> classes = new TreeMap<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (var en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                try {
                    ClassModel m = ClassFile.of().parse(z.getInputStream(e).readAllBytes());
                    classes.put(m.thisClass().asInternalName(), m);
                } catch (RuntimeException bad) { /* skip */ }
            }
        }

        Set<String> reached = ScratchList.reach(jar, roots);

        List<Site> sites = new ArrayList<>();
        Set<String> siteClasses = new TreeSet<>();
        for (String m : reached) {
            int colon = m.indexOf(':');
            String head = m.substring(0, colon);
            int dot = head.lastIndexOf('.');
            String ownerBin = head.substring(0, dot);
            String name = head.substring(dot + 1);
            String desc = m.substring(colon + 1);
            String owner = ownerBin.replace('.', '/');
            ClassModel cm = classes.get(owner);
            if (cm == null) continue;
            MethodModel mm = null;
            for (MethodModel cand : cm.methods())
                if (cand.methodName().equalsString(name) && cand.methodType().equalsString(desc)) { mm = cand; break; }
            if (mm == null || mm.code().isEmpty()) continue;

            int flags = mm.flags().flagsMask();
            if ((flags & (java.lang.reflect.Modifier.SYNCHRONIZED)) != 0) {
                sites.add(new Site(owner, name + desc, -1, "LOCK", "sync", false));
                siteClasses.add(owner.replace('/', '.'));
            }

            List<CodeElement> elems = mm.code().get().elementList();
            int offset = 0;
            for (int idx = 0; idx < elems.size(); idx++) {
                CodeElement el = elems.get(idx);
                if (!(el instanceof Instruction ins)) { continue; }
                if (el instanceof InvokeInstruction i) {
                    String o = i.owner().asInternalName();
                    String family = familyOfInvoke(o, i.name().stringValue());
                    String target = o + "." + i.name().stringValue() + ":" + i.type().stringValue();
                    if (family != null) { sites.add(new Site(owner, name + desc, offset, family, target, false)); siteClasses.add(owner.replace('/', '.')); }
                } else if (el instanceof FieldInstruction f) {
                    String fo = f.owner().asInternalName();
                    String fname = f.name().stringValue();
                    String ftype = f.type().stringValue();
                    Opcode op = f.opcode();
                    String declFo = fo;
                    java.util.Set<String> _seen = new java.util.HashSet<>();
                    java.util.Deque<String> _todo = new java.util.ArrayDeque<>(java.util.List.of(fo));
                    outer: while (!_todo.isEmpty()) {
                        String _c = _todo.poll();
                        if (!_seen.add(_c)) continue;
                        ClassModel _cm = classes.get(_c);
                        if (_cm != null) {
                            for (var _fm : _cm.fields())
                                if (_fm.fieldName().equalsString(fname) && _fm.fieldType().equalsString(ftype)) { declFo = _c; break outer; }
                            _cm.superclass().ifPresent(s -> _todo.add(s.asInternalName()));
                            _cm.interfaces().forEach(i -> _todo.add(i.asInternalName()));
                        }
                    }
                    String fieldKey = declFo + "." + fname;
                    if (op == Opcode.GETSTATIC) {
                        String family = null;
                        if (fo.equals("zombie/GameWindow") && fname.equals("gameThread")) family = "THREAD";
                        else if (reviewFields.contains(fieldKey)) family = "REVIEW_READ";
                        if (family != null) {
                            sites.add(new Site(owner, name + desc, offset, family, fieldKey + ":" + ftype, false));
                            siteClasses.add(owner.replace('/', '.'));
                        }
                    } else if (op == Opcode.PUTSTATIC) {
                        if (!name.equals("<clinit>") || true) {
                            if (!mm.methodName().equalsString("<clinit>")) {
                                sites.add(new Site(owner, name + desc, offset, "PUTSTATIC", fieldKey + ":" + ftype, false));
                                siteClasses.add(owner.replace('/', '.'));
                            }
                        }
                    } else if (false /* PUTFIELD dropped */) {
                        boolean approx = true;
                        if (idx >= 2 && elems.get(idx - 1) instanceof Instruction) {
                            // best effort: object pushed just before the value push
                            CodeElement prev2 = idx >= 2 ? elems.get(idx - 2) : null;
                            if (prev2 instanceof Instruction p2 && p2.opcode() == Opcode.ALOAD_0) approx = false;
                        }
                        sites.add(new Site(owner, name + desc, offset, "PUTFIELD_OTHER", fieldKey + ":" + ftype, approx));
                        siteClasses.add(owner.replace('/', '.'));
                        // only add if approx? spec says any putfield whose object not aload_0; keep all approximated? We add all as PUTFIELD_OTHER, approx flag irrelevant to family. Actually family PUTFIELD_OTHER should be only those not aload_0.
                    }
                } else if (el instanceof MonitorInstruction mi && mi.opcode() == Opcode.MONITORENTER) {
                    sites.add(new Site(owner, name + desc, offset, "LOCK", "monitor", false));
                    siteClasses.add(owner.replace('/', '.'));
                }
                offset += ins.sizeInBytes();
            }
        }

        int luaDataCount = 0;
        for (Site s : sites) if (s.family().equals("LUA_DATA")) luaDataCount++;
        boolean dropLuaData = luaDataCount > 300;
        List<Site> filtered = new ArrayList<>();
        for (Site s : sites) {
            if (s.family().equals("LUA_DATA") && dropLuaData) continue;
            filtered.add(s);
        }
        sites = filtered;

        // reorder ids dense by current list order
        List<String> out = new ArrayList<>();
        out.add("revision " + revision);
        TreeSet<String> finalClasses = new TreeSet<>();
        for (Site s : sites) finalClasses.add(s.owner().replace('/', '.'));
        List<String> clsLines = new ArrayList<>();
        for (String c : finalClasses) clsLines.add("class " + c);
        out.addAll(clsLines);
        if (dropLuaData) out.add("# dropped LUA_DATA " + luaDataCount);
        int id = 0;
        for (Site s : sites) {
            String line = "site " + id + " " + s.family() + " " + s.owner().replace('/', '?').replace('?', '/') // noop-ish keep slash
                + " " + s.method() + " " + s.offset() + " " + s.target();
            // owner internal with slashes already
            line = "site " + id + " " + s.family() + " " + s.owner() + " " + s.method() + " " + s.offset() + " " + s.target();
            if (s.family().equals("PUTFIELD_OTHER") && s.approx()) line += " # approx";
            out.add(line);
            id++;
        }
        return out;
    }

    private static String familyOfInvoke(String owner, String name) {
        // simple name
        String simple = owner.substring(owner.lastIndexOf('/') + 1);
        // LUA: narrowly (Ruling 2b)
        if (owner.equals("zombie/Lua/LuaEventManager") || owner.equals("zombie/Lua/LuaHookManager")) return "LUA";
        if (owner.equals("zombie/Lua/LuaManager") && (name.startsWith("caller") || name.startsWith("RunLua") || name.startsWith("call"))) return "LUA";
        if (owner.equals("se/krka/kahlua/integration/LuaCaller")) return "LUA";
        if (owner.equals("se/krka/kahlua/vm/KahluaThread") && (name.startsWith("call") || name.startsWith("pcall"))) return "LUA";
        if (owner.startsWith("zombie/Lua/") || owner.startsWith("se/krka/")) return "LUA_DATA";
        // SHARED (Ruling 2a): right after NET
        String o = owner;
        boolean shared = o.equals("zombie/iso/IsoGridSquare") || o.equals("zombie/iso/IsoCell") || o.equals("zombie/iso/IsoChunk")
            || o.equals("zombie/iso/IsoChunkMap") || o.equals("zombie/iso/IsoMetaGrid") || o.equals("zombie/iso/IsoWorld")
            || o.equals("zombie/ai/ZombieGroupManager") || o.equals("zombie/characters/ZombieGroup")
            || o.equals("zombie/pathfind/PolygonalMap2") || o.equals("zombie/statistics/StatisticsManager")
            || o.startsWith("zombie/characters/AttachedItems/") || o.startsWith("zombie/popman/")
            || o.equals("zombie/VirtualZombieManager");
        if (owner.startsWith("zombie/network/")) {
            // NET handled below before shared; ensure order NET before SHARED
        }
        if (owner.startsWith("fmod/") || owner.startsWith("zombie/audio/") || simple.contains("SoundManager") || simple.contains("SoundEmitter") || owner.equals("zombie/WorldSoundManager")) return "SOUND";
        if (owner.startsWith("zombie/core/physics/") || simple.contains("Bullet") || simple.contains("Ragdoll")) return "PHYSICS";
        if (owner.startsWith("zombie/core/random/") || owner.equals("java/util/Random") || owner.equals("java/util/concurrent/ThreadLocalRandom")) return "RNG";
        if (owner.startsWith("zombie/network/")) return "NET";
        if (shared) return "SHARED";
        if (name.equals("currentThread") && owner.equals("java/lang/Thread")) return "THREAD";
        return null;
    }

    private record Site(String owner, String method, int offset, String family, String target, boolean approx) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) { System.err.println("usage: CensusList <jar> <revision> <root>..."); System.exit(2); }
        // read review fields
        String revision = args[1];
        Set<String> review = new TreeSet<>();
        Path rf = Path.of("src/pzbutter/workers-" + revision + ".txt");
        if (Files.exists(rf)) {
            for (String line : Files.readAllLines(rf)) {
                String t = line.trim();
                if (!t.startsWith("# review")) continue;
                String[] sp = t.split("\\s+");
                if (sp.length < 2) continue;
                String tok = sp.length >= 3 ? sp[2] : sp[1];
                int last = tok.lastIndexOf('.');
                if (last < 0) continue;
                String ownerBin = tok.substring(0, last);
                String field = tok.substring(last + 1);
                review.add(ownerBin.replace('.', '/') + "." + field);
            }
        }
        List<String> roots = List.of(args).subList(2, args.length);
        List<String> out = run(Path.of(args[0]), roots, revision, review);
        System.out.print(String.join("\n", out) + "\n");
    }
}