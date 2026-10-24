package pzbutter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The frame-worker list for one game revision: `revision <r>`, `class <binary name>`,
 * `scratch <k> <owner internal name> <field> <descriptor>` and `field <owner binary name> <name> <descriptor>` lines.
 */
final class WorkerList {
    record Scratch(int k, String owner, String field, String desc) {}

    record Field(String owner, String name, String desc) {}

    private final String revision;
    private final List<String> classes;
    private final Set<String> internal = new HashSet<>();
    private final List<Scratch> scratch;
    private final Map<String, Integer> scratchIndex = new HashMap<>();
    private final List<Field> fields;
    private final int textHash;

    private WorkerList(String revision, List<String> classes, List<Scratch> scratch, List<Field> fields, int textHash) {
        this.revision = revision;
        this.classes = List.copyOf(classes);
        this.scratch = List.copyOf(scratch);
        this.fields = List.copyOf(fields);
        this.textHash = textHash;
        for (String c : classes) internal.add(c.replace('.', '/'));
        for (Scratch s : scratch) scratchIndex.put(s.owner() + '.' + s.field() + ':' + s.desc(), s.k());
    }

    String revision() { return revision; }

    List<String> classes() { return classes; }

    List<Scratch> scratch() { return scratch; }

    /** The added fields of one class (binary name), in list order. */
    List<Field> fieldsOf(String owner) { return fields.stream().filter(f -> f.owner().equals(owner)).toList(); }

    boolean listed(String internalName) { return internal.contains(internalName); }

    /** The k of a scratch field, or -1. */
    int scratchIndex(String owner, String field, String desc) {
        return scratchIndex.getOrDefault(owner + '.' + field + ':' + desc, -1);
    }

    /** String.hashCode() of the list's text. */
    int textHash() { return textHash; }

    /** The list resource for a game revision, or null when there is none. */
    static WorkerList load(String revision) {
        try (InputStream in = WorkerList.class.getResourceAsStream("/pzbutter/workers-" + revision + ".txt")) {
            return in == null ? null : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Blank lines and `#` comments are ignored. Throws IllegalArgumentException, naming the line, on anything else. */
    static WorkerList parse(String text) {
        String rev = null;
        List<String> classes = new ArrayList<>();
        List<Scratch> scratch = new ArrayList<>();
        List<Field> fields = new ArrayList<>();
        int no = 0;
        for (String raw : text.lines().toList()) {
            no++;
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            if (p.length == 2 && p[0].equals("revision") && rev == null) rev = p[1];
            else if (p.length == 2 && p[0].equals("class")) classes.add(p[1]);
            else if (p.length == 5 && p[0].equals("scratch") && isInt(p[1]) && scratchOk(scratch, Integer.parseInt(p[1]), p[3], p[4]))
                scratch.add(new Scratch(Integer.parseInt(p[1]), p[2], p[3], p[4]));
            else if (p.length == 4 && p[0].equals("field")) fields.add(new Field(p[1], p[2], p[3]));
            else throw new IllegalArgumentException("line " + no + ": " + raw);
        }
        if (rev == null) throw new IllegalArgumentException("no revision line");
        return new WorkerList(rev, classes, scratch, fields, text.hashCode());
    }

    /** k is the next new index, or a repeat of an earlier k for the same field name and descriptor (another owner name). */
    private static boolean scratchOk(List<Scratch> have, int k, String field, String desc) {
        int next = have.isEmpty() ? 0 : have.get(have.size() - 1).k() + 1;
        if (k == next) return true;
        return have.stream().anyMatch(s -> s.k() == k && s.field().equals(field) && s.desc().equals(desc));
    }

    private static boolean isInt(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }
}
