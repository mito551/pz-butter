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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class CensusTestSynth {
    private CensusTestSynth() {}

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