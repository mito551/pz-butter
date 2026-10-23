package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.SpriteRenderer;
import zombie.ui.TextManager;
import zombie.ui.UIElement;
import zombie.ui.UIFont;
import zombie.ui.UIManager;

/**
 * UI hooks, one holder per game method; each advice is one call to a public static method here
 * (advice is inlined into the game class, so it may only reach public members).
 */
public final class UiPatches {
    private UiPatches() {}

    static final Guard tickGuard = new Guard("uiTickStagger");
    private static boolean stockTick, swapped;

    /** UIElement.update entry: a top-level window sees its own staggered tick; its children follow it. */
    public static void onUpdateEnter(Object element) {
        if (!UiTick.on || !tickGuard.on || swapped) return;
        try {
            if (((UIElement) element).getParent() != null) return;
            stockTick = UIManager.doTick;
            swapped = true;
            UIManager.doTick = UiTick.decide(element, System.currentTimeMillis());
        } catch (Throwable t) { tickGuard.fail(t); }
    }

    /** UIElement.update exit: the game's own tick flag back for everything else that reads it. */
    public static void onUpdateExit(Object element) {
        if (!swapped) return;
        try {
            if (((UIElement) element).getParent() != null) return;
            UIManager.doTick = stockTick;
            swapped = false;
        } catch (Throwable t) { tickGuard.fail(t); }
    }

    // ---- textWidthCache ----

    static final Guard widthGuard = new Guard("textWidthCache");

    /** TextManager.MeasureStringX entry: the cached width, or null to measure. */
    public static Object cachedWidth(Object textManager, Object font, String text) {
        if (!TextWidths.on || !widthGuard.on || text == null || Thread.currentThread() != FrameClock.mainThread) return null;
        try {
            Object f = ((TextManager) textManager).getFontFromEnum((UIFont) font);
            return f == null ? null : TextWidths.get(f, text);
        } catch (Throwable t) { widthGuard.fail(t); return null; }
    }

    /** TextManager.MeasureStringX exit after a real measurement: keep it, unless a font is still loading. */
    public static void storeWidth(Object textManager, Object font, String text, int width) {
        if (!TextWidths.on || !widthGuard.on || text == null || Thread.currentThread() != FrameClock.mainThread) return;
        try {
            TextManager tm = (TextManager) textManager;
            if (tm.isAnyFontLoading()) return;
            Object f = tm.getFontFromEnum((UIFont) font);
            if (f != null) TextWidths.put(f, text, width);
        } catch (Throwable t) { widthGuard.fail(t); }
    }

    /** Font loads so far: map street labels laid out with older fonts are rebuilt. */
    static volatile int fontsLoaded;

    /** TextManager.Init exit: fonts were (re)loaded. */
    public static void onFontsLoaded() { fontsLoaded++; TextWidths.clear(); }

