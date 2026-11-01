package pzbutter;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Rewrites every Thread.sleep(J)V inside WorldStreamer.threadLoop to StreamWake.sleep(J)V; nothing else changes. */
final class WakeTransform implements ClassFileTransformer {
    private static final ClassDesc STREAMWAKE = ClassDesc.of("pzbutter.StreamWake");
    private static final MethodTypeDesc SLEEP = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_long);

    WakeTransform() {}

    @Override public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefining, ProtectionDomain pd, byte[] bytes) {
        try {
            if (redefining != null || className == null || !className.equals("zombie/iso/WorldStreamer")) return null;
            return transform(bytes, loader == null ? ClassLoader.getSystemClassLoader() : loader);
        } catch (Throwable t) {
            try {
                System.out.println("[PZButter] wakeTransform " + t);
            } catch (Throwable ignored) {}
            return null;
        }
    }

    static byte[] transform(byte[] classFile) {
        return transform(classFile, ClassLoader.getSystemClassLoader());
    }

    static byte[] transform(byte[] classFile, ClassLoader loader) {
        ClassFile cf0 = ClassFile.of();
        ClassModel m = cf0.parse(classFile);
        if (!m.thisClass().asInternalName().equals("zombie/iso/WorldStreamer")) return null;

        ClassFile cf = WorkerTransform.classFile(loader);
        java.util.function.Predicate<MethodModel> methodPred = mm ->
            mm.methodName().equalsString("threadLoop") && mm.methodType().equalsString("()V");
        CodeTransform ct = (cb, e) -> {
            if (e instanceof InvokeInstruction inv
                    && inv.opcode() == Opcode.INVOKESTATIC
                    && inv.owner().asInternalName().equals("java/lang/Thread")
                    && inv.name().stringValue().equals("sleep")
                    && inv.type().stringValue().equals("(J)V")) {
                cb.invokestatic(STREAMWAKE, "sleep", SLEEP);
            } else {
                cb.with(e);
            }
        };
        return cf.transformClass(m, ClassTransform.transformingMethodBodies(methodPred, ct));
    }
}