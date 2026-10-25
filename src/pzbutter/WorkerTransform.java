package pzbutter;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Rewrites a listed game class: each getstatic of a listed scratch field (outside {@code <clinit>}) becomes
 * {@code Scratch.get(k)} plus a checkcast, the list's `field` lines are added, and a marker int constant carries the list's
 * text hash. Loads no class and runs no game code; any failure leaves the class untouched.
 */
final class WorkerTransform implements ClassFileTransformer {
    static final String MARKER = "pzbutter$w";
    private static final ClassDesc SCRATCH = ClassDesc.of("pzbutter.Scratch");
    private static final MethodTypeDesc GET = MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int);

    private final WorkerList list;
    private final LogSink log;

    WorkerTransform(WorkerList list, LogSink log) {
        this.list = list;
        this.log = log;
    }

    @Override public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefining, ProtectionDomain pd, byte[] bytes) {
        try {
            if (redefining != null || className == null || !list.listed(className)) return null;
            return transform(bytes, list, loader);
        } catch (Throwable t) {
            try { if (log != null) synchronized (log) { log.begin(System.currentTimeMillis()).text("workerTransform " + className + " " + t).end(System.currentTimeMillis()); log.flushBuf(); } }
            catch (Throwable ignored) {}
            return null;
        }
    }

    static byte[] transform(byte[] classFile, WorkerList list) { return transform(classFile, list, null); }

    /** The transformed class, or null (untouched) when it is not listed, clashes with an added name, or the list is out of date. */
    static byte[] transform(byte[] classFile, WorkerList list, ClassLoader loader) {
        ClassFile cf0 = ClassFile.of();
        ClassModel m = cf0.parse(classFile);
        String self = m.thisClass().asInternalName();
        if (!list.listed(self)) return null;
        String binary = self.replace('/', '.');
        var added = list.fieldsOf(binary);
        for (var f : m.fields()) {
            if (f.fieldName().equalsString(MARKER) || added.stream().anyMatch(a -> f.fieldName().equalsString(a.name()))) return null;
        }
        boolean reads = false;
        for (MethodModel mm : m.methods()) {
            if (mm.methodName().equalsString("<clinit>")) continue;
            for (CodeModel code : mm.code().stream().toList())
                for (CodeElement e : code)
                    if (e instanceof FieldInstruction f && scratchK(f, list) >= 0) reads = true;
        }
        if (!reads && added.isEmpty()) return null;

        ClassLoader l = loader != null ? loader : ClassLoader.getSystemClassLoader();
        for (MethodModel mm : m.methods()) {
            if (mm.methodName().equalsString("<clinit>")) continue;
            for (CodeModel code : mm.code().stream().toList())
                for (CodeElement e : code)
                    if (e instanceof FieldInstruction f && scratchK(f, list) >= 0 && !typeAccessible(f.type().stringValue(), l))
                        return null;
        }

        ClassFile cf = classFile(l);
        ClassTransform bodies = ClassTransform.transformingMethodBodies(mm -> !mm.methodName().equalsString("<clinit>"), (b, e) -> {
            if (e instanceof FieldInstruction f && scratchK(f, list) >= 0) {
                b.loadConstant(scratchK(f, list)).invokestatic(SCRATCH, "get", GET).checkcast(ClassDesc.ofDescriptor(f.type().stringValue()));
            } else b.with(e);
        });
        ClassTransform extra = ClassTransform.endHandler(cb -> {
            for (var a : added) cb.withField(a.name(), ClassDesc.ofDescriptor(a.desc()), ClassFile.ACC_PUBLIC);
            cb.withField(MARKER, ConstantDescs.CD_int, fb -> fb
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL)
                .with(ConstantValueAttribute.of(cb.constantPool().intEntry(list.textHash()))));
        });
        return cf.transformClass(m, bodies.andThen(extra));
    }

    private static int scratchK(FieldInstruction f, WorkerList list) {
        if (f.opcode() != Opcode.GETSTATIC) return -1;
        return list.scratchIndex(f.owner().asInternalName(), f.name().stringValue(), f.type().stringValue());
    }

    /** Package-private: the stack-map resolver both transforms share. Never loads a class. */
    static ClassFile classFile(ClassLoader loader) {
        ClassLoader l = loader != null ? loader : ClassLoader.getSystemClassLoader();
        return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.ofResourceParsing(l).orElse(ClassHierarchyResolver.defaultResolver())));
    }

    private static boolean typeAccessible(String desc, ClassLoader l) {
        if (desc.startsWith("[")) return typeAccessible(desc.substring(1), l);
        if (desc.length() == 1) return true;
        String name = desc.substring(1, desc.length() - 1).replace('/', '.');
        try {
            Class<?> c = Class.forName(name, false, l);
            return java.lang.reflect.Modifier.isPublic(c.getModifiers());
        } catch (Throwable t) {
            return false;
        }
    }
}
