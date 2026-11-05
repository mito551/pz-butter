package ves.perf;

import java.util.ArrayList;
import java.util.Set;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.ui.UIElement;
import zombie.ui.UIElementInterface;
import zombie.ui.UIManager;

/**
 * One call instead of a Lua loop with three pcall closures per UI element.
 * Returns the Lua tables of every window in the UI manager whose class is one
 * the Viewpoint Extended Support cursor logic cares about. Visibility is left
 * to the caller, which re-checks it cheaply between scans.
 *
 * The list must match CURSOR_WINDOW_CLASS_NAMES in ViewpointExtendedSupport.lua.
 */
public final class Windows {
    private static final Set<String> WATCHED = Set.of(
        "ISRadialMenu", "ISWorldMap", "ISMapWrapper", "ISCraftingUI",
        "ISHandcraftWindow", "ISBuildWindow", "ISCharacterInfoWindow");

    private Windows() {}

    @LuaMethod(name = "vesPerfWatchedWindows", global = true)
    public static KahluaTable watchedWindows() {
        KahluaTable out = LuaManager.platform.newTable();
        ArrayList<UIElementInterface> ui = UIManager.getUI();
        int n = 0;
        // the list is only touched from the main thread, but stay safe if a
        // window is removed while we walk it
        for (int i = 0; i < ui.size(); i++) {
            UIElementInterface element;
            try {
                element = ui.get(i);
            } catch (IndexOutOfBoundsException e) {
                break;
            }
            if (!(element instanceof UIElement)) continue;
            KahluaTable table = ((UIElement) element).getTable();
            if (table != null && WATCHED.contains(typeOf(table))) {
                out.rawset(++n, table);
            }
        }
        return out;
    }

    /** "Type" lives on the class table, so walk the __index chain like Lua does. */
    private static String typeOf(KahluaTable table) {
        KahluaTable current = table;
        for (int depth = 0; current != null && depth < 16; depth++) {
            Object type = current.rawget("Type");
            if (type instanceof String s) return s;
            KahluaTable meta = current.getMetatable();
            if (meta == null) return null;
            Object index = meta.rawget("__index");
            current = index instanceof KahluaTable k ? k : null;
        }
        return null;
    }
}
