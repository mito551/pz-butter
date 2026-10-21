package pzbutter;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.IntBinaryOperator;

/**
 * World map speed-ups (SPEC 3.9). Main thread only.
 * mapStreetMemo: a street's on-screen length is kept per projection (the linear part of world→UI: zoom, rotation,
 * isometric skew; translation doesn't change a length), its translated name per language, and the render sort's
 * indexOf (twice per comparison) becomes one street→index map per sort.
 * mapStreetCache: a StreetRenderData keeps its label layout while everything its init reads is unchanged.
 * mapVisitedFast: the explored-area texture's changed region is written a row at a time, same bytes.
 */
final class MapFast {
    static volatile boolean memo, cache, visited;

    private static final WeakHashMap<Object, double[]> lengths = new WeakHashMap<>();   // {a, b, c, d, length}
    private static final WeakHashMap<Object, Object[]> names = new WeakHashMap<>();     // {untranslated, language, text}
    private static final WeakHashMap<Object, ViewKey> layouts = new WeakHashMap<>();
    private static final IdentityHashMap<Object, Integer> index = new IdentityHashMap<>();
    private static List<?> indexed;
    private static byte[] row = new byte[0];

    private MapFast() {}

    static void init() {
        memo = Settings.flag("mapStreetMemo");
        cache = Settings.flag("mapStreetCache");
        visited = Settings.flag("mapVisitedFast");
        MapPatches.guard.on = memo || cache || visited;
    }

    static void clear() {
        lengths.clear(); names.clear(); layouts.clear(); index.clear(); indexed = null;
    }

    // ---- mapStreetMemo ----

    static Float length(Object street, double[] view) {
        double[] l = lengths.get(street);
        return l != null && l[0] == view[0] && l[1] == view[1] && l[2] == view[2] && l[3] == view[3] ? (float) l[4] : null;
    }

    static void putLength(Object street, double[] view, float len) {
        lengths.put(street, new double[] {view[0], view[1], view[2], view[3], len});
    }

    static String name(Object street, String untranslated, Object language) {
        Object[] n = names.get(street);
        return n != null && n[0] == untranslated && n[1] == language ? (String) n[2] : null;
    }

    static void putName(Object street, String untranslated, Object language, String text) {
        names.put(street, new Object[] {untranslated, language, text});
    }

    /** A new render sort starts: the index map is rebuilt on first use. */
    static void newSort() { indexed = null; }

    static int indexOf(List<?> list, Object o) {
        if (indexed != list) {
            index.clear();
            for (int i = list.size() - 1; i >= 0; i--) index.put(list.get(i), i);   // first occurrence wins, as indexOf
            indexed = list;
        }
        Integer i = index.get(o);
        return i == null ? -1 : i;
    }

    // ---- mapStreetCache ----

    /** Everything StreetRenderData.init reads that can change between frames. */
    static final class ViewKey {
        final double[] nums;
        final Object[] refs;   // compared by identity

        ViewKey(double[] nums, Object[] refs) { this.nums = nums; this.refs = refs; }

        boolean same(ViewKey o) {
            if (o == null || !Arrays.equals(nums, o.nums) || refs.length != o.refs.length) return false;
            for (int i = 0; i < refs.length; i++) if (refs[i] != o.refs[i]) return false;
            return true;
        }
    }

    /** Should this render-data object rebuild its label layout? Remembers the key when it does. */
    static boolean shouldInit(Object renderData, ViewKey key, boolean dirty) {
        if (!dirty && key.same(layouts.get(renderData))) return false;
        layouts.put(renderData, key);
        return true;
    }

    // ---- mapVisitedFast ----

    /** The changed region x1..x2, y1..y2 into buf (row stride width * 4, one-texel border), then position 0. */
    static void fillRows(ByteBuffer buf, int width, int x1, int x2, int y1, int y2, IntBinaryOperator flags) {
        int n = (x2 - x1 + 1) * 4;
        if (n <= 0) { buf.position(0); return; }
        if (row.length < n) row = new byte[n];
        for (int y = y1; y <= y2; y++) {
            for (int x = x1, i = 0; x <= x2; x++, i += 4) {
                int f = flags.applyAsInt(x, y);
                row[i] = (byte) ((f & 2) != 0 ? 0 : -1);
                row[i + 1] = (byte) ((f & 1) != 0 ? 0 : -1);
                row[i + 2] = -1;
                row[i + 3] = -1;
            }
            buf.position((1 + x1) * 4 + (1 + y) * width * 4);
            buf.put(row, 0, n);
        }
        buf.position(0);
    }
}
