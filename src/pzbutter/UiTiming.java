package pzbutter;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import zombie.ui.UIElement;
import zombie.ui.UIManager;

/**
 * uiTiming: time spent in UIManager.render per frame, and in each top-level UI window (a UIElement rendered outside
 * another element), inclusive and self, with the sprites queued while it drew. Once a minute the total, the rest and
 * the 15 slowest windows go to the console and, with frameLog, to Zomboid/pzbutter/ui.out. The rest is the total
 * minus every window: OnPreUIDraw / OnPostUIDraw, fades, tooltips and modals, and Java windows whose render() never
 * calls super.render(). With uiCensus, the census lines follow the window lines.
 */
final class UiTiming {
    static final Guard guard = new Guard("uiTiming");
    static java.util.function.LongSupplier clock = System::nanoTime;
    private static final int MAX_DEPTH = 64;
    private static final long WINDOW_NS = 60_000_000_000L;

    static final class Stat {
        final String name;
        long inclNs, selfNs, renders, draws;

        Stat(String name) { this.name = name; }
    }

    private static final Map<String, Stat> stats = new HashMap<>();
    private static final Map<Object, String> names = new WeakHashMap<>();
    private static final long[] starts = new long[MAX_DEPTH];
    private static final long[] childNs = new long[MAX_DEPTH];
    private static final int[] drawsAt = new int[MAX_DEPTH];
    private static final Stat[] wins = new Stat[MAX_DEPTH];
    private static int depth;
    private static long uiStartNs, uiNs, frames, windowStartNs;
    private static boolean uiOpen, windowSet;

    private UiTiming() {}

    static void init() { guard.on = Settings.flag("uiTiming"); }

    private static boolean mainThread() { return Thread.currentThread() == FrameClock.mainThread; }

    static void uiEnter() {
        if (!mainThread()) return;
        uiStartNs = clock.getAsLong();
        uiOpen = true;
    }

    static void uiExit() {
        if (!mainThread() || !uiOpen) return;
        uiOpen = false;
        uiNs += clock.getAsLong() - uiStartNs;
        frames++;
    }

    static void elementEnter(Object element, int draws) {
        if (!mainThread()) return;
        push(depth == 0 ? nameOf(element) : null, draws);
    }

    /** The name given by the caller (tests); a child's name is ignored, it counts only inside its window. */
    static void elementEnterNamed(Object element, String name, int draws) {
        if (!mainThread()) return;
        if (depth == 0) names.put(element, name);
        push(depth == 0 ? name : null, draws);
    }

    static void elementExit(int draws) {
        if (!mainThread() || depth == 0) return;
        depth--;
        if (depth >= MAX_DEPTH) return;
        long now = clock.getAsLong();
        long incl = now - starts[depth];
        if (depth > 0) childNs[depth - 1] += incl;
        Stat s = wins[depth];
        wins[depth] = null;
        if (s != null) {
            s.inclNs += incl;
            s.selfNs += incl - childNs[depth];
            s.renders++;
            if (drawsAt[depth] >= 0 && draws >= 0) s.draws += Math.max(0, draws - drawsAt[depth]);
        }
        if (depth == 0) windowDone(now);
    }

    /** A render starts; a deeper one is pushed too (without timing), so every exit pops one. */
    private static void push(String window, int draws) {
        if (depth < MAX_DEPTH) {
            starts[depth] = clock.getAsLong();
            childNs[depth] = 0;
            drawsAt[depth] = draws;
            wins[depth] = window == null ? null : stats.computeIfAbsent(window, Stat::new);
        }
        depth++;
    }

    /** The window name for a top-level element, cached per element (uiCensus names windows the same way). */
    static String nameOf(Object element) {
        String name = names.get(element);
        if (name == null) {
            UIElement e = (UIElement) element;
            String type = null, file = null;
            KahluaTable t = e.getTable();
            if (t != null) {
                if (UIManager.tableget(t, "Type") instanceof String s) type = s;
                file = pickFile(fileOf(UIManager.tableget(t, "render")), fileOf(UIManager.tableget(t, "prerender")),
                    fileOf(UIManager.tableget(t, "createChildren")), fileOf(UIManager.tableget(t, "new")));
            }
            names.put(element, name = windowName(type, e.getClass().getSimpleName(), file));
        }
        return name;
    }

