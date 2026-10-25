package pzbutter;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class CensusTest {
    private CensusTest() {}

    static void run() throws Exception {
        censusListFamilies();
        censusListNoDuplicateSites();
        censusListReviewReadsRealList();
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
}