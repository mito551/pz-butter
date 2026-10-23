package pzbutter;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import se.krka.kahlua.integration.expose.caller.MethodCaller;
import zombie.ui.UIElement;
import zombie.ui.UIManager;

/**
 * uiCensus (diagnostic, Plan 3c): for each top-level UI window, what its render reads and calls, and how often a render
 * read the same values, with the same geometry, as the render before it on the same element ("stable"). A render that
 * read the clock, random numbers, a table iteration or a mouse answer inside the element is volatile and never stable.
 * Needs uiTiming; the census lines follow the uiTiming report each minute.
 */
final class UiCensus {
    static final Guard guard = new Guard("uiCensus");
    static final int OTHER = 0, TIME = 1, MOUSE = 2, RANDOM = 3;
    private static final int KINDS = 4;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_LINES = 15;
    private static final long SEED = 1;
    private static final String[] TIME_NAMES = {"Timestamp", "TimeInMillis", "MillisSince", "SecondsSince", "getTimeOfDay",
        "BlinkAlpha", "SyncedIconIndex"};
    private static final Set<String> MOUSE_NAMES = Set.of("getMouseX", "getMouseY", "getXA", "getYA");
    private static final String[] TARGETS = {"se.krka.kahlua.integration.expose.caller.MethodCaller",
        "se.krka.kahlua.integration.expose.ReturnValues", "se.krka.kahlua.stdlib.OsLib", "se.krka.kahlua.stdlib.RandomLib"};

    static final class Stat {
        final String name;
        long renders, stable, volatileRenders, reads, calls, iterations, itemIcons, entries, reusableNs;
        final long[] byKind = new long[KINDS];

        Stat(String name) { this.name = name; }
    }

    /** A panel inside a window (depth 2 and deeper), keyed "window > panel". */
    static final class ChildStat {
        final String key;
        long renders, stable, inclNs;

        ChildStat(String key) { this.key = key; }
    }

    /** The full child key, cached per element while its window and name stay the same. */
    private static final class ChildKey {
        final String window, name, key;

        ChildKey(String window, String name) {
            this.window = window;
            this.name = name;
            this.key = window + " > " + name;
        }
    }

    /** One open element. A child's frame is folded into its parent's when the child ends. */
    private static final class Frame {
        Object element;
        String name;
        long hash, startNs, inclNs, reusableNs, childReusable;
        boolean volatileSeen, stable;
        int reads, calls, iterations, itemIcons, entries;
        final int[] byKind = new int[KINDS];

        void open(Object element, String name, long startNs) {
            this.element = element;
            this.name = name;
            this.startNs = startNs;
            hash = SEED;
            volatileSeen = stable = false;
            childReusable = 0;
            reads = calls = iterations = itemIcons = entries = 0;
            Arrays.fill(byKind, 0);
        }
    }

    private static final Frame[] frames = new Frame[MAX_DEPTH];
    private static final String[] callNames = new String[MAX_DEPTH];
    private static final Map<String, Stat> stats = new HashMap<>();
    private static final Map<String, ChildStat> childStats = new HashMap<>();
    private static final Map<Object, ChildKey> childKeys = new WeakHashMap<>();
    private static final Map<Object, Long> lastHash = new WeakHashMap<>();
    private static int depth, callDepth;

    static {
        for (int i = 0; i < MAX_DEPTH; i++) frames[i] = new Frame();
    }

    private UiCensus() {}

    static void init() {
        boolean on = Settings.flag("uiCensus");
        if (on && !UiTiming.guard.on) {
            System.out.println("[PZButter] uiCensus needs uiTiming=true; uiCensus off");
            on = false;
        }
        guard.on = on;
        if (on) for (String c : TARGETS) Main.classPresent(c);
    }

    private static boolean mainThread() { return Thread.currentThread() == FrameClock.mainThread; }

    /** The hooks' first check: the feature is on and this is the main thread. */
    static boolean active() { return guard.on && mainThread(); }

    // ---- events (main thread) ----

    /** An element's render starts. It is named as uiTiming names it (a window, or a panel inside one). */
    static void elementEnter(Object element) {
        if (!mainThread()) return;
        elementEnterNamed(element, UiTiming.nameOf(element));
    }

    /** The same with the name given by the caller (tests). */
    static void elementEnterNamed(Object element, String name) {
        if (!mainThread()) return;
        depth++;
        if (depth <= MAX_DEPTH) frames[depth - 1].open(element, name, UiTiming.clock.getAsLong());
        frames[0].entries++;
    }

    /**
     * An element's render ends: its verdict (stable against its own previous render), its inclusive time, and its
     * reusable time (its whole time when stable, else what its panels could reuse). Then it goes to its window or parent.
     */
    static void elementExit(Object element) {
        if (!mainThread() || depth == 0) return;
        int d = depth--;
        if (d > MAX_DEPTH) return;
        Frame f = frames[d - 1];
        f.hash = geometry(f.hash, element);
        f.inclNs = UiTiming.clock.getAsLong() - f.startNs;
        f.stable = verdict(element, f.hash, f.volatileSeen);
        f.reusableNs = f.stable ? f.inclNs : f.childReusable;
        if (d == 1) finish(f);
        else {
            childDone(element, f);
            fold(frames[d - 2], f);
        }
    }

