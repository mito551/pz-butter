package pzbutter;

/** Plan 11b Task 3: script comments stripped in one pass (scriptParserFast). */
public final class ScriptComments {
    private ScriptComments() {}

    public static final Guard guard = new Guard("scriptParserFast");

    /**
     * Returns exactly what stock {@code ScriptParser.stripComments} returns. Implemented as the same backward
     * lastIndexOf/replace sequence the game uses (its own StringBuilder), because the stock algorithm's rightmost
     * close traversal cannot be reproduced by a plain single forward pass over all inputs.
     */
    public static String strip(String s) {
        StringBuilder sb = new StringBuilder();
        sb.setLength(0);
        sb.append(s);
        int i1 = sb.lastIndexOf("*/");
        while (i1 != -1) {
            int i2 = sb.lastIndexOf("/*", i1 - 1);
            if (i2 == -1) break;
            int i3 = sb.lastIndexOf("*/", i1 - 1);
            if (i3 > i2) {
                int i4 = i2;
                i2 = sb.lastIndexOf("/*", i2 - 2);
                if (i2 == -1) break;
                i3 = sb.lastIndexOf("*/", i4 - 2);
                while (i3 > i2 && i2 != -1) {
                    i4 = i2;
                    i2 = sb.lastIndexOf("/*", i2 - 2);
                    if (i2 == -1) break;
                    i3 = sb.lastIndexOf("*/", i4 - 2);
                }
                if (i2 == -1) break;
            }
            if (i2 == -1) break;
            sb.replace(i2, i1 + 2, "");
            i1 = sb.lastIndexOf("*/", i2);
        }
        return sb.toString();
    }

    static void init() {
        guard.on = Settings.flag("scriptParserFast");
    }

    static void resetForTest() {
        guard.on = false;
    }
}