    @Patch(className = "zombie.ui.TextManager", methodName = "MeasureStringX")
    public static class MeasureX {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) Object font, @Patch.Argument(1) String text,
                                    @Patch.Local("w") Object w) {
            w = cachedWidth(self, font, text);
            return w != null;
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Argument(0) Object font, @Patch.Argument(1) String text,
                                @Patch.Return(readOnly = false) int ret, @Patch.Local("w") Object w) {
            if (w != null) ret = (Integer) w;
            else storeWidth(self, font, text, ret);
        }
    }

    @Patch(className = "zombie.ui.TextManager", methodName = "Init")
    public static class FontsInit {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onFontsLoaded(); }
    }

    @Patch(className = "zombie.ui.UIElement", methodName = "update")
    public static class Update {
        @Patch.OnEnter
        public static void enter(@Patch.This Object element) { onUpdateEnter(element); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object element) { onUpdateExit(element); }
    }

    // ---- uiTiming ----

    /** UIManager.render entry: one UI frame starts. */
    public static void onUiRenderEnter() {
        if (!UiTiming.guard.on) return;
        try { UiTiming.uiEnter(); } catch (Throwable t) { UiTiming.guard.fail(t); }
    }

    /** UIManager.render exit, also when it threw. */
    public static void onUiRenderExit() {
        if (!UiTiming.guard.on) return;
        try { UiTiming.uiExit(); } catch (Throwable t) { UiTiming.guard.fail(t); }
    }

    /** UIElement.render entry: the element's window starts, or it is nested in one. */
    public static void onElementRenderEnter(Object element) {
        if (UiCensus.guard.on) { try { UiCensus.elementEnter(element); } catch (Throwable t) { UiCensus.guard.fail(t); } }
        if (!UiTiming.guard.on) return;
        try { UiTiming.elementEnter(element, drawCount()); } catch (Throwable t) { UiTiming.guard.fail(t); }
    }

    /** UIElement.render exit, also when it threw. */
    public static void onElementRenderExit(Object element) {
        if (UiCensus.guard.on) { try { UiCensus.elementExit(element); } catch (Throwable t) { UiCensus.guard.fail(t); } }
        if (!UiTiming.guard.on) return;
        try { UiTiming.elementExit(drawCount()); } catch (Throwable t) { UiTiming.guard.fail(t); }
    }

    /** Sprites queued so far in the populating render state, or -1 when unreadable. */
    private static int drawCount() {
        try { return SpriteRenderer.instance.states.getPopulatingActiveState().numSprites; }
        catch (Throwable t) { return -1; }
    }

    // ---- uiCensus ----

    /** MethodCaller.call entry: a Java method called from Lua starts. */
    public static void onJavaCallEnter(Object caller) {
        if (!UiCensus.active()) return;
        try { UiCensus.noteCall(UiCensus.methodName(caller)); } catch (Throwable t) { UiCensus.guard.fail(t); }
    }

    /** MethodCaller.call exit, also when it threw. */
    public static void onJavaCallExit() {
        if (!UiCensus.active()) return;
        try { UiCensus.callExit(); } catch (Throwable t) { UiCensus.guard.fail(t); }
    }

    /** ReturnValues.push entry: a Java answer for Lua. An array is the varargs overload, which pushes each element itself. */
    public static void onJavaAnswer(Object answer) {
        if (!UiCensus.active() || answer instanceof Object[]) return;
        try { UiCensus.noteRead(UiCensus.currentCall(), answer); } catch (Throwable t) { UiCensus.guard.fail(t); }
    }

    /** OsLib.call and RandomLib.call entry: the render reads the clock or random numbers. */
    public static void onVolatileCall() {
        if (!UiCensus.active()) return;
        try { UiCensus.noteVolatile(); } catch (Throwable t) { UiCensus.guard.fail(t); }
    }

    /** UIElement.DrawItemIcon entry. */
    public static void onItemIcon() {
        if (!UiCensus.active()) return;
        try { UiCensus.noteItemIcon(); } catch (Throwable t) { UiCensus.guard.fail(t); }
    }

    @Patch(className = "se.krka.kahlua.integration.expose.caller.MethodCaller", methodName = "call")
    public static class JavaCall {
        @Patch.OnEnter
        public static void enter(@Patch.This Object caller) { onJavaCallEnter(caller); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onJavaCallExit(); }
    }

    @Patch(className = "se.krka.kahlua.integration.expose.ReturnValues", methodName = "push")
    public static class JavaAnswer {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object answer) { onJavaAnswer(answer); }
    }

    @Patch(className = "se.krka.kahlua.stdlib.OsLib", methodName = "call")
    public static class OsCall {
        @Patch.OnEnter
        public static void enter() { onVolatileCall(); }
    }

    @Patch(className = "se.krka.kahlua.stdlib.RandomLib", methodName = "call")
    public static class RandomCall {
        @Patch.OnEnter
        public static void enter() { onVolatileCall(); }
    }

    @Patch(className = "zombie.ui.UIElement", methodName = "DrawItemIcon")
    public static class ItemIcon {
        @Patch.OnEnter
        public static void enter() { onItemIcon(); }
    }

    @Patch(className = "zombie.ui.UIManager", methodName = "render")
    public static class UiRender {
        @Patch.OnEnter
        public static void enter() { onUiRenderEnter(); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { onUiRenderExit(); }
    }

    @Patch(className = "zombie.ui.UIElement", methodName = "render")
    public static class ElementRender {
        @Patch.OnEnter
        public static void enter(@Patch.This Object element) { onElementRenderEnter(element); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object element) { onElementRenderExit(element); }
    }
}
