package wkt.perf;

import java.util.ArrayList;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Key;

public final class Keys {
    private Keys() {}

    @LuaMethod(name = "wktKeysIn", global = true)
    public static KahluaTable keysIn(Object container) {
        KahluaTable out = LuaManager.platform.newTable();
        if (!(container instanceof ItemContainer ic)) return out;
        ArrayList<InventoryItem> list = ic.getAllRecurse(item -> item instanceof Key);
        if (list == null) return out;
        int i = 1;
        for (InventoryItem item : list) {
            if (item != null) out.rawset(i++, item);
        }
        return out;
    }
}
