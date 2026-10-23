package pzbutter;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import se.krka.kahlua.integration.expose.caller.AbstractCaller;
import se.krka.kahlua.integration.expose.caller.MethodCaller;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLState;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;
import zombie.entity.components.fluids.FluidContainer;
import zombie.gameStates.IngameState;
import zombie.input.Mouse;
import zombie.GameWindow;
import zombie.inventory.InventoryItem;
import zombie.ui.UIElement;
import zombie.ui.UIManager;

/**
 * uiRetained (Plan 3c): a top-level UI window that read nothing new since its last render is drawn from the draw list
 * recorded then. While a top-level render is open, everything it reads (Lua table reads, Java getters, item icons)
 * goes into that window's record, children included; the next frame re-reads all of it, and replays only when every
 * value is the same. Only the main thread; a window nested in another is part of the outer record.
 */
final class UiRetain {
    static final Guard guard = new Guard("uiRetained");
    static final Guard check = new Guard("uiRetainCheck");
    private static final String[] TARGETS = {"zombie.ui.UIElement", "zombie.ui.UIManager", "zombie.core.Core",
        "zombie.core.opengl.GLState", "zombie.core.logger.ExceptionLogger", "zombie.core.textures.Texture",
        "zombie.inventory.InventoryItem", "se.krka.kahlua.integration.expose.caller.MethodCaller",
        "se.krka.kahlua.integration.expose.ReturnValues", "se.krka.kahlua.stdlib.OsLib", "se.krka.kahlua.stdlib.RandomLib"};
    private static final Object[] NO_ARGS = new Object[0];

    /** guard.on || check.on, kept in sync: the one check every hook path starts with. */
    static boolean any;
    /** True while a recorded top-level render is open: the note paths do work only then. Main thread writes it. */
    static boolean noting;
    /** True while verification re-reads: nothing is noted. */
    static boolean suspended;

    private static final AtomicLong EPOCH = new AtomicLong();
    private static Set<String> excluded = Set.of();
    private static boolean modsToo = true;

    // the open top-level render (main thread)
    private static Object root;
    private static Record rec;
    private static int depth;
    private static GenericSpriteRenderState openState;
    private static int openFrom;
    private static UiSlice checkSlice;
    private static final Call[] pending = new Call[64];
    private static int callDepth;

    private static final Map<Object, Record> records = new WeakHashMap<>();
    private static final Map<Object, CallInfo> infos = new IdentityHashMap<>();
    private static long seenEpoch;
    private static Object lastState;
    private static int lastPlayers = -1, lastW, lastH;

    private UiRetain() {}

    // ---- bound members ----

    private static final class F {
        static final VarHandle STENCIL = Game.staticField(UIElement.class, "stencilLevel", int.class);
        static final VarHandle CLIPPED = Game.field(UIElement.class, "renderClippedChildren", boolean.class);
        static final VarHandle METHOD = Game.field(MethodCaller.class, "method", Method.class);
        static final VarHandle OWNER = Game.field(MethodCaller.class, "owner", Object.class);
        static final VarHandle HAS_SELF = Game.field(MethodCaller.class, "hasSelf", boolean.class);
    }

    static void init() {
        boolean want = Settings.flag("uiRetained");
        if (want && !Settings.flag("uiVerify")) {
            System.out.println("[PZButter] uiRetained needs uiVerify=true; uiRetained off");
            want = false;
        }
        boolean diag = Settings.flag("uiRetainCheck");
        excluded = parseNames(Settings.text("uiRetainExclude", "ObjectTooltip,ISToolTip,ISToolTipInv"));
        modsToo = Settings.flag("uiRetainedMods");
        boolean ready = true;
        if (want || diag) ready = F.STENCIL != null && F.CLIPPED != null && F.METHOD != null && F.OWNER != null && F.HAS_SELF != null;
        if (!ready) System.out.println("[PZButter] uiRetained: game members missing; off");
        guard.on = want && ready;
        check.on = diag && ready;
        any = guard.on || check.on;
        if (guard.on || check.on) for (String c : TARGETS) Main.classPresent(c);
    }

