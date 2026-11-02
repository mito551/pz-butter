package pzbutter;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/** Plan 11e route C: generateHwId(Z)'s three GL11.glTexImage2D calls become TexCompress.texImage2D (same descriptor); nothing else changes. */
final class TexTransform implements ClassFileTransformer {
    static volatile boolean transformState = false;
    static volatile String transformReason = "";

    private static final ClassDesc TEXCOMPRESS = ClassDesc.of("pzbutter.TexCompress");

    TexTransform() {}

    @Override public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefining, ProtectionDomain pd, byte[] bytes) {
        try {
            if (className == null || !className.equals("zombie/core/textures/TextureID")) return null;
            byte[] out = transform(bytes, loader == null ? ClassLoader.getSystemClassLoader() : loader);
            if (out == null) { transformState = false; if (transformReason.isEmpty()) transformReason = "generateHwId not found"; }
            // TextureID loads before ZombieBuddy's patches and is retransformed for the generateHwId holder: this transformer is
            // retransform-capable, so it runs again there (after ZombieBuddy's, on its output)
            System.out.println("[PZButter] texTransform: TextureID " + (out != null ? "rewritten" : "untouched: " + transformReason) + (redefining != null ? " (retransform)" : " (load)"));
            return out;
        } catch (Throwable t) {
            transformState = false;
            transformReason = String.valueOf(t);
            try { System.out.println("[PZButter] texTransform " + t); } catch (Throwable ignored) {}
            return null;
        }
    }

    static int sites(MethodModel mm) {
        int[] n = {0};
        mm.code().ifPresent(c -> { for (var e : c)
            if (e instanceof InvokeInstruction inv
                    && inv.opcode() == Opcode.INVOKESTATIC
                    && inv.owner().asInternalName().equals("org/lwjgl/opengl/GL11")
                    && inv.name().stringValue().equals("glTexImage2D")
                    && inv.type().stringValue().equals("(IIIIIIIILjava/nio/ByteBuffer;)V")) n[0]++; });
        return n[0];
    }

    static byte[] transform(byte[] classFile) {
        return transform(classFile, ClassLoader.getSystemClassLoader());
    }

    static byte[] transform(byte[] classFile, ClassLoader loader) {
        ClassFile cf0 = ClassFile.of();
        ClassModel m = cf0.parse(classFile);
        if (!m.thisClass().asInternalName().equals("zombie/core/textures/TextureID")) return null;

        java.util.function.Predicate<MethodModel> methodPred = mm ->
            mm.methodName().equalsString("generateHwId") && mm.methodType().equalsString("(Z)V");
        var target = m.methods().stream().filter(methodPred).findFirst();
        if (target.isEmpty()) return null;
        int n = sites(target.get());
        if (n != 3) {
            transformState = false;
            transformReason = n + " glTexImage2D sites in generateHwId, expected 3";
            return null;
        }
        MethodTypeDesc invokeDesc = MethodTypeDesc.ofDescriptor("(IIIIIIIILjava/nio/ByteBuffer;)V");
        CodeTransform ct = new CodeTransform() {
            @Override public void accept(java.lang.classfile.CodeBuilder cb, CodeElement e) {
                if (e instanceof InvokeInstruction inv
                        && inv.opcode() == Opcode.INVOKESTATIC
                        && inv.owner().asInternalName().equals("org/lwjgl/opengl/GL11")
                        && inv.name().stringValue().equals("glTexImage2D")
                        && inv.type().stringValue().equals("(IIIIIIIILjava/nio/ByteBuffer;)V")) {
                    cb.invokestatic(TEXCOMPRESS, "texImage2D", invokeDesc);
                } else {
                    cb.with(e);
                }
            }
        };
        transformState = true;
        transformReason = "";
        return WorkerTransform.classFile(loader).transformClass(m, ClassTransform.transformingMethodBodies(methodPred, ct));
    }
}
