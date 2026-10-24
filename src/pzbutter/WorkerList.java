package pzbutter;

import java.util.ArrayList;
import java.util.List;

/** The frame-worker class list for one game revision: a `revision <r>` line and `class <binary name>` lines. */
final class WorkerList {
    private final String revision;
    private final List<String> classes;

    private WorkerList(String revision, List<String> classes) {
        this.revision = revision;
        this.classes = List.copyOf(classes);
    }

    String revision() { return revision; }

    List<String> classes() { return classes; }

    /** Blank lines and `#` comments are ignored. Throws IllegalArgumentException, naming the line, on anything else. */
    static WorkerList parse(String text) {
        String rev = null;
        List<String> classes = new ArrayList<>();
        int no = 0;
        for (String raw : text.lines().toList()) {
            no++;
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length == 2 && parts[0].equals("revision") && rev == null) rev = parts[1];
            else if (parts.length == 2 && parts[0].equals("class")) classes.add(parts[1]);
            else throw new IllegalArgumentException("line " + no + ": " + raw);
        }
        if (rev == null) throw new IllegalArgumentException("no revision line");
        return new WorkerList(rev, classes);
    }
}
