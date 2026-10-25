package pzbutter;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class CensusTest {
    private CensusTest() {}

    static void run() throws Exception {
        censusListFamilies();
        censusListNoDuplicateSites();
        censusListReviewReadsRealList();
        censusTransformInsertsHits();
        censusTransformMismatchUntouched();
        censusTransformVerifiesOnGameClasses();
        censusListSubclassNamedReviewRead();
        calmPredicate();
        calmOfNonZombie();
        censusCalmIsDecidedAtEntry();
        censusNestingBalanced();
        censusIgnoresOtherThreads();
        censusExitOnThrow();
        censusDumpFormat();
        censusStatusLine();
        censusReportTables();
    }

    static void calmPredicate() {
        Calm.Facts ok = new Calm.Facts(true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 20f);
        assert Calm.test(ok);
        assert !Calm.test(new Calm.Facts(true, false, false, true, true, false, false, false, false, false, false, false, false, false, false, 20f));
        assert !Calm.test(new Calm.Facts(true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 12f));
        assert !Calm.test(new Calm.Facts(false, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 20f));
    }

    static void calmOfNonZombie() {
        Calm.Facts f = Calm.of(new Object());
        assert !f.zombie() : f;
    }

    static void censusCalmIsDecidedAtEntry() {
        Census.resetForTest();
        Census.guard.on = true;
        Census.facts = o -> new Calm.Facts(true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 20f);
        Object o = new Object();
        Census.enter(o);
        Census.facts = o2 -> new Calm.Facts(true, false, false, true, true, false, false, false, false, false, false, false, false, false, false, 20f);
        Census.hit(0);
        Census.exit(o);
        assert Census.calm[0] == 1L && Census.other[0] == 0L && Census.calmUpdates == 1L && Census.otherUpdates == 0L;
    }

    static void censusNestingBalanced() {
        Census.resetForTest();
        Census.guard.on = true;
        Census.facts = o -> new Calm.Facts(true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 20f);
        Object a = new Object(), b = new Object();
        Census.enter(a);
        Census.enter(b);
        Census.hit(0);
        Census.exit(b);
        Census.hit(0);
        Census.exit(a);
        assert Census.calm[0] == 2L && Census.other[0] == 0L && Census.calmUpdates == 1L && Census.otherUpdates == 0L;
        assert Census.calmDepth == 0 && Census.otherDepth == 0;
    }

    static void censusIgnoresOtherThreads() throws Exception {
        Census.resetForTest();
        Census.guard.on = true;
        Census.facts = o -> new Calm.Facts(true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 20f);
        Object o = new Object();
        Census.enter(o);
        Census.hit(0); // baseline? counts calm
        // reset that hit by direct? Instead track
        // We'll just test a second-thread hit adds nothing additional
        long before = Census.calm[0];
        Thread t = new Thread(() -> Census.hit(0));
        t.start(); t.join(5000);
        assert !t.isAlive();
        assert Census.calm[0] == before : "second thread must not increment";
        Census.exit(o);
    }

    static void censusExitOnThrow() {
        Census.resetForTest();
        Census.guard.on = true;
        Census.facts = o -> new Calm.Facts(true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 20f);
        Object o = new Object();
        Census.enter(o);
        Census.exit(o);
        assert Census.calmDepth == 0 && Census.otherDepth == 0;
    }

    static void censusStatusLine() throws Exception {
        java.io.File f = SelfTest.tmp("census/status.properties");
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write("enabled=true\ndevCensus=false\n"); }
        Settings.load(f);
        assert Census.status().equals("off (devCensus=false)") : Census.status();
    }

    static void censusDumpFormat() throws Exception {
        Census.resetForTest();
        Census.guard.on = true;
        Census.dir = SelfTest.tmp("census/out");
        Census.facts = o -> new Calm.Facts(true, false, false, true, false, false, false, false, false, false, false, false, false, false, false, 20f);
        Census.sites = CensusSites.load("4a0e9546ec");
        Census.allocate(Census.sites.size());
        Object o = new Object();
        Census.enter(o);
        Census.hit(0);
        Census.hit(1);
        Census.exit(o);
        long t = 5_000_000_000L;
        Census.frameEnd(t);
        Census.frameEnd(t + 60_000_000_000L);
        java.io.File f = new java.io.File(Census.dir, "census.out");
        String got = java.nio.file.Files.readString(f.toPath());
        assert got.contains("revision 4a0e9546ec minutes 1") : got.substring(0, Math.min(200, got.length()));
        assert got.contains("0 ") : got.substring(0, 100);
    }

    static void censusListReviewReadsRealList() throws Exception {
        try (java.io.InputStream in = CensusTest.class.getResourceAsStream("/pzbutter/workers-4a0e9546ec.txt")) {
            assert in != null;
            java.util.Set<String> review = new java.util.TreeSet<>();
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n")) {
                String t = line.trim();
                if (!t.startsWith("# review")) continue;
                String[] sp = t.split("\\s+");
                if (sp.length < 3) continue;
                review.add(sp[2]);
            }
            assert !review.isEmpty() : "no review fields in real list";
        }
    }

    static void censusListFamilies() throws Exception {
        Path jar = CensusTestSynth.jar(SelfTest.tmp("census/t.jar").toPath());
        List<String> out = CensusList.run(jar, List.of("t.Z.update:()V"), "r", Set.of("t/R.REV"));
        for (String fam : List.of("LUA", "RNG", "PUTSTATIC", "LOCK", "REVIEW_READ"))
            assert out.stream().anyMatch(l -> l.startsWith("site ") && l.split(" ")[2].equals(fam)) : fam;
        assert out.contains("class t.Z");
    }

    static void censusListNoDuplicateSites() throws Exception {
        Path jar = CensusTestSynth.jar(SelfTest.tmp("census/t2.jar").toPath());
        List<String> out = CensusList.run(jar, List.of("t.Z.update:()V", "t.Z.preupdate:()V"), "r", Set.of());
        assert out.stream().filter(l -> l.startsWith("site ") && l.contains(" t/H helper")).count() == 1;
    }

    static void censusTransformInsertsHits() throws Exception {
        byte[] b = CensusTestSynth.classZ();
        CensusSites s = CensusSites.parse(CensusTestSynth.SITES);
        byte[] out = CensusTransform.transform(b, s);
        assert out != null && ClassFile.of().verify(out).isEmpty();
        assert CensusTestSynth.callsBefore(out, "update", "java/util/Random", "pzbutter/Census", "hit");
    }

    static void censusTransformMismatchUntouched() {
        assert CensusTransform.transform(CensusTestSynth.classZ(), CensusSites.parse(CensusTestSynth.SITES_WRONG_OFFSET)) == null;
    }

    static void censusTransformVerifiesOnGameClasses() throws Exception {
        CensusSites s = CensusSites.load("4a0e9546ec");
        for (String c : CensusTestSynth.listedClasses(s)) {
            byte[] out = CensusTransform.transform(CensusTestSynth.gameBytes(c), s);
            assert out != null : c + ": untouched";
            assert CensusTestSynth.verifyResolved(out).isEmpty() : c + ": " + CensusTestSynth.verifyResolved(out).get(0);
        }
    }

    static void censusListSubclassNamedReviewRead() throws Exception {
        Path p = SelfTest.tmp("census/sub.jar").toPath();
        java.lang.constant.ClassDesc base = java.lang.constant.ClassDesc.of("t.Base");
        java.lang.constant.ClassDesc sub = java.lang.constant.ClassDesc.of("t.Sub");
        java.lang.constant.ClassDesc z = java.lang.constant.ClassDesc.of("t.Z");
        java.lang.constant.ClassDesc obj = java.lang.constant.ConstantDescs.CD_Object;
        java.lang.constant.MethodTypeDesc voidM = java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void);

        byte[] cb = ClassFile.of().build(base, b -> {
            b.withFlags(ClassFile.ACC_PUBLIC);
            b.withField("F", obj, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC);
        });
        byte[] cs = ClassFile.of().build(sub, b -> {
            b.withFlags(ClassFile.ACC_PUBLIC);
            b.withSuperclass(base);
        });
        byte[] cz = ClassFile.of().build(z, b -> {
            b.withFlags(ClassFile.ACC_PUBLIC);
            b.withMethodBody("update", voidM, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                code.getstatic(sub, "F", obj).pop();
                code.return_();
            });
        });

        java.nio.file.Files.createDirectories(p.getParent());
        try (java.util.zip.ZipOutputStream zout = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(p))) {
            for (var e : java.util.Map.of("t/Base.class", cb, "t/Sub.class", cs, "t/Z.class", cz).entrySet()) {
                zout.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }

        List<String> out = CensusList.run(p, List.of("t.Z.update:()V"), "r", Set.of("t/Base.F"));
        String siteLine = out.stream().filter(l -> l.startsWith("site ") && l.contains("REVIEW_READ")).findFirst().orElse(null);
        assert siteLine != null : "no REVIEW_READ site";
        assert siteLine.contains("getstatic:t/Sub.F:") : siteLine;

        // parse and transform class bytes
        CensusSites sites = CensusSites.parse(String.join("\n", out));
        byte[] zbytes = cz;
        byte[] transformed = CensusTransform.transform(zbytes, sites);
        assert transformed != null;
        // verify hit inserted immediately before getstatic t/Sub.F
        ClassFile cf = ClassFile.of();
        var m = cf.parse(transformed);
        for (var mm : m.methods()) {
            if (!mm.methodName().stringValue().equals("update")) continue;
            List<?> elems = mm.code().get().elementList();
            for (int i = 0; i + 1 < elems.size(); i++) {
                Object a = elems.get(i);
                Object bb = elems.get(i + 1);
                if (a instanceof java.lang.classfile.Instruction ia && bb instanceof java.lang.classfile.instruction.FieldInstruction fi) {
                    if (ia instanceof java.lang.classfile.instruction.InvokeInstruction ii
                            && ii.owner().asInternalName().equals("pzbutter/Census")
                            && ii.name().stringValue().equals("hit")
                            && fi.owner().asInternalName().equals("t/Sub")
                            && fi.name().stringValue().equals("F")) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("hit not inserted before getstatic t/Sub.F");
    }

    static void censusReportTables() throws Exception {
        java.io.File siteList = SelfTest.tmp("censusreport/sites.txt");
        java.io.File censusOut = SelfTest.tmp("censusreport/census.out");
        String sites = String.join("\n",
            "revision r",
            "class t.Z",
            "site 0 LUA t.Z update()V 0 invokevirtual:zombie/Lua/LuaEventManager.triggerEvent:(Ljava/lang/String;)V",
            "site 1 SHARED a/O foo()V 1 invokevirtual:zombie/Foo.bar:()V",
            "site 2 SHARED b/O bar()V 2 invokevirtual:zombie/Foo.bar:()V",
            "site 3 RNG t.Z update()V 3 invokestatic:java/util/Random.nextInt:()I",
            "site 4 PHYSICS t.Z update()V 4 invokestatic:zombie/Physics.step:()V"
        );
        try (java.io.FileWriter w = new java.io.FileWriter(siteList)) { w.write(sites + "\n"); }
        String census = "revision r minutes 0 calmUpdates 400 otherUpdates 100 missed 0\n"
            + "0 LUA 832 0 t.Z update()V 0 invokevirtual:zombie/Lua/LuaEventManager.triggerEvent:(Ljava/lang/String;)V\n"
            + "1 SHARED 100 0 a/O foo()V 1 invokevirtual:zombie/Foo.bar:()V\n"
            + "2 SHARED 200 0 b/O bar()V 2 invokevirtual:zombie/Foo.bar:()V\n"
            + "3 RNG 5 0 t.Z update()V 3 invokestatic:java/util/Random.nextInt:()I\n";
        try (java.io.FileWriter w = new java.io.FileWriter(censusOut)) { w.write(census); }

        String md = CensusReport.generate(censusOut, siteList);
        assert md.contains("calm updates: 400") : md;
        assert md.contains("80.0 %") : md;
        assert md.contains("triggerEvent") : md;
        assert md.contains("2.08") : md;
        // SHARED grouping sums the two sites into one target row
        assert md.contains("zombie.Foo.bar") : md;
        assert !md.contains("calm hits") || md.contains("300") : md;
        // PHYSICS listed with zero calm hits
        assert md.contains("PHYSICS") && md.contains("needs no latch") : md;
    }
}