    // ---- pure cores ----

    /** == ; else equals for Double, Float, Integer, Long, Boolean and String of the same class; else false. */
    static boolean same(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        Class<?> c = a.getClass();
        if (c != b.getClass()) return false;
        return (c == Double.class || c == String.class || c == Boolean.class || c == Integer.class || c == Float.class
            || c == Long.class) && a.equals(b);
    }

    /** Everything one window read from Lua tables: (table, key, value) in read order. */
    static final class ReadSet {
        private Object[] tables = new Object[16], keys = new Object[16], values = new Object[16];
        private int n;

        void add(Object table, Object key, Object value) {
            if (n == tables.length) {
                tables = Arrays.copyOf(tables, n * 2);
                keys = Arrays.copyOf(keys, n * 2);
                values = Arrays.copyOf(values, n * 2);
            }
            tables[n] = table;
            keys[n] = key;
            values[n++] = value;
        }

        /** True when every read gives the value it gave when recorded. */
        boolean stillSame(BiFunction<Object, Object, Object> read) {
            for (int i = 0; i < n; i++) if (!same(values[i], read.apply(tables[i], keys[i]))) return false;
            return true;
        }

        int size() { return n; }

        void clear() {
            Arrays.fill(tables, 0, n, null);
            Arrays.fill(keys, 0, n, null);
            Arrays.fill(values, 0, n, null);
            n = 0;
        }
    }

    /** What decides whether an element draws the same pictures: its own and its parent's place, size, clip and children. */
    static final class Geometry {
        double absX, absY, width, height, xScroll, yScroll, y, pAbsY, pHeight;
        int maxDrawHeight, pMaxDraw, kids, kidsHash;
        boolean visible, enabled, hasParent, pClipped;

        static Geometry of(UIElement e) {
            Geometry g = new Geometry();
            g.absX = d(e.getAbsoluteX());
            g.absY = d(e.getAbsoluteY());
            g.width = d(e.getWidth());
            g.height = d(e.getHeight());
            g.xScroll = d(e.getXScroll());
            g.yScroll = d(e.getYScroll());
            g.y = e.y;
            g.maxDrawHeight = e.maxDrawHeight;
            g.visible = Boolean.TRUE.equals(e.isVisible());
            g.enabled = e.isEnabled();
            UIElement p = e.getParent();
            if (p != null) {
                g.hasParent = true;
                g.pAbsY = d(p.getAbsoluteY());
                g.pHeight = d(p.getHeight());
                g.pMaxDraw = p.maxDrawHeight;
                g.pClipped = F.CLIPPED != null && (boolean) F.CLIPPED.get(p);
            }
            ArrayList<UIElement> c = e.getControls();
            if (c != null) {
                g.kids = c.size();
                int h = 1;
                for (int i = 0; i < g.kids; i++) h = h * 31 + System.identityHashCode(c.get(i));
                g.kidsHash = h;
            }
            return g;
        }

        private static double d(Double v) { return v == null ? Double.NaN : v; }

        boolean equals(Geometry o) {
            return o != null && bits(absX) == bits(o.absX) && bits(absY) == bits(o.absY) && bits(width) == bits(o.width)
                && bits(height) == bits(o.height) && bits(xScroll) == bits(o.xScroll) && bits(yScroll) == bits(o.yScroll)
                && bits(y) == bits(o.y) && maxDrawHeight == o.maxDrawHeight && visible == o.visible && enabled == o.enabled
                && hasParent == o.hasParent && bits(pAbsY) == bits(o.pAbsY) && bits(pHeight) == bits(o.pHeight)
                && pMaxDraw == o.pMaxDraw && pClipped == o.pClipped && kids == o.kids && kidsHash == o.kidsHash;
        }

        private static long bits(double v) { return Double.doubleToLongBits(v); }
    }

    /** One Java getter the window called: what it was called with and what it answered. */
    static final class Call {
        final CallInfo info;
        final Object self;
        final Object[] args;
        Object answer;
        boolean answered;

        Call(CallInfo info, Object self, Object[] args) {
            this.info = info;
            this.self = self;
            this.args = args;
        }