    /** A Java method called from Lua starts. */
    static void noteCall(String methodName) {
        if (!mainThread()) return;
        if (callDepth < MAX_DEPTH) callNames[callDepth] = methodName;
        callDepth++;
        if (depth == 0) return;
        Frame f = top();
        int k = kind(methodName);
        f.calls++;
        f.byKind[k]++;
        if (k == TIME || k == RANDOM) f.volatileSeen = true;
    }

    static void callExit() {
        if (mainThread() && callDepth > 0) callDepth--;
    }

    /**
     * A value read by the open element: a table read (key is the Lua key), or the answer of the innermost open Java
     * call (key is that call's name, identical to currentCall()). A mouse answer counts only inside the element.
     */
    static void noteRead(Object key, Object value) {
        if (!mainThread() || key == null || depth == 0) return;
        Frame f = top();
        if (key == currentCall() && kind((String) key) == MOUSE && !mouseInside(f.element)) return;
        f.hash = mix(f.hash, key, value);
        f.reads++;
    }

    /** A table iteration: the render is live. */
    static void noteIterate() {
        if (!mainThread() || depth == 0) return;
        Frame f = top();
        f.iterations++;
        f.volatileSeen = true;
    }

    /** A call that reads the clock or random numbers (os.*, math.random): the render is live. */
    static void noteVolatile() {
        if (!mainThread() || depth == 0) return;
        top().volatileSeen = true;
    }

    static void noteItemIcon() {
        if (!mainThread() || depth == 0) return;
        top().itemIcons++;
    }

    static String currentCall() {
        return callDepth == 0 ? null : callNames[Math.min(callDepth, MAX_DEPTH) - 1];
    }

    private static Frame top() { return frames[Math.min(depth, MAX_DEPTH) - 1]; }

    private static void fold(Frame p, Frame c) {
        p.hash = mixLong(p.hash, c.hash);
        p.volatileSeen |= c.volatileSeen;
        p.reads += c.reads;
        p.calls += c.calls;
        p.iterations += c.iterations;
        p.itemIcons += c.itemIcons;
        p.childReusable += c.reusableNs;
        for (int k = 0; k < KINDS; k++) p.byKind[k] += c.byKind[k];
    }

    /** A panel inside a window: its own renders and inclusive time, under "window > panel". */
    private static void childDone(Object element, Frame f) {
        String window = frames[0].name;
        ChildKey k = childKeys.get(element);
        if (k == null || k.window != window || k.name != f.name) childKeys.put(element, k = new ChildKey(window, f.name));
        ChildStat c = childStats.computeIfAbsent(k.key, ChildStat::new);
        c.renders++;
        if (f.stable) c.stable++;
        c.inclNs += f.inclNs;
    }

    private static void finish(Frame f) {
        Stat s = stats.computeIfAbsent(f.name, Stat::new);
        s.iterations += f.iterations;
        s.itemIcons += f.itemIcons;
        s.entries += f.entries;
        for (int k = 0; k < KINDS; k++) s.byKind[k] += f.byKind[k];
        countRender(s, f.stable, f.volatileSeen, f.reads, f.calls, f.reusableNs);
    }

    /** A top-level render with only its own reads (tests): stable when it equals the element's previous render. */
    static void renderDone(String window, long readHash, boolean volatileSeen, int reads, int calls) {
        countRender(stats.computeIfAbsent(window, Stat::new), verdict(window, readHash, volatileSeen), volatileSeen,
            reads, calls, 0);
    }

    /** The element's hash is kept for its next render; stable when it equals the previous one and nothing was volatile. */
    private static boolean verdict(Object identity, long readHash, boolean volatileSeen) {
        Long prev = lastHash.put(identity, readHash);
        return prev != null && prev == readHash && !volatileSeen;
    }

    private static void countRender(Stat s, boolean stable, boolean volatileSeen, int reads, int calls, long reusableNs) {
        s.renders++;
        if (volatileSeen) s.volatileRenders++;
        if (stable) s.stable++;
        s.reads += reads;
        s.calls += calls;
        s.reusableNs += reusableNs;
    }

    // ---- pure cores ----

    /** TIME, MOUSE, RANDOM or OTHER, by the Lua-visible method name. */
    static int kind(String name) {
        for (String t : TIME_NAMES) if (name.contains(t)) return TIME;
        if (MOUSE_NAMES.contains(name)) return MOUSE;
        if (name.startsWith("ZombRand") || name.startsWith("getRandom")) return RANDOM;
        return OTHER;
    }

    /** Order-sensitive hash of one (key, value) read. Strings and boxed numbers by value, anything else by identity. */
    static long mix(long h, Object key, Object value) {
        return mixLong(mixLong(h, hashOf(key)), hashOf(value));
    }

