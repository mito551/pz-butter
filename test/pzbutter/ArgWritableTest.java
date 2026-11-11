package pzbutter;

import java.nio.file.*;
import java.util.regex.*;

/** ZombieBuddy refuses to transform an advice that assigns to an @Patch.Argument not declared readOnly = false. */
final class ArgWritableTest {
    private static final Pattern ARG = Pattern.compile("@(?:me\\.zed_0xff\\.zombie_buddy\\.)?Patch\\.Argument\\(([^)]*)\\)\\s*(?:final\\s+)?[\\w.<>\\[\\], ?]+?\\s+(\\w+)\\s*[,)]");

    static void run() throws Exception {
        StringBuilder bad = new StringBuilder();
        try (var files = Files.list(Path.of("src/pzbutter"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ").replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
                Matcher m = ARG.matcher(src);
                while (m.find()) {
                    if (m.group(1).replace(" ", "").contains("readOnly=false")) continue;
                    String name = m.group(2);
                    int open = src.indexOf('{', m.end()), depth = 0, i = open;
                    do { char c = src.charAt(i); if (c == '{') depth++; else if (c == '}') depth--; i++; } while (depth > 0);
                    String body = src.substring(open, i);
                    if (Pattern.compile("(?<![\\w.])" + name + "\\s*(?:[-+*/%&|^]|<<|>>>?)?=(?!=)|(?<![\\w.])" + name + "\\s*(?:\\+\\+|--)|(?:\\+\\+|--)" + name + "\\b").matcher(body).find())
                        bad.append(f.getFileName()).append(": ").append(name).append(" (").append(m.group(1).trim()).append(")\n");
                }
            }
        }
        assert bad.length() == 0 : "advice assigns to a read-only @Patch.Argument:\n" + bad;
    }
}