        boolean stillSame() {
            if (!answered) return true;   // it pushed nothing (void, or it threw): nothing to compare
            try { return sameAnswer(answer, info.method.invoke(info.hasSelf ? self : info.owner, args)); }
            catch (Throwable t) { return false; }
        }
    }

    /** A getter's answer: arrays by content (element by element with same), anything else with same. */
    static boolean sameAnswer(Object a, Object b) {
        if (a instanceof Object[] x && b instanceof Object[] y) {
            if (x.length != y.length) return false;
            for (int i = 0; i < x.length; i++) if (!sameAnswer(x[i], y[i])) return false;
            return true;
        }
        return same(a, b);
    }

    /** A MethodCaller's method, read once: how it is called again and what kind of answer it gives. */
    static final class CallInfo {
        final Method method;
        final Object owner;
        final boolean hasSelf;
        final int kind;
        final boolean reinvoke;

        CallInfo(Method method, Object owner, boolean hasSelf) {
            this.method = method;
            this.owner = owner;
            this.hasSelf = hasSelf;
            String name = method.getName();
            kind = UiCensus.kind(name);
            reinvoke = kind == UiCensus.OTHER && (name.startsWith("get") || name.startsWith("is") || name.startsWith("has")
                || name.startsWith("can") || name.startsWith("contains") || name.startsWith("size") || name.startsWith("count"));
        }
    }

    /** An item icon the window drew, with the Java values the icon draw reads from the item. */
    static final class Icon {
        final InventoryItem item;
        final Object[] values;

        Icon(InventoryItem item) {
            this.item = item;
            this.values = iconValues(item);
        }

        boolean stillSame() {
            Object[] now = iconValues(item);
            if (now.length != values.length) return false;
            for (int i = 0; i < now.length; i++) if (!UiRetain.same(values[i], now[i])) return false;
            return true;
        }
    }

    static Object[] iconValues(InventoryItem it) {
        FluidContainer fc = it.getFluidContainer();
        if (fc == null) {
            zombie.iso.objects.IsoWorldInventoryObject w = it.getWorldItem();
            if (w != null) fc = w.getFluidContainer();
        }
        Object[] v = new Object[fc == null ? 6 : 12];
        v[0] = it.getTex();
        v[1] = it.getR();
        v[2] = it.getG();
        v[3] = it.getB();
        v[4] = it.getTextureColorMask();
        v[5] = it.getTextureFluidMask();
        if (fc != null) {
            v[6] = fc.getAmount();
            v[7] = fc.getCapacity();
            zombie.core.Color c = fc.getColor();
            v[8] = c == null ? null : c.r;
            v[9] = c == null ? null : c.g;
            v[10] = c == null ? null : c.b;
            v[11] = c == null ? null : c.a;
        }
        return v;
    }

    /** What one top-level window kept from its last real render. */
    static final class Record {
        Geometry geometry;
        int stencilIn, stencilOut;
        long epoch;
        boolean fbo;
        final ReadSet reads = new ReadSet();
        Call[] calls = new Call[0];
        int nCalls;
        Icon[] icons = new Icon[0];
        int nIcons;
        UIElement[] kidEls = new UIElement[0];
        Geometry[] kidGeo = new Geometry[0];
        int nKids;
        boolean volatileSeen, errorSeen, mouseSeen, live;
        int mouseX, mouseY;   // where the mouse was when a window read it
        UiSlice slice;   // null: nothing replayable kept
        UiSlice spare;   // the slice object to record into again

        Call addCall(Call c) {
            if (nCalls == calls.length) calls = Arrays.copyOf(calls, Math.max(8, nCalls * 2));
            return calls[nCalls++] = c;
        }

        void addIcon(Icon i) {
            if (nIcons == icons.length) icons = Arrays.copyOf(icons, Math.max(8, nIcons * 2));
            icons[nIcons++] = i;
        }

        void addKid(UIElement e) {
            if (nKids == kidEls.length) {
                kidEls = Arrays.copyOf(kidEls, Math.max(8, nKids * 2));
                kidGeo = Arrays.copyOf(kidGeo, kidEls.length);
            }
            kidEls[nKids] = e;
            kidGeo[nKids++] = Geometry.of(e);
        }