    private static long hashOf(Object o) {
        if (o == null) return 0;
        if (o instanceof Double || o instanceof Boolean || o instanceof String || o instanceof Integer
            || o instanceof Float || o instanceof Long) return o.hashCode();
        return System.identityHashCode(o);
    }

    private static long mixLong(long h, long v) {
        long x = (h ^ v) * 0x9E3779B97F4A7C15L;
        return x ^ (x >>> 32);
    }

    /** The element's position and size: a render at another place or size is not the same render. */
    private static long geometry(long h, Object element) {
        if (!(element instanceof UIElement e)) return h;
        h = mixLong(h, Double.doubleToLongBits(e.x));
        h = mixLong(h, Double.doubleToLongBits(e.y));
        h = mixLong(h, Float.floatToIntBits(e.width));
        return mixLong(h, Float.floatToIntBits(e.height));
    }

    /** The mouse answer counts only while the mouse is in the element's plain absolute rectangle. */
    private static boolean mouseInside(Object element) {
        if (!(element instanceof UIElement e)) return false;
        Double ax = e.getAbsoluteX(), ay = e.getAbsoluteY();
        if (ax == null || ay == null) return false;
        int mx = UIManager.lastMouseX, my = UIManager.lastMouseY;
        return mx >= ax && mx < ax + e.width && my >= ay && my < ay + e.height;
    }

    /** The Java method's name for a MethodCaller. */
    static String methodName(Object caller) {
        return ((Method) Caller.METHOD.get((MethodCaller) caller)).getName();
    }

    /** Bound once by class initialisation, so the census costs nothing until a Java call is seen. */
    private static final class Caller {
        static final VarHandle METHOD = Game.field(MethodCaller.class, "method", Method.class);
    }

    // ---- report ----

    /** The census lines for the minute, then the census starts over. */
    static List<String> report(Map<String, UiTiming.Stat> timing, long elapsedNs) {
        List<String> lines = reportLines(stats, childStats, timing, elapsedNs);
        stats.clear();
        childStats.clear();
        return lines;
    }

    /**
     * The windows with the most inclusive time (from uiTiming), at most 15: stable ms/s = inclusive ms/s x stable
     * fraction, reusable ms/s = the time the window could reuse. Then the panels with the most inclusive time, at most 15.
     */
    static List<String> reportLines(Map<String, Stat> census, Map<String, ChildStat> children,
                                    Map<String, UiTiming.Stat> timing, long elapsedNs) {
        double seconds = Math.max(1e-9, elapsedNs / 1e9);
        List<Stat> sorted = new ArrayList<>(census.values());
        sorted.sort((a, b) -> Long.compare(inclNs(timing, b), inclNs(timing, a)));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_LINES, sorted.size()); i++) {
            Stat s = sorted.get(i);
            double renders = Math.max(1, s.renders);
            double incl = inclNs(timing, s) / 1e6 / seconds;
            double stable = s.stable / renders;
            out.add(String.format(Locale.ROOT,
                "[PZButter] census: %7.3f ms/s  stable %5.1f%% = %7.3f ms/s  reusable %7.3f ms/s  volatile %5.1f%%  "
                    + "%5d renders  reads %6.1f  calls %5.1f (time %.1f random %.1f mouse %.1f other %.1f)  "
                    + "iterations %.1f  icons %.1f  entries %.1f  %s",
                incl, 100 * stable, incl * stable, s.reusableNs / 1e6 / seconds, 100.0 * s.volatileRenders / renders,
                s.renders, s.reads / renders, s.calls / renders, s.byKind[TIME] / renders, s.byKind[RANDOM] / renders,
                s.byKind[MOUSE] / renders, s.byKind[OTHER] / renders, s.iterations / renders, s.itemIcons / renders,
                s.entries / renders, s.name));
        }
        List<ChildStat> kids = new ArrayList<>(children.values());
        kids.sort((a, b) -> Long.compare(b.inclNs, a.inclNs));
        for (int i = 0; i < Math.min(MAX_LINES, kids.size()); i++) {
            ChildStat c = kids.get(i);
            out.add(String.format(Locale.ROOT, "[PZButter] census child: %8.3f ms/s  stable %5.1f%%  %s",
                c.inclNs / 1e6 / seconds, 100.0 * c.stable / Math.max(1, c.renders), c.key));
        }
        return out;
    }

    private static long inclNs(Map<String, UiTiming.Stat> timing, Stat s) {
        UiTiming.Stat t = timing.get(s.name);
        return t == null ? 0 : t.inclNs;
    }

    // ---- test entry points (package-private) ----

    /** Tests run on the test thread: it becomes the main thread for the events. */
    static void resetForTest() {
        FrameClock.mainThread = Thread.currentThread();
        stats.clear();
        childStats.clear();
        childKeys.clear();
        lastHash.clear();
        depth = callDepth = 0;
    }

    static Stat statForTest(String name) { return stats.get(name); }

    static ChildStat childStatForTest(String key) { return childStats.get(key); }
}
