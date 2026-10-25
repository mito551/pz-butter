package pzbutter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Offline tool (test tree, never shipped): turns a {@code census.out} plus the site list it was
 * generated from into a Markdown report. Usage:
 * {@code java -cp build/test-classes:<game jars> pzbutter.CensusReport <census.out> <site list> <out.md>}.
 */
final class CensusReport {
    private CensusReport() {}

    record SiteCount(int id, String family, long calm, long other,
                     String owner, String method, int offset, String target) {}

    private static Path asPath(Object o) {
        if (o instanceof Path p) return p;
        if (o instanceof File f) return f.toPath();
        return Path.of(String.valueOf(o));
    }

    static String generate(Object censusOut, Object siteList) throws Exception {
        return generate(asPath(censusOut), asPath(siteList));
    }

    static String generate(Path censusOut, Path siteList) throws Exception {
        String censusText = Files.readString(censusOut, StandardCharsets.UTF_8);
        CensusSites sites = CensusSites.parse(Files.readString(siteList, StandardCharsets.UTF_8));

        // header
        long minutes = 0, calmUpdates = 0, otherUpdates = 0, missed = 0;
        String revision = sites.revision();
        String[] hdr = censusText.lines().findFirst().orElse("").trim().split("\\s+");
        // header format: revision X minutes N calmUpdates N otherUpdates N missed N
        for (int i = 0; i + 1 < hdr.length; i += 2) {
            String key = hdr[i];
            String val = hdr[i + 1];
            if (key.equals("revision")) revision = val;
            else if (key.equals("minutes")) minutes = Long.parseLong(val);
            else if (key.equals("calmUpdates")) calmUpdates = Long.parseLong(val);
            else if (key.equals("otherUpdates")) otherUpdates = Long.parseLong(val);
            else if (key.equals("missed")) missed = Long.parseLong(val);
        }

        Map<Integer, SiteCount> counts = new LinkedHashMap<>();
        for (String raw : censusText.lines().skip(1).toList()) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            String[] p = line.split(" ", 8);
            if (p.length < 8) continue;
            int id = Integer.parseInt(p[0]);
            String family = p[1];
            long calm = Long.parseLong(p[2]);
            long other = Long.parseLong(p[3]);
            String owner = p[4];
            String method = p[5];
            int offset = Integer.parseInt(p[6]);
            String target = p[7];
            counts.put(id, new SiteCount(id, family, calm, other, owner, method, offset, target));
        }

        // families present in site list
        Set<String> families = new TreeSet<>();
        for (int i = 0; i < sites.size(); i++) families.add(sites.site(i).family());