        void reset() {
            reads.clear();
            Arrays.fill(calls, 0, nCalls, null);
            Arrays.fill(icons, 0, nIcons, null);
            Arrays.fill(kidEls, 0, nKids, null);
            Arrays.fill(kidGeo, 0, nKids, null);
            nCalls = nIcons = nKids = 0;
            volatileSeen = errorSeen = mouseSeen = false;
        }
    }

    /** The replay rule for the parts that need no game objects: the Java getters, icons and children are checked apart. */
    static boolean canReplay(Record r, int stencilNow, long epochNow, boolean fboNow, Geometry gNow,
                             BiFunction<Object, Object, Object> read) {
        return r.slice != null && !r.volatileSeen && !r.errorSeen && r.epoch == epochNow && r.fbo == fboNow
            && r.stencilIn == stencilNow && r.geometry != null && r.geometry.equals(gNow) && r.reads.stillSame(read);
    }

    static boolean callsSame(Record r) {
        for (int i = 0; i < r.nCalls; i++) if (!r.calls[i].stillSame()) return false;
        return true;
    }

    private static boolean iconsSame(Record r) {
        for (int i = 0; i < r.nIcons; i++) if (!r.icons[i].stillSame()) return false;
        return true;
    }

    private static boolean kidsSame(Record r) {
        for (int i = 0; i < r.nKids; i++) if (!Geometry.of(r.kidEls[i]).equals(r.kidGeo[i])) return false;
        return true;
    }

    /** A window that read the mouse position keeps its picture only while the mouse stays where it was. */
    static boolean mouseClear(Record r, int mx, int my) {
        return !r.mouseSeen || r.mouseX == mx && r.mouseY == my;
    }

    private static final Map<Class<?>, Boolean> javaRender = new IdentityHashMap<>();

    /** True when the element or anything in its getControls() tree is of a class other than UIElement that declares render(). */
    static boolean hasJavaRender(UIElement e) { return hasJavaRender(e, 0); }

    private static boolean hasJavaRender(UIElement e, int level) {
        if (level > 32) return true;   // deeper than any real window: not worth tracing
        if (overridesRender(e.getClass())) return true;
        ArrayList<UIElement> c = e.getControls();
        if (c != null) for (int i = 0; i < c.size(); i++) if (hasJavaRender(c.get(i), level + 1)) return true;
        return false;
    }

    private static boolean overridesRender(Class<?> k) {
        if (k == UIElement.class) return false;
        Boolean b = javaRender.get(k);
        if (b == null) {
            boolean has = false;
            for (Class<?> c = k; c != null && c != UIElement.class && !has; c = c.getSuperclass()) {
                try { c.getDeclaredMethod("render"); has = true; } catch (NoSuchMethodException ignored) { }
            }
            javaRender.put(k, b = has);
        }
        return b;
    }

