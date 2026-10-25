package pzbutter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parsed `census-<rev>.txt`: revision, classes and call-site offsets. */
final class CensusSites {
    record Site(int id, String family, String owner, String method, int offset, String target) {}

    private final String revision;
    private final List<Site> sites;
    private final Set<String> classes; // internal names
    private final int textHash;

    private CensusSites(String revision, List<Site> sites, Set<String> classes, int textHash) {
        this.revision = revision;
        this.sites = List.copyOf(sites);
        this.classes = Set.copyOf(classes);
        this.textHash = textHash;
    }

    String revision() { return revision; }
    int size() { return sites.size(); }
    int classCount() { return classes.size(); }
    boolean listed(String internalName) { return classes.contains(internalName); }

    List<Site> sitesOf(String owner) { return sites.stream().filter(s -> s.owner().equals(owner)).toList(); }
    Site site(int id) { return sites.get(id); }
    int textHash() { return textHash; }

    static CensusSites load(String revision) {
        try (InputStream in = CensusSites.class.getResourceAsStream("/pzbutter/census-" + revision + ".txt")) {
            return in == null ? null : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static CensusSites parse(String text) {
        String rev = null;
        List<Site> sites = new ArrayList<>();
        Set<String> classes = new HashSet<>();
        Set<Integer> seenIds = new HashSet<>();
        int no = 0;
        for (String raw : text.lines().toList()) {
            no++;
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            if (p.length == 2 && p[0].equals("revision") && rev == null) {
                rev = p[1];
            } else if (p.length == 2 && p[0].equals("class")) {
                classes.add(p[1].replace('.', '/'));
            } else if (p.length == 7 && p[0].equals("site") && isInt(p[1]) && isInt(p[5])) {
                int id = Integer.parseInt(p[1]);
                if (id != sites.size()) throw new IllegalArgumentException("line " + no + ": " + raw);
                if (!seenIds.add(id)) throw new IllegalArgumentException("line " + no + ": " + raw);
                sites.add(new Site(id, p[2], p[3], p[4], Integer.parseInt(p[5]), p[6]));
            } else throw new IllegalArgumentException("line " + no + ": " + raw);
        }
        if (rev == null) throw new IllegalArgumentException("no revision line");
        return new CensusSites(rev, sites, classes, text.hashCode());
    }

    private static boolean isInt(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }
}