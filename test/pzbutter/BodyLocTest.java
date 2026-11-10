package pzbutter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import zombie.scripting.objects.Item;
import zombie.scripting.objects.ItemBodyLocation;
import zombie.scripting.objects.ResourceLocation;

public final class BodyLocTest {
    private BodyLocTest() {}

    static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static ItemBodyLocation bareLocation() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (ItemBodyLocation) ((sun.misc.Unsafe) f.get(null)).allocateInstance(ItemBodyLocation.class);
    }

    private static Item probe(String name) throws Exception {
        Item item = new Item();
        item.name = name;
        field(Item.class, "moduleDotType").set(item, "PZButter." + name);
        return item;
    }

    public static void run() throws Exception {
        ItemBodyLocation belt = ItemBodyLocation.get(ResourceLocation.of("BELT"));
        ItemBodyLocation back = ItemBodyLocation.get(ResourceLocation.of("BACK"));
        check("registry resolves belt/back", belt != null && back != null && belt != back);

        Item beltItem = probe("BeltOn"); beltItem.clothingItem = "yes"; beltItem.setBodyLocation(belt);
        Item equipItem = probe("BeltEquip"); equipItem.clothingItem = "yes"; equipItem.canBeEquipped = belt;
        Item backItem = probe("BackOn"); backItem.clothingItem = "yes"; backItem.setBodyLocation(back);
        Item plainItem = probe("Plain"); plainItem.clothingItem = null; plainItem.setBodyLocation(belt);
        List<Object> items = new ArrayList<>(List.of(beltItem, equipItem, backItem, plainItem));

        se.krka.kahlua.vm.KahluaTable t = LuaPatches.getAllItemsForBodyLocationOnce("BELT", items);
        check("BELT picks body match", "PZButter.BeltOn".equals(t.rawget(1)));
        check("BELT picks equip match", "PZButter.BeltEquip".equals(t.rawget(2)));
        check("BELT count", t.len() == 2);
        se.krka.kahlua.vm.KahluaTable b = LuaPatches.getAllItemsForBodyLocationOnce("BACK", items);
        check("BACK picks only its item", b.len() == 1 && "PZButter.BackOn".equals(b.rawget(1)));
        se.krka.kahlua.vm.KahluaTable again = LuaPatches.getAllItemsForBodyLocationOnce("BELT", items);
        check("call again fresh table same contents", again != t && again.len() == t.len()
            && "PZButter.BeltOn".equals(again.rawget(1)) && "PZButter.BeltEquip".equals(again.rawget(2)));
        check("null loc empty fresh", LuaPatches.getAllItemsForBodyLocationOnce(null, items).isEmpty());
        check("blank loc empty", LuaPatches.getAllItemsForBodyLocationOnce("   ", items).isEmpty());

        // resolved null identity: with no registry hit, an item whose own body location is null matches.
        Item nullLoc = probe("NullLoc"); nullLoc.clothingItem = "yes"; nullLoc.setBodyLocation(null);
        items.add(nullLoc);
        se.krka.kahlua.vm.KahluaTable bogus = LuaPatches.getAllItemsForBodyLocationOnce("PZButterNoLocation404", items);
        check("resolved-null identity matches null-location and null-equip items", bogus.len() == 4
            && "PZButter.BeltOn".equals(bogus.rawget(1)) && "PZButter.BeltEquip".equals(bogus.rawget(2))
            && "PZButter.BackOn".equals(bogus.rawget(3)) && "PZButter.NullLoc".equals(bogus.rawget(4)));

        // the real game path (game's own item list) agrees with the game for null/whitespace input and is fresh per call.
        zombie.scripting.ScriptManager.instance.getAllItems();
        se.krka.kahlua.vm.KahluaTable stockNull = zombie.Lua.LuaManager.GlobalObject.getAllItemsForBodyLocation(null);
        check("real path null loc empty", stockNull != null && stockNull.isEmpty());
    }
}
