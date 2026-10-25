package pzbutter;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;

/** Synthetic classes for the worker transform tests, built with java.lang.classfile at test time. */
final class WorkerTestSynth {
    private WorkerTestSynth() {}

    static final String LIST_TEXT = "revision r\nclass t.A\nscratch 0 t/A L Ljava/util/ArrayList;\nfield t.A pzbIdx I\n";
    static final String FRESH_LIST_TEXT = "revision r\nclass t.G\nfresh 0 t/G INSTANCE Lt/Gen; init\n";
    private static final ClassDesc AL = ClassDesc.of("java.util.ArrayList");
    private static final ClassDesc INT = ConstantDescs.CD_Integer;

    static WorkerList list() { return WorkerList.parse(LIST_TEXT); }

    static WorkerList listNoFields() { return WorkerList.parse("revision r\nclass t.A\nscratch 0 t/A L Ljava/util/ArrayList;\n"); }

    static byte[] classA() { return build("t.A", true, null); }

    static byte[] otherClass() { return build("t.Other", true, null); }

    static byte[] classWithField(String name) { return build("t.A", true, name); }

    static byte[] listedClassWithoutReads() { return build("t.A", false, null); }

    static Class<?> loadTransformed() throws Exception {
        byte[] b = WorkerTransform.transform(classA(), list());
        assert b != null;
        ClassLoader l = new ClassLoader(WorkerTestSynth.class.getClassLoader()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (!n.equals("t.A")) throw new ClassNotFoundException(n);
                return defineClass(n, b, 0, b.length);
            }
        };
        return l.loadClass("t.A");
    }

    static byte[] classG() {
        ClassDesc self = ClassDesc.of("t.G");
        ClassDesc gen = ClassDesc.of("t.Gen");
        return ClassFile.of().build(self, (ClassBuilder cb) -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("INSTANCE", gen, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            cb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, b -> b
                .aload(0).invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void)).return_());
            cb.withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, b -> b
                .new_(gen).dup().invokespecial(gen, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
                .putstatic(self, "INSTANCE", gen).return_());
            cb.withMethodBody("get", MethodTypeDesc.of(gen), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b
                .getstatic(self, "INSTANCE", gen).areturn());
        });
    }

    static byte[] classGen() {
        ClassDesc self = ClassDesc.of("t.Gen");
        return ClassFile.of().build(self, (ClassBuilder cb) -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("value", ConstantDescs.CD_Object, ClassFile.ACC_PUBLIC);
            cb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PROTECTED, b -> b
                .aload(0).invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void)).return_());
            cb.withMethodBody("init", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, b -> b
                .aload(0).new_(ConstantDescs.CD_Object).dup().invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
                .putfield(self, "value", ConstantDescs.CD_Object).return_());
        });
    }

    static Class<?> loadFreshTransformed() throws Exception {
        ClassLoader tl = new ClassLoader(WorkerTestSynth.class.getClassLoader()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (n.equals("t.Gen")) { byte[] b = classGen(); return defineClass(n, b, 0, b.length); }
                throw new ClassNotFoundException(n);
            }
        };
        byte[] g = WorkerTransform.transform(classG(), WorkerList.parse(FRESH_LIST_TEXT), tl);
        assert g != null;
        ClassLoader l = new ClassLoader(WorkerTestSynth.class.getClassLoader()) {
            @Override protected Class<?> findClass(String n) throws ClassNotFoundException {
                if (n.equals("t.G")) return defineClass(n, g, 0, g.length);
                if (n.equals("t.Gen")) { byte[] b = classGen(); return defineClass(n, b, 0, b.length); }
                throw new ClassNotFoundException(n);
            }
        };
        return l.loadClass("t.G");
    }

    private static byte[] build(String name, boolean reads, String extraField) {
        ClassDesc self = ClassDesc.of(name);
        return ClassFile.of().build(self, (ClassBuilder cb) -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("L", AL, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            if (extraField != null) cb.withField(extraField, ConstantDescs.CD_int, ClassFile.ACC_PUBLIC);
            cb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, b -> b
                .aload(0).invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void)).return_());
            cb.withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, b -> b
                .new_(AL).dup().invokespecial(AL, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void))
                .putstatic(self, "L", AL).return_());
            cb.withMethodBody("sum", MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                b -> { if (reads) sum(b, self); else b.iconst_0().ireturn(); });
        });
    }

    // L.clear(); for (i = 0; i < k; i++) L.add(i); int s = 0; for (j = 0; j < L.size(); j++) s += L.get(j); return s;
    private static void sum(CodeBuilder b, ClassDesc self) {
        Label top = b.newLabel(), end = b.newLabel(), top2 = b.newLabel(), end2 = b.newLabel();
        b.getstatic(self, "L", AL).invokevirtual(AL, "clear", MethodTypeDesc.of(ConstantDescs.CD_void));
        b.iconst_0().istore(1);
        b.labelBinding(top).iload(1).iload(0).if_icmpge(end);
        b.getstatic(self, "L", AL).iload(1).invokestatic(INT, "valueOf", MethodTypeDesc.of(INT, ConstantDescs.CD_int))
            .invokevirtual(AL, "add", MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object)).pop();
        b.iinc(1, 1).goto_(top).labelBinding(end);
        b.iconst_0().istore(2).iconst_0().istore(3);
        b.labelBinding(top2).iload(3).getstatic(self, "L", AL).invokevirtual(AL, "size", MethodTypeDesc.of(ConstantDescs.CD_int)).if_icmpge(end2);
        b.iload(2).getstatic(self, "L", AL).iload(3).invokevirtual(AL, "get", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int))
            .checkcast(INT).invokevirtual(INT, "intValue", MethodTypeDesc.of(ConstantDescs.CD_int)).iadd().istore(2);
        b.iinc(3, 1).goto_(top2).labelBinding(end2).iload(2).ireturn();
    }

    /** A jar with t.A (L: ArrayList, S: String, counter: int; m() reads L and S, writes counter, calls t.B.n()), t.B (V: t.V) and t.V. */
    static java.nio.file.Path writeScratchJar(java.nio.file.Path jar) throws java.io.IOException {
        ClassDesc a = ClassDesc.of("t.A"), bb = ClassDesc.of("t.B"), cc = ClassDesc.of("t.C"), v = ClassDesc.of("t.V");
        MethodTypeDesc voidM = MethodTypeDesc.of(ConstantDescs.CD_void);
        int sf = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;
        byte[] ca = ClassFile.of().build(a, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("L", AL, sf);
            cb.withField("S", ConstantDescs.CD_String, sf);
            cb.withField("counter", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
            cb.withMethodBody("<clinit>", voidM, ClassFile.ACC_STATIC, b -> b
                .new_(AL).dup().invokespecial(AL, "<init>", voidM).putstatic(a, "L", AL)
                .ldc("s").putstatic(a, "S", ConstantDescs.CD_String).return_());
            cb.withMethodBody("m", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b
                .getstatic(a, "L", AL).invokevirtual(AL, "clear", voidM)
                .getstatic(a, "S", ConstantDescs.CD_String).pop()
                .iconst_1().putstatic(a, "counter", ConstantDescs.CD_int)
                .invokestatic(bb, "n", voidM).invokestatic(cc, "p", voidM).return_());
        });
        byte[] cbb = ClassFile.of().build(bb, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("V", v, sf);
            cb.withMethodBody("<clinit>", voidM, ClassFile.ACC_STATIC, b -> b
                .new_(v).dup().invokespecial(v, "<init>", voidM).putstatic(bb, "V", v).return_());
            cb.withMethodBody("n", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b
                .getstatic(bb, "V", v).iconst_1().putfield(v, "x", ConstantDescs.CD_int).return_());
        });
        byte[] ccb = ClassFile.of().build(cc, cb -> {   // reads t.B.V through the subclass name
            cb.withFlags(ClassFile.ACC_PUBLIC).withSuperclass(bb);
            cb.withMethodBody("p", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b
                .getstatic(cc, "V", v).iconst_2().putfield(v, "x", ConstantDescs.CD_int).return_());
        });
        ClassDesc d = ClassDesc.of("t.D"), fa2 = ConstantDescs.CD_float.arrayType(2), ia = ConstantDescs.CD_int.arrayType();
        byte[] cd = ClassFile.of().build(d, cb -> {   // G: 2-D, outer aastore; H: 1-D, only arraycopy into it
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("G", fa2, sf);
            cb.withField("H", ia, sf);
            cb.withMethodBody("<clinit>", voidM, ClassFile.ACC_STATIC, b -> b
                .iconst_1().anewarray(ConstantDescs.CD_float.arrayType()).putstatic(d, "G", fa2)
                .iconst_1().newarray(java.lang.classfile.TypeKind.INT).putstatic(d, "H", ia).return_());
            cb.withMethodBody("q", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b
                .getstatic(d, "G", fa2).iconst_0().aconst_null().aastore()
                .getstatic(d, "H", ia).iconst_0().getstatic(d, "H", ia).iconst_0().iconst_1()
                .invokestatic(ClassDesc.of("java.lang.System"), "arraycopy", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object,
                    ConstantDescs.CD_int, ConstantDescs.CD_Object, ConstantDescs.CD_int, ConstantDescs.CD_int)).return_());
        });
        byte[] cv = ClassFile.of().build(v, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("x", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC);
            cb.withMethodBody("<init>", voidM, ClassFile.ACC_PUBLIC, b -> b
                .aload(0).invokespecial(ConstantDescs.CD_Object, "<init>", voidM).return_());
        });
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (var e : java.util.Map.of("t/A.class", ca, "t/B.class", cbb, "t/C.class", ccb, "t/D.class", cd, "t/V.class", cv).entrySet()) {
                z.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return jar;
    }

    /** A package-private value type (not public), with no usable public constructor expectation. */
    static byte[] nonPublicTypeClass() { return typeClass("t.NonPublicType", 0); }
    /** A public value type with a public no-arg constructor. */
    static byte[] publicTypeClass() { return typeClass("t.PublicType", ClassFile.ACC_PUBLIC); }

    private static byte[] typeClass(String name, int classFlags) {
        ClassDesc self = ClassDesc.of(name);
        int ctorFlags = ((classFlags & ClassFile.ACC_PUBLIC) != 0) ? ClassFile.ACC_PUBLIC : 0;
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(classFlags);
            cb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ctorFlags, b -> b
                .aload(0).invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void)).return_());
        });
    }

    /** A reader class (t.Rdr) exposing `static final F` of `typeName`, read in `read()`. */
    static byte[] readerWithType(String typeName) {
        ClassDesc self = ClassDesc.of("t.Rdr");
        ClassDesc type = ClassDesc.of(typeName);
        int sf = ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL;
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("F", type, sf);
            cb.withMethodBody("<init>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, b -> b
                .aload(0).invokespecial(ConstantDescs.CD_Object, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void)).return_());
            cb.withMethodBody("read", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b
                .getstatic(self, "F", type).pop().return_());
        });
    }

    static final String ARR_LIST = "revision r\nclass t.Arr\nscratch 0 t/Arr X [I\n";

    /** t.Arr with `static final int[] X` and `static int len()` returning X.length. */
    static byte[] classArr() {
        ClassDesc self = ClassDesc.of("t.Arr"), ia = ConstantDescs.CD_int.arrayType();
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("X", ia, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
            cb.withMethodBody("<clinit>", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, b -> b
                .iconst_3().newarray(java.lang.classfile.TypeKind.INT).putstatic(self, "X", ia).return_());
            cb.withMethodBody("len", MethodTypeDesc.of(ConstantDescs.CD_int), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b
                .getstatic(self, "X", ia).arraylength().ireturn());
        });
    }
}