    static Set<String> parseNames(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    static Set<String> excludedNames() { return excluded; }

    static boolean excluded(Set<String> names, String javaClass, String luaType) {
        return names.contains(javaClass) || luaType != null && names.contains(luaType);
    }

    /** A window file under the game's media/lua (no mods or workshop folder on the way); a Java window has none. */
    static boolean gameFile(String file) {
        if (file == null) return true;
        String f = file.replace('\\', '/');
        if (!f.contains("media/lua/")) return false;
        for (String seg : f.split("/")) if (seg.equals("mods") || seg.equals("workshop")) return false;
        return true;
    }

    // ---- epoch ----

    /** A fact every kept picture depends on changed (resize, texture lifetime, FBO option, fonts, game state): drop them all. */
    static void bumpEpoch() { EPOCH.incrementAndGet(); }

    /** UIManager.resize exit: it runs every frame; only a real size change counts. */
    static void onResize() {
        if (!(guard.on || check.on)) return;
        try {
            Core c = Core.getInstance();
            int w = c.getScreenWidth(), h = c.getScreenHeight();
            if (w != lastW || h != lastH) { lastW = w; lastH = h; bumpEpoch(); }
        } catch (Throwable t) { fail(t); }
    }

    // ---- render hooks (main thread) ----

    private static boolean main() { return Thread.currentThread() == FrameClock.mainThread; }

    static void fail(Throwable t) {
        abort();
        guard.fail(t);
        check.fail(t);
        any = guard.on || check.on;
    }

    /** UIManager.render entry: a new UI frame; whatever a lost exit left open is dropped. */
    static void frameStart() {
        if (root != null) abort();
    }

    private static void abort() {
        root = null;
        rec = null;
        noting = false;
        depth = callDepth = 0;
        checkSlice = null;
        suspended = false;
    }

    /** UIElement.render entry. True: the window was replayed and the stock render must be skipped. */
    static boolean onRenderEnter(UIElement e) {
        if (!any || !main()) return false;
        try {
            if (root != null) {
                depth++;
                if (rec != null) rec.addKid(e);
                return false;
            }
            return begin(e);
        } catch (Throwable t) { fail(t); return false; }
    }

    /** UIElement.render exit, for a render that was not replayed. threw: the render ended with an exception. */
    static void onRenderExit(UIElement e, boolean threw) {
        if (root == null || !main()) return;
        try {
            if (depth > 0) { depth--; return; }
            if (root != e) { abort(); return; }
            finish(e, threw);
        } catch (Throwable t) { fail(t); }
    }

    private static boolean begin(UIElement e) {
        long ep = checkEpoch();
        Record r = records.get(e);
        if (r == null) {
            r = new Record();
            r.live = excluded(excluded, e.getClass().getSimpleName(), UiTiming.typeOf(e))
                || !modsToo && !gameFile(UiTiming.windowFile(e));
            records.put(e, r);
        }
        root = e;
        depth = callDepth = 0;
        if (!r.live && overridesRender(e.getClass())) r.live = true;   // its Java render draws from Java fields nobody watches
        if (r.live || Core.debug || !(GameWindow.states.current instanceof IngameState)) return false;
        GenericSpriteRenderState s = populating();
        if (s == null) return false;
        int stencil = (int) F.STENCIL.get();
        boolean fbo = UIManager.useUiFbo;
        UiSlice old = r.slice;
        boolean would = old != null && verify(r, e, stencil, ep, fbo);
        if (would && guard.on && !check.on) {
            if (tryReplay(r, old, s)) { root = null; return true; }
            if (any) root = e;   // this window and its children draw live this frame, nothing is recorded
            return false;
        }
        r.slice = null;
        r.reset();
        r.geometry = Geometry.of(e);
        r.stencilIn = stencil;
        r.epoch = ep;
        r.fbo = fbo;
        checkSlice = would ? old : null;
        GLState.startFrame();
        openState = s;
        openFrom = s.numSprites;
        rec = r;
        noting = true;
        return false;
    }

    /** Replays a kept slice. On a throw nothing partial stays in the queue and nothing stays open; stock draws. */
    static boolean tryReplay(Record r, UiSlice old, GenericSpriteRenderState s) {
        int n0 = s.numSprites;
        try {
            GLState.startFrame();
            UiSlice.replay(old, s);
            GLState.startFrame();
            F.STENCIL.set(r.stencilOut);
            return true;
        } catch (Throwable t) {
            s.numSprites = n0;
            fail(t);   // aborts what is open and counts the failure
            return false;
        }
    }

    /** Every re-read of a window's record, with nothing noted. */
    private static boolean verify(Record r, UIElement e, int stencil, long ep, boolean fbo) {
        suspended = true;
        try {
            return canReplay(r, stencil, ep, fbo, Geometry.of(e), (t, k) -> ((KahluaTable) t).rawget(k))
                && kidsSame(r) && callsSame(r) && iconsSame(r) && mouseClear(r, Mouse.getXA(), Mouse.getYA());
        } catch (Throwable t) { fail(t); return false; }
        finally { suspended = false; }
    }

    private static long checkEpoch() {
        Object st = GameWindow.states == null ? null : GameWindow.states.current;
        int np = IsoPlayer.numPlayers;
        if (st != lastState || np != lastPlayers) { lastState = st; lastPlayers = np; bumpEpoch(); }
        long ep = EPOCH.get();
        if (ep != seenEpoch) { records.clear(); seenEpoch = ep; }
        return ep;
    }

    /** The state the UI draws into; tests replace it. */
    static java.util.function.Supplier<GenericSpriteRenderState> populating = () -> SpriteRenderer.instance.states.getPopulatingActiveState();

    private static GenericSpriteRenderState populating() { return populating.get(); }

    private static void finish(UIElement e, boolean threw) {
        Record r = rec;
        GenericSpriteRenderState s = openState;
        int from = openFrom;
        UiSlice was = checkSlice;
        root = null;
        rec = null;
        openState = null;
        noting = false;
        checkSlice = null;
        depth = callDepth = 0;
        if (r == null) return;
        GenericSpriteRenderState s2 = populating();
        int to = s2 == null ? -1 : s2.numSprites;
        boolean ok = !threw && s2 == s && to >= from;
        if (ok && was != null) checkAgainst(e, was, s, from, to);
        if (ok && !r.volatileSeen && hasJavaRender(e)) r.volatileSeen = true;
        r.stencilOut = (int) F.STENCIL.get();
        if (ok && !r.errorSeen && !r.volatileSeen && Geometry.of(e).equals(r.geometry)) {   // a render that moved itself is not kept
            UiSlice sl = UiSlice.record(s, from, to, r.spare);
            if (sl != null) {
                sl.stencilIn = r.stencilIn;
                sl.stencilOut = r.stencilOut;
                r.spare = sl;
            }
            r.slice = sl;
        }
        if (threw) r.slice = null;
        GLState.startFrame();
    }

    // ---- uiRetainCheck ----

    private static final Map<String, int[]> checks = new HashMap<>();   // window -> {would replay, differed}
    private static final class DrawFields {
        static final Field[] ALL = Arrays.stream(TextureDraw.class.getFields())
            .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers())).toArray(Field[]::new);
    }

    private static void checkAgainst(UIElement e, UiSlice was, GenericSpriteRenderState s, int from, int to) {
        String name = UiTiming.nameOf(e);
        int[] c = checks.computeIfAbsent(name, k -> new int[2]);
        c[0]++;
        if (!UiSlice.sameAs(was, s, from, to)) {
            if (c[1]++ == 0) for (String line : mismatches(was, s, from, to, 5)) System.out.println("[PZButter] uiRetainCheck " + name + ": " + line);
        }
        if (c[0] >= 100 && c[0] % 100 == 0 || c[0] == 1) {
            System.out.println("[PZButter] uiRetainCheck " + name + ": would replay " + c[0] + " times, draw list differed " + c[1]);
        }
    }

    /** The first differences (entry index and field) between a kept slice and a live range. */
    static java.util.List<String> mismatches(UiSlice a, GenericSpriteRenderState s, int from, int to, int max) {
        java.util.List<String> out = new ArrayList<>();
        if (a.n != to - from) { out.add("entries " + a.n + " kept, " + (to - from) + " live"); return out; }
        for (int i = 0; i < a.n && out.size() < max; i++) {
            if (a.styles[i] != s.style[from + i]) out.add("entry " + i + " field style");
            TextureDraw x = a.entries[i], y = s.sprite[from + i];
            for (Field f : DrawFields.ALL) {
                try {
                    if (!Objects.deepEquals(f.get(x), f.get(y)) && out.size() < max) out.add("entry " + i + " field " + f.getName());
                } catch (Throwable t) { out.add("entry " + i + " field " + f.getName() + " unreadable"); }
            }
        }
        if (out.isEmpty()) out.add("differs in a private field of an entry");
        return out;
    }

    // ---- notes (main thread, while a recorded render is open) ----

    /** A Lua table read by the open window. */
    static void noteRead(Object table, Object key, Object value) {
        if (!noting || suspended || !main()) return;
        rec.reads.add(table, key, value);
    }

    /** A Java method called from Lua starts. A getter is kept with a copy of its arguments (Kahlua reuses the array). */
    static void noteCall(Object caller, Object self, Object args) {
        if (!noting || suspended || !main()) return;
        Call c = null;
        CallInfo ci = info(caller);
        if (ci == null || caller instanceof AbstractCaller ac && ac.needsMultipleReturnValues()) rec.volatileSeen = true;   // cannot tell what it is, or it answers with several values
        else if (ci.kind == UiCensus.TIME || ci.kind == UiCensus.RANDOM) rec.volatileSeen = true;
        else if (ci.kind == UiCensus.MOUSE) {
            rec.mouseSeen = true;
            rec.mouseX = Mouse.getXA();
            rec.mouseY = Mouse.getYA();
            c = MOUSE_CALL;
        } else if (ci.reinvoke) c = rec.addCall(new Call(ci, self, args instanceof Object[] a ? a.clone() : NO_ARGS));
        if (callDepth < pending.length) pending[callDepth] = c;
        callDepth++;
    }

    static void callExit() {
        if (noting && callDepth > 0 && main()) callDepth--;
    }

    /** A Java answer for Lua: the answer of the innermost open getter. */
    static void noteAnswer(Object value) {
        if (!noting || suspended || callDepth == 0 || !main()) return;
        Call c = pending[Math.min(callDepth, pending.length) - 1];
        if (c == MOUSE_CALL) return;
        if (c == null) { rec.volatileSeen = true; return; }   // an answer nobody can check again
        if (!c.answered) {
            c.answer = value instanceof Object[] a ? a.clone() : value;
            c.answered = true;
        }
    }

    private static final Map<String, int[]> writeLogs = new HashMap<>();

    /** A Lua table write by the open window: one that changes what the table holds makes the window live. */
    static void noteWrite(Object table, Object key, Object value) {
        if (!noting || suspended || !main()) return;
        suspended = true;
        boolean same;
        try { same = same(((KahluaTable) table).rawget(key), value); }
        finally { suspended = false; }
        if (same) return;
        rec.volatileSeen = true;
        if (check.on && root instanceof UIElement e) {
            int[] n = writeLogs.computeIfAbsent(UiTiming.nameOf(e), k -> new int[1]);
            if (n[0]++ < 5) System.out.println("[PZButter] uiRetainCheck " + UiTiming.nameOf(e) + ": render writes key " + key);
        }
    }

    /** The window reads the clock, random numbers or iterates a table: it is live. */
    static void noteVolatile() {
        if (!noting || suspended || !main()) return;
        rec.volatileSeen = true;
    }

    static void noteItemIcon(Object item) {
        if (!noting || suspended || item == null || !main()) return;
        rec.addIcon(new Icon((InventoryItem) item));
    }

    /** Lua logged an exception: whatever this render drew is not kept. */
    static void noteError() {
        if (!noting || suspended || !main()) return;
        rec.errorSeen = true;
    }

    private static final Call MOUSE_CALL = new Call(null, null, null);   // a mouse answer: kept as the position it came from

    private static CallInfo info(Object caller) {
        CallInfo ci = infos.get(caller);
        if (ci == null && !infos.containsKey(caller)) {
            try {
                MethodCaller mc = (MethodCaller) caller;
                if (F.METHOD != null && F.OWNER != null && F.HAS_SELF != null)
                    ci = new CallInfo((Method) F.METHOD.get(mc), F.OWNER.get(mc), (boolean) F.HAS_SELF.get(mc));
            } catch (Throwable t) { ci = null; }
            infos.put(caller, ci);
        }
        return ci;
    }

    // ---- test entry points (package-private) ----

    /** Tests run on the test thread: it becomes the main thread, and r the open record. */
    static void openForTest(Record r) { openForTest(r, new Object(), null); }

    static void openForTest(Record r, Object rootElement, GenericSpriteRenderState s) {
        FrameClock.mainThread = Thread.currentThread();
        abort();
        root = rootElement;
        rec = r;
        noting = true;
        openState = s;
        openFrom = s == null ? 0 : s.numSprites;
    }

    static int depthForTest() { return depth; }

    static boolean openForTest() { return root != null; }

    static void closeForTest() { abort(); }
}
