package pzbutter;

import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * textWidthCache (Undying Optimizer 3.1-3.3): TextManager.MeasureStringX answers for (font object, text) are kept.
 * Context menus re-measure every option on every opening (and each addOption re-measures the whole menu). Keyed by
 * the font object, so reloaded fonts (new objects) never reuse old widths; cleared on TextManager.Init and when
 * full. It only ever stores the game's own answers. Main thread only.
 */
final class TextWidths {
    static final int MAX = 8192;
    static volatile boolean on;

    private static final IdentityHashMap<Object, HashMap<String, Integer>> byFont = new IdentityHashMap<>();
    private static int size;

    private TextWidths() {}

    static void init() { on = Settings.flag("textWidthCache"); }

    static Integer get(Object font, String text) {
        HashMap<String, Integer> m = byFont.get(font);
        return m == null ? null : m.get(text);
    }

    static void put(Object font, String text, int width) {
        if (size >= MAX) clear();
        HashMap<String, Integer> m = byFont.get(font);
        if (m == null) byFont.put(font, m = new HashMap<>());
        if (m.put(text, width) == null) size++;
    }

    static int size() { return size; }

    static void clear() {
        byFont.clear();
        size = 0;
    }
}
