package pzbutter;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;

/** Rewrites a listed game class: before each listed instruction it inserts a {@code Census.hit(id)} call. */
final class CensusTransform implements ClassFileTransformer {
    private static final ClassDesc CENSUS = ClassDesc.of("pzbutter.Census");
    private static final MethodTypeDesc HIT = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);

    private final CensusSites sites;
    private final LogSink log;

    private static int missedCount = 0;

    CensusTransform(CensusSites sites, LogSink log) {
        this.sites = sites;
        this.log = log;
    }

    @Override public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefining, ProtectionDomain pd, byte[] bytes) {
        try {
            if (redefining != null) {
                if (className != null && sites.listed(className)) missedCount++;
                return null;
            }
            if (className == null || !sites.listed(className)) return null;
            return transform(bytes, sites);
        } catch (Throwable t) {
            try { if (log != null) synchronized (log) { log.begin(System.currentTimeMillis()).text("censusTransform " + className + " " + t).end(System.currentTimeMillis()); log.flushBuf(); } }
            catch (Throwable ignored) {}
            return null;
        }
    }

    static int missed() { return missedCount; }

    static byte[] transform(byte[] classFile, CensusSites sites) {
        ClassFile cf0 = ClassFile.of();
        ClassModel m = cf0.parse(classFile);
        String self = m.thisClass().asInternalName();
        if (!sites.listed(self)) return null;

        List<CensusSites.Site> selfSites = sites.sitesOf(self);
        // verify every listed offset first
        java.util.Map<String, List<CensusSites.Site>> byMethod = new java.util.HashMap<>();
        for (CensusSites.Site site : selfSites) byMethod.computeIfAbsent(site.method(), k -> new java.util.ArrayList<>()).add(site);

        // Pre-verify: each listed offset must be an instruction start with matching opcode/target.
        for (var mm : m.methods()) {
            String md = mm.methodName().stringValue() + mm.methodType().stringValue();
            List<CensusSites.Site> ss = byMethod.get(md);
            if (ss == null) continue;
            if (mm.code().isEmpty()) {
                // only sync sites could be ok? sync requires code
                for (CensusSites.Site s : ss) if (s.offset() != -1) return null;
                continue;
            }
            // build offset -> instruction map
            java.util.Map<Integer, Instruction> at = new java.util.TreeMap<>();
            int off = 0;
            for (CodeElement e : mm.code().get().elementList()) {
                if (e instanceof Instruction ins) {
                    at.put(off, ins);
                    off += ins.sizeInBytes();
                }
            }
            for (CensusSites.Site s : ss) {
                if (s.offset() < 0) continue; // sync
                Instruction ins = at.get(s.offset());
                if (ins == null) return null;
                if (!matches(ins, s)) return null;
            }
        }

        ClassLoader l = ClassLoader.getSystemClassLoader();
        ClassFile cf = WorkerTransform.classFile(l);

        List<CensusSites.Site>[] currentSites = new List[1];
        int[] curOff = {0};
        boolean[] syncDone = {false};
        java.util.function.Predicate<MethodModel> methodPred = mm -> {
            String md = mm.methodName().stringValue() + mm.methodType().stringValue();
            List<CensusSites.Site> ss = byMethod.get(md);
            if (ss == null || ss.isEmpty()) return false;
            currentSites[0] = ss;
            curOff[0] = 0;
            syncDone[0] = false;
            return true;
        };

        CodeTransform ct = (cb, e) -> {
            List<CensusSites.Site> ss = currentSites[0];
            if (ss != null && !syncDone[0]) {
                for (CensusSites.Site site : ss) {
                    if (site.offset() == -1) emitHit(cb, site);
                }
                syncDone[0] = true;
            }
            if (e instanceof Instruction) {
                int o = curOff[0];
                if (ss != null) {
                    for (CensusSites.Site site : ss) {
                        if (site.offset() == o) emitHit(cb, site);
                    }
                }
            }
            cb.with(e);
            if (e instanceof Instruction ins) curOff[0] += ins.sizeInBytes();
        };

        return cf.transformClass(m, ClassTransform.transformingMethodBodies(methodPred, ct));
    }

    private static void emitHit(java.lang.classfile.CodeBuilder cb, CensusSites.Site site) {
        int id = site.id();
        if (id <= 32767) cb.sipush(id);
        else cb.ldc(cb.constantPool().intEntry(id));
        cb.invokestatic(CENSUS, "hit", HIT);
    }

    private static boolean matches(Instruction ins, CensusSites.Site site) {
        String target = site.target();
        if (ins instanceof InvokeInstruction i) {
            String cand = i.owner().asInternalName() + "." + i.name().stringValue() + ":" + i.type().stringValue();
            return cand.equals(target);
        }
        if (ins instanceof FieldInstruction f) {
            Opcode expectedOp = site.family().equals("PUTSTATIC") ? Opcode.PUTSTATIC : Opcode.GETSTATIC;
            if (f.opcode() != expectedOp) return false;
            String owner = f.owner().asInternalName();
            String cand = owner + "." + f.name().stringValue() + ":" + f.type().stringValue();
            if (cand.equals(target)) return true;
            // try resolving declaration owner
            String tOwner = target.substring(0, target.lastIndexOf('.'));
            String tField = target.substring(target.lastIndexOf('.') + 1, target.indexOf(':'));
            String tType = target.substring(target.indexOf(':') + 1);
            if (!tField.equals(f.name().stringValue()) || !tType.equals(f.type().stringValue())) return false;
            // resolve decl
            String resolved = resolveDeclOwner(f, ClassLoader.getSystemClassLoader());
            return resolved.equals(tOwner);
        }
        if (ins instanceof MonitorInstruction mi) {
            return site.target().equals("monitor") && mi.opcode() == Opcode.MONITORENTER;
        }
        return false;
    }

    private static String resolveDeclOwner(FieldInstruction f, ClassLoader l) {
        String binary = f.owner().asInternalName().replace('/', '.');
        String name = f.name().stringValue();
        String desc = f.type().stringValue();
        try {
            Class<?> cls = Class.forName(binary, false, l);
            while (cls != null) {
                for (java.lang.reflect.Field fld : cls.getDeclaredFields()) {
                    if (fld.getName().equals(name)) {
                        // rough descriptor check: only name; type mapping not exact but ok
                        return cls.getName().replace('.', '/');
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return f.owner().asInternalName();
    }
}