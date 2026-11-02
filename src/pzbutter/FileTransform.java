package pzbutter;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Rewrites the two bipush 16 in-flight limits in FileSystemImpl.updateAsyncTransactions to FileKnobs.inflight(); nothing else changes. */
final class FileTransform implements ClassFileTransformer {
    private static final ClassDesc FILEKNOBS = ClassDesc.of("pzbutter.FileKnobs");
    private static final MethodTypeDesc INFLIGHT = MethodTypeDesc.of(ConstantDescs.CD_int);

    static volatile boolean transformState = false;
    static volatile String transformReason = "";

    FileTransform() {}

    @Override public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefining, ProtectionDomain pd, byte[] bytes) {
        try {
            if (className == null || !className.equals("zombie/fileSystem/FileSystemImpl")) return null;
            byte[] out = transform(bytes, loader == null ? ClassLoader.getSystemClassLoader() : loader);
            if (out == null) { transformState = false; transformReason = "updateAsyncTransactions not found"; }
            return out;
        } catch (Throwable t) {
            transformState = false;
            transformReason = String.valueOf(t);
            try { System.out.println("[PZButter] fileTransform " + t); } catch (Throwable ignored) {}
            return null;
        }
    }

    static byte[] transform(byte[] classFile) {
        return transform(classFile, ClassLoader.getSystemClassLoader());
    }

    static byte[] transform(byte[] classFile, ClassLoader loader) {
        ClassFile cf0 = ClassFile.of();
        ClassModel m = cf0.parse(classFile);
        if (!m.thisClass().asInternalName().equals("zombie/fileSystem/FileSystemImpl")) return null;

        java.util.function.Predicate<MethodModel> methodPred = mm ->
            mm.methodName().equalsString("updateAsyncTransactions") && mm.methodType().equalsString("()V");
        if (m.methods().stream().noneMatch(methodPred)) return null;
        CodeTransform ct = new CodeTransform() {
            @Override public void accept(java.lang.classfile.CodeBuilder cb, CodeElement e) {
                if (e instanceof ConstantInstruction ci
                        && ci.opcode() == Opcode.BIPUSH
                        && ci.constantValue() instanceof Integer i
                        && i == 16) {
                    cb.invokestatic(FILEKNOBS, "inflight", INFLIGHT);
                } else {
                    cb.with(e);
                }
            }
        };
        return WorkerTransform.classFile(loader).transformClass(m, ClassTransform.transformingMethodBodies(methodPred, ct));
    }
}