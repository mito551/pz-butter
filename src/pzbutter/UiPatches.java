package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.ui.UIElement;
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

    @Patch(className = "zombie.ui.UIElement", methodName = "update")
    public static class Update {
        @Patch.OnEnter
        public static void enter(@Patch.This Object element) { onUpdateEnter(element); }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.This Object element) { onUpdateExit(element); }
    }
}