    private static String fileOf(Object fn) {
        if (!(fn instanceof LuaClosure c) || c.prototype == null) return null;
        Prototype p = c.prototype;
        return p.filename != null ? p.filename : p.file;
    }

    /** "Type (file)": the Lua Type or the Java class, and the source file of the window's Lua render. */
    static String windowName(String type, String javaClass, String file) {
        return (type != null ? type : javaClass) + " (" + (file != null ? file : "java") + ")";
    }

    private static void windowDone(long now) {
        if (!windowSet) { windowStartNs = now; windowSet = true; uiNs = 0; frames = 0; }
        else if (now - windowStartNs >= WINDOW_NS) report(now);
    }

    private static void report(long now) {
        List<String> lines = reportLines(stats.values(), now - windowStartNs, uiNs, frames);
        if (UiCensus.guard.on) lines.addAll(UiCensus.report(stats, now - windowStartNs));
        for (String l : lines) System.out.println(l);
        if (Settings.frameLog) {
            try (FileWriter w = new FileWriter(new File(System.getProperty("user.home"), "Zomboid/pzbutter/ui.out"), true)) {
                w.write("# " + System.currentTimeMillis() + "\n");
                for (String l : lines) w.write(l + "\n");
            } catch (Throwable t) { guard.fail(t); }
        }
        stats.clear();
        uiNs = 0;
        frames = 0;
        windowStartNs = now;
    }

    /** The UI total, then the 15 windows with the most inclusive time, slowest first. */
    static List<String> reportLines(Collection<Stat> all, long elapsedNs, long uiNs, long frames) {
        List<Stat> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Long.compare(b.inclNs, a.inclNs));
        List<String> out = new ArrayList<>();
        double seconds = Math.max(1e-9, elapsedNs / 1e9);
        out.add(String.format(Locale.ROOT, "[PZButter] ui: total %7.3f ms/s  %6.3f ms/frame  %d frames",
            uiNs / 1e6 / seconds, uiNs / 1e6 / Math.max(1, frames), frames));
        long windowNs = 0;
        for (Stat s : all) windowNs += s.inclNs;
        out.add(String.format(Locale.ROOT, "[PZButter] ui: rest  %7.3f ms/s  (total minus every window, incl. Java windows that skip super.render)",
            Math.max(0, uiNs - windowNs) / 1e6 / seconds));
        for (int i = 0; i < Math.min(15, sorted.size()); i++) {
            Stat s = sorted.get(i);
            out.add(String.format(Locale.ROOT, "[PZButter] ui: %7.3f ms/s  self %7.3f  %5d renders  %4d draws/render  %s",
                s.inclNs / 1e6 / seconds, s.selfNs / 1e6 / seconds, s.renders, s.draws / Math.max(1, s.renders), s.name));
        }
        return out;
    }

    // ---- test entry points (package-private) ----

    /** Tests run on the test thread: it becomes the main thread for the timed calls. */
    static void resetForTest() {
        FrameClock.mainThread = Thread.currentThread();
        stats.clear();
        names.clear();
        depth = 0;
        uiNs = frames = 0;
        uiOpen = windowSet = false;
    }

    static Stat statForTest(String name) { return stats.get(name); }

    static int depthForTest() { return depth; }

    static long uiNsForTest() { return uiNs; }

    static long framesForTest() { return frames; }

    /**
     * The file that defines a window: the first non-null one that is not a base UI file it derives from (an empty
     * ISUIElement.render says nothing about the window); else the first non-null one; else null.
     */
    static String pickFile(String... files) {
        String first = null;
        for (String f : files) {
            if (f == null) continue;
            if (first == null) first = f;
            if (!BASE_FILES.contains(f.substring(f.lastIndexOf('/') + 1))) return f;
        }
        return first;
    }

    private static final java.util.Set<String> BASE_FILES = java.util.Set.of("ISUIElement.lua", "ISPanel.lua",
        "ISPanelJoypad.lua", "ISCollapsableWindow.lua", "ISCollapsableWindowJoypad.lua", "ISBaseObject.lua");
}
