package pzbutter;

/**
 * textWidthCache (Undying Optimizer 3.1-3.3): TextManager.MeasureStringX answers for (font object, text) are kept.
 * Context menus re-measure every option on every opening (and each addOption re-measures the whole menu). Keyed by
 * the font object (identity), so reloaded fonts (new objects) never reuse old widths; cleared on TextManager.Init.
 * An open-addressing table (linear probing, at most half full); when full, one random entry is dropped.
 * It only ever stores the game's own answers. Main thread only. (Low-probability variant b of 2026-10-21.)
 */
final class TextWidths {
    static final int MAX = 8192;
    static volatile boolean on;

    private TextWidths() {}

    static void init() { on = Settings.flag("textWidthCache"); UiPatches.widthGuard.on = on; }

    private static final int CAP = MAX * 2, MASK = CAP - 1;
    private static final Object[] fonts = new Object[CAP];
    private static final String[] texts = new String[CAP];
    private static final Integer[] widths = new Integer[CAP];
    private static int size, rng = 0x9E3779B9;

    private static int slot(Object font, String text) {
        return ((System.identityHashCode(font) * 31 + text.hashCode()) * 0x9E3779B1) >>> 18;   // 14 bits = CAP
    }

    static Integer get(Object font, String text) {
        for (int i = slot(font, text); ; i = (i + 1) & MASK) {
            String t = texts[i];
            if (t == null) return null;
            if (fonts[i] == font && t.equals(text)) return widths[i];
        }
    }

    static void put(Object font, String text, int width) {
        int i = slot(font, text);
        for (; texts[i] != null; i = (i + 1) & MASK)
            if (fonts[i] == font && texts[i].equals(text)) { widths[i] = width; return; }
        if (size >= MAX) {   // full: drop one random entry (backward-shift delete), then re-find the free slot
            rng ^= rng << 13; rng ^= rng >>> 17; rng ^= rng << 5;
            int d = rng & MASK;
            while (texts[d] == null) d = (d + 1) & MASK;
            remove(d);
            for (i = slot(font, text); texts[i] != null; i = (i + 1) & MASK) { }
        }
        fonts[i] = font; texts[i] = text; widths[i] = width; size++;
    }

    private static void remove(int d) {
        for (int j = d; ; ) {
            j = (j + 1) & MASK;
            if (texts[j] == null) break;
            int h = slot(fonts[j], texts[j]);
            if (((j - h) & MASK) >= ((j - d) & MASK)) { fonts[d] = fonts[j]; texts[d] = texts[j]; widths[d] = widths[j]; d = j; }
        }
        fonts[d] = null; texts[d] = null; widths[d] = null; size--;
    }

    static int size() { return size; }

    static void clear() {
        java.util.Arrays.fill(fonts, null); java.util.Arrays.fill(texts, null); java.util.Arrays.fill(widths, null);
        size = 0;
    }
}