        // join site list metadata when census line lacks it
        // build per-site resolved record using site list when available
        List<SiteCount> rows = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            CensusSites.Site st = sites.site(i);
            SiteCount c = counts.get(i);
            long calm = c == null ? 0 : c.calm();
            long other = c == null ? 0 : c.other();
            String family = c == null ? st.family() : c.family();
            String owner = c == null ? st.owner() : c.owner();
            String method = c == null ? st.method() : c.method();
            int offset = c == null ? st.offset() : c.offset();
            String target = c == null ? st.target() : c.target();
            rows.add(new SiteCount(i, family, calm, other, owner, method, offset, target));
        }

        long totalUpdates = calmUpdates + otherUpdates;
        double calmShare = totalUpdates > 0 ? (100.0 * calmUpdates / totalUpdates) : 0.0;

        // families with zero calm hits
        List<String> zeroCalm = new ArrayList<>();
        for (String f : families) {
            long totalCalm = 0;
            for (SiteCount r : rows) if (r.family().equals(f)) totalCalm += r.calm();
            if (totalCalm == 0) zeroCalm.add(f);
        }

        StringBuilder md = new StringBuilder();
        md.append("# PZ Butter census report\n\n");
        md.append("revision: ").append(revision).append("\n\n");
        md.append("## Summary\n\n");
        md.append("- calm updates: ").append(calmUpdates).append("\n");
        md.append("- other updates: ").append(otherUpdates).append("\n");
        md.append("- total updates: ").append(totalUpdates).append("\n");
        md.append("- calm share: ").append(String.format(Locale.ROOT, "%.1f %%", calmShare))
           .append(" (").append(calmUpdates).append("/").append(totalUpdates).append(", ")
           .append(String.format(Locale.ROOT, "%.1f %%", calmShare)).append(")\n");
        md.append("- minutes: ").append(minutes).append("\n");
        if (zeroCalm.isEmpty()) {
            md.append("- families with zero calm hits (needs no latch): (none)\n");
        } else {
            md.append("- families with zero calm hits (needs no latch): ").append(String.join(", ", zeroCalm)).append("\n");
        }
        md.append("- missed classes: ").append(missed).append("\n\n");

        // per family
        for (String fam : families) {
            List<SiteCount> fr = new ArrayList<>();
            for (SiteCount r : rows) if (r.family().equals(fam)) fr.add(r);
            long calmTotal = 0;
            int hitSites = 0;
            for (SiteCount r : fr) { if (r.calm() > 0) { hitSites++; calmTotal += r.calm(); } }
            md.append("## ").append(fam).append("\n\n");
            md.append("- sites hit on calm updates: ").append(hitSites).append("\n");
            double perCalm = calmUpdates > 0 ? ((double) calmTotal / calmUpdates) : 0.0;
            md.append("- calm hits per calm update: ").append(fmtRate(perCalm)).append("\n");

            // top 15 by calm hits
            fr.sort((a, b) -> Long.compare(b.calm(), a.calm()));
            md.append("\n### top 15 sites by calm hits\n\n");
            md.append("| owner.method | offset | target | calm hits |\n");
            md.append("|---|---:|---|---:|\n");
            int n = 0;
            for (SiteCount r : fr) {
                if (r.calm() <= 0) continue;
                if (n++ >= 15) break;
                md.append("| ").append(dot(r.owner())).append(".").append(methodName(r.method()))
                   .append(" | ").append(r.offset())
                   .append(" | ").append(r.target())
                   .append(" | ").append(r.calm()).append(" |\n");
            }
            md.append("\n");
        }

        // sites hit only in other updates, count per family
        md.append("## Sites hit only in other updates (count per family)\n\n");
        md.append("| family | sites |\n");
        md.append("|---|---:|\n");
        Map<String, Integer> otherOnly = new TreeMap<>();
        for (String f : families) otherOnly.put(f, 0);
        for (SiteCount r : rows) {
            if (r.calm() == 0 && r.other() > 0) {
                otherOnly.merge(r.family(), 1, Integer::sum);
            }
        }
        for (var e : otherOnly.entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        md.append("\n");

        // REVIEW_READ fields
        md.append("## REVIEW_READ fields read on calm updates\n\n");
        md.append("| field | hits per calm update | reading methods |\n");
        md.append("|---|---:|---|\n");
        // group by target (field)
        Map<String, List<SiteCount>> byField = new LinkedHashMap<>();
        for (SiteCount r : rows) {
            if (!r.family().equals("REVIEW_READ")) continue;
            byField.computeIfAbsent(r.target(), k -> new ArrayList<>()).add(r);
        }
        for (var e : byField.entrySet()) {
            String target = e.getKey();
            List<SiteCount> list = e.getValue();
            long total = 0;
            for (SiteCount r : list) total += r.calm();
            double per = calmUpdates > 0 ? ((double) total / calmUpdates) : 0.0;
            List<String> methods = new ArrayList<>();
            for (SiteCount r : list) methods.add(dot(r.owner()) + "." + methodName(r.method()));
            md.append("| ").append(fieldOf(target))
               .append(" | ").append(fmtRate(per))
               .append(" | ").append(String.join(", ", methods))
               .append(" |\n");
        }
        md.append("\n");
        // full target details appendix
        md.append("## Target details (REVIEW_READ)\n\n");
        for (var e : byField.entrySet()) {
            md.append("- ").append(e.getKey()).append("\n");
        }
        md.append("\n");

        // grouping SHARED and LUA calm hits by target method
        for (String fam : List.of("SHARED", "LUA")) {
            md.append("## Calm hits by target method (").append(fam).append(")\n\n");
            md.append("| target method | calm hits |\n");
            md.append("|---|---:|\n");
            Map<String, Long> byTarget = new TreeMap<>();
            for (SiteCount r : rows) {
                if (!r.family().equals(fam)) continue;
                if (r.calm() <= 0) continue;
                String tm = targetMethod(r.target());
                byTarget.merge(tm, r.calm(), Long::sum);
            }
            for (var e : byTarget.entrySet()) {
                md.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
            }
            md.append("\n");
        }

        // missed classes
        md.append("## missed classes\n\n");
        md.append(missed).append("\n");

        return md.toString();
    }

    private static String dot(String internal) {
        return internal.replace('/', '.');
    }

    private static String methodName(String method) {
        int p = method.indexOf('(');
        return p >= 0 ? method.substring(0, p) : method;
    }

    private static String fmtRate(double v) {
        return String.format(Locale.ROOT, "%.3g", v);
    }

    private static String targetMethod(String target) {
        // target like opcode:owner.method:desc
        String s = target;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int lastColon = s.lastIndexOf(':');
        if (lastColon >= 0) s = s.substring(0, lastColon);
        return s.replace('/', '.');
    }

    private static String fieldOf(String target) {
        // getstatic:owner.field:desc -> owner.field
        return targetMethod(target); // same shape: owner.name without desc
    }

    // convenience aliases
    static String run(Object censusOut, Object siteList) throws Exception {
        return generate(censusOut, siteList);
    }
    static String run(Path censusOut, Path siteList) throws Exception {
        return generate(censusOut, siteList);
    }
    static String run(File censusOut, File siteList) throws Exception {
        return generate(censusOut, siteList);
    }
    static String run(String censusOut, String siteList) throws Exception {
        return generate(censusOut, siteList);
    }

    static void run(Object censusOut, Object siteList, Object out) throws Exception {
        write(censusOut, siteList, out);
    }
    static void run(Path censusOut, Path siteList, Path out) throws Exception {
        write(censusOut, siteList, out);
    }
    static void run(File censusOut, File siteList, File out) throws Exception {
        write(censusOut, siteList, out);
    }
    static void run(String censusOut, String siteList, String out) throws Exception {
        write(censusOut, siteList, out);
    }

    static String report(Object censusOut, Object siteList) throws Exception { return generate(censusOut, siteList); }
    static String report(Path a, Path b) throws Exception { return generate(a, b); }
    static String report(File a, File b) throws Exception { return generate(a, b); }
    static String report(String a, String b) throws Exception { return generate(a, b); }

    static void write(Object censusOut, Object siteList, Object out) throws Exception {
        String md = generate(censusOut, siteList);
        Files.writeString(asPath(out), md, StandardCharsets.UTF_8);
    }
    static void write(Path a, Path b, Path c) throws Exception {
        String md = generate(a, b);
        Files.writeString(c, md, StandardCharsets.UTF_8);
    }
    static void write(File a, File b, File c) throws Exception {
        String md = generate(a, b);
        Files.writeString(c.toPath(), md, StandardCharsets.UTF_8);
    }
    static void write(String a, String b, String c) throws Exception {
        String md = generate(a, b);
        Files.writeString(Path.of(c), md, StandardCharsets.UTF_8);
    }

    static List<String> lines(Object censusOut, Object siteList) throws Exception {
        return List.of(generate(censusOut, siteList).split("\\n"));
    }

    static String build(Object a, Object b) throws Exception { return generate(a, b); }
    static String render(Object a, Object b) throws Exception { return generate(a, b); }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: CensusReport <census.out> <site list> <out.md>");
            System.exit(2);
            return;
        }
        write(args[0], args[1], args[2]);
    }
}
