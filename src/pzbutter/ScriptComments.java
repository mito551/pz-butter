package pzbutter;

import java.util.ArrayDeque;

/**
 * Plan 11b Task 3 (scriptParserFast): {@code ScriptParser.stripComments} without its quadratic {@code StringBuilder.replace}.
 *
 * Stock walks the buffer from its last {@code *}{@code /} leftwards and deletes each comment span with {@code replace}, which
 * copies the whole tail every time. Every deletion lies right of where the next search starts, so the text left of the
 * current boundary {@code p} is always the original string. The searches reach at most two characters past {@code p}, into
 * the kept suffix. So the same control flow runs on a virtual buffer: the original prefix {@code [0, p)} plus the kept suffix
 * as ranges of the original, joined once at the end. Same result, O(n) copying.
 */
public final class ScriptComments {
    private ScriptComments() {}

    public static final Guard guard = new Guard("scriptParserFast");

    static void init() {
        guard.on = Settings.flag("scriptParserFast");
    }

    static int large;
    static long largeNs;
    private static volatile boolean printed;

    static synchronized void count(long ns) { large++; largeNs += ns; }

    /** GameLoadingState exit: how many large inputs took this path so far (the boot's tileGeometry.txt is advised only if it ran after Main). */
    public static void printSummaryOnce() {
        if (!guard.on || printed) return;
        printed = true;
        System.out.println("[PZButter] scriptParserFast: " + large + " large inputs, " + (largeNs / 1_000_000) + " ms");
    }

    static void resetForTest() {
        guard.on = false;
        large = 0;
        largeNs = 0;
        printed = false;
    }

    /** Exactly what stock {@code stripComments(s)} returns; the control flow mirrors its bytecode (test ScriptCommentsRef). */
    public static String strip(String s) {
        return new Buf(s).run();
    }

    /** The stock buffer after some deletions: a[0, p) followed by the kept suffix (ranges of a, in order). */
    private static final class Buf {
        final char[] a;
        int p;
        final ArrayDeque<int[]> suffix = new ArrayDeque<>();
        int sufLen;

        Buf(String s) { a = s.toCharArray(); p = a.length; }

        int len() { return p + sufLen; }

        char ch(int i) {
            if (i < p) return a[i];
            int k = i - p;
            for (int[] r : suffix) {   // only k = 0 or 1 is ever asked for: at most two chunks are walked
                int n = r[1] - r[0];
                if (k < n) return a[r[0] + k];
                k -= n;
            }
            throw new IndexOutOfBoundsException(String.valueOf(i));
        }

        /** {@code StringBuilder.lastIndexOf(c0c1, from)} on this buffer. */
        int last(char c0, char c1, int from) {
            int right = len() - 2;
            if (from > right) from = right;
            for (int j = from; j >= 0; j--) if (ch(j) == c0 && ch(j + 1) == c1) return j;
            return -1;
        }

        /** {@code replace(start, end, "")}: start < p always (it is a match left of the boundary); end may reach into the suffix. */
        void delete(int start, int end) {
            int drop = end - p;   // suffix chars covered by the deletion
            while (drop > 0) {
                int[] r = suffix.peekFirst();
                int n = r[1] - r[0];
                if (drop >= n) { suffix.pollFirst(); sufLen -= n; drop -= n; }
                else { r[0] += drop; sufLen -= drop; drop = 0; }
            }
            if (end < p) { suffix.addFirst(new int[] {end, p}); sufLen += p - end; }
            p = start;
        }

        String run() {
            int i1 = last('*', '/', len());
            while (i1 != -1) {
                int i2 = last('/', '*', i1 - 1);
                if (i2 == -1) break;
                int i3 = last('*', '/', i1 - 1);
                if (i3 > i2) {
                    int i4 = i2;
                    i2 = last('/', '*', i2 - 2);
                    if (i2 == -1) break;
                    i3 = last('*', '/', i4 - 2);
                    while (i3 > i2 && i2 != -1) {
                        i4 = i2;
                        i2 = last('/', '*', i2 - 2);
                        if (i2 == -1) break;
                        i3 = last('*', '/', i4 - 2);
                    }
                    if (i2 == -1) break;
                }
                if (i2 == -1) break;
                delete(i2, i1 + 2);
                i1 = last('*', '/', i2);
            }
            StringBuilder out = new StringBuilder(len());
            out.append(a, 0, p);
            for (int[] r : suffix) out.append(a, r[0], r[1] - r[0]);
            return out.toString();
        }
    }
}
