package pzbutter;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class CensusTestSynth {
    private CensusTestSynth() {}

    static byte[] classZ() {
        ClassDesc z = ClassDesc.of("t.Z");
        ClassDesc x = ClassDesc.of("java.util.Random");
        MethodTypeDesc intM = MethodTypeDesc.of(ConstantDescs.CD_int);

        return ClassFile.of().build(z, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withMethodBody("update", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> {
                b.invokestatic(x, "nextInt", intM).pop();
                b.return_();
            });
        });
    }

    static final String SITES = String.join("\n",
            "revision r",
            "class t.Z",
            "site 0 RNG t/Z update()V 0 invokestatic:java/util/Random.nextInt:()I"
    );

    static final String SITES_WRONG_OFFSET = String.join("\n",
            "revision r",
            "class t.Z",
            "site 0 RNG t/Z update()V 999 invokestatic:java/util/Random.nextInt:()I"
    );

    static boolean callsBefore(byte[] classFile, String method, String beforeOwner, String insertedOwner, String insertedName) throws Exception {
        ClassFile cf = ClassFile.of();
        var m = cf.parse(classFile);
        for (var mm : m.methods()) {
            if (!mm.methodName().stringValue().equals(method)) continue;
            if (mm.code().isEmpty()) continue;
            List<?> elems = mm.code().get().elementList();
            for (int i = 0; i + 1 < elems.size(); i++) {
                Object a = elems.get(i);
                Object b = elems.get(i + 1);
                if (a instanceof java.lang.classfile.Instruction ia && b instanceof java.lang.classfile.Instruction ib) {
                    if (ia instanceof java.lang.classfile.instruction.InvokeInstruction iia
                            && ib instanceof java.lang.classfile.instruction.InvokeInstruction iib) {
                        if (iia.owner().asInternalName().equals(insertedOwner) && iia.name().stringValue().equals(insertedName)
                                && iib.owner().asInternalName().equals(beforeOwner.replace('.', '/'))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    static List<String> listedClasses(CensusSites s) {
        java.util.Set<String> owners = new java.util.TreeSet<>();
        for (int i = 0; i < s.size(); i++) owners.add(s.site(i).owner());
        return new ArrayList<>(owners);
    }

    static byte[] gameBytes(String c) throws IOException {
        var is = ClassLoader.getSystemResourceAsStream(c + ".class");
        if (is == null) throw new IOException("missing " + c);
        try (is) {
            return is.readAllBytes();
        }
    }

    static List<java.lang.VerifyError> verifyResolved(byte[] bytes) {
        return WorkerTransform.classFile(ClassLoader.getSystemClassLoader()).verify(bytes);
    }

    static Path jar(Path p) throws IOException {
        ClassDesc z = ClassDesc.of("t.Z");
        ClassDesc h = ClassDesc.of("t.H");
        ClassDesc r = ClassDesc.of("t.R");
        ClassDesc x = ClassDesc.of("se.krka.kahlua.integration.LuaCaller");
        ClassDesc tlr = ClassDesc.of("java.util.concurrent.ThreadLocalRandom");
        ClassDesc obj = ConstantDescs.CD_Object;
        MethodTypeDesc voidM = MethodTypeDesc.of(ConstantDescs.CD_void);
        MethodTypeDesc intM = MethodTypeDesc.of(ConstantDescs.CD_int);

        byte[] cz = ClassFile.of().build(z, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("n", ConstantDescs.CD_int, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
            cb.withMethodBody("update", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_SYNCHRONIZED, b -> {
                b.invokestatic(x, "call", voidM);
                b.invokestatic(tlr, "current", MethodTypeDesc.of(tlr)).invokevirtual(tlr, "nextInt", intM).pop();
                b.iconst_1().putstatic(z, "n", ConstantDescs.CD_int);
                b.getstatic(r, "REV", obj).pop();
                b.return_();
            });
            cb.withMethodBody("preupdate", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> {
                b.invokestatic(h, "helper", voidM).return_();
            });
        });

        byte[] ch = ClassFile.of().build(h, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withMethodBody("helper", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> {
                b.invokestatic(tlr, "current", MethodTypeDesc.of(tlr)).pop();
                b.return_();
            });
        });

        byte[] cr = ClassFile.of().build(r, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withField("REV", obj, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL);
        });

        byte[] cx = ClassFile.of().build(x, cb -> {
            cb.withFlags(ClassFile.ACC_PUBLIC);
            cb.withMethodBody("call", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, b -> b.return_());
        });

        Files.createDirectories(p.getParent());
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var e : java.util.Map.of("t/Z.class", cz, "t/H.class", ch, "t/R.class", cr, "se/krka/kahlua/integration/LuaCaller.class", cx).entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
        return p;
    }
}