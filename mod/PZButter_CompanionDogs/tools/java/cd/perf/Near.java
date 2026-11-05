package cd.perf;

import java.util.List;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.inventory.InventoryItem;
import zombie.iso.objects.IsoFeedingTrough;
import zombie.iso.objects.IsoWorldInventoryObject;

/**
 * Native box query for the perf mod. Returns a single Lua table holding, on request,
 * zombies, animals, troughs and matching dishes found in the square box around (x, y, z).
 */
public final class Near {
    private Near() {}

    @LuaMethod(name = "cdNear", global = true)
    public static KahluaTable near(double x, double y, double z, double r,
                                 boolean wantZombies, boolean wantAnimals,
                                 boolean wantTroughs, Object dishTypeArg) {
        KahluaTable out = LuaManager.platform.newTable();
        try {
            String dishType = (dishTypeArg instanceof String) ? (String) dishTypeArg : null;

            IsoWorld world = IsoWorld.instance;
            IsoCell cell = (world == null) ? null : world.getCell();
            if (cell == null) return out;

            int cx = (int) Math.floor(x);
            int cy = (int) Math.floor(y);
            int cz = (int) Math.floor(z);
            int R = (int) Math.ceil(r);
            if (R < 0) R = 0;
            if (R > 48) R = 48;

            // one walk over the box, in the originals' order (dx outer, dy inner)
            KahluaTable zs = wantZombies ? LuaManager.platform.newTable() : null;
            KahluaTable as = wantAnimals ? LuaManager.platform.newTable() : null;
            // troughs and dishes in one list, troughs first within a square, as findTroughNear visits them
            KahluaTable fs = wantTroughs ? LuaManager.platform.newTable() : null;
            int nz = 0, na = 0, nf = 0;
            for (int dx = -R; dx <= R; dx++) {
                for (int dy = -R; dy <= R; dy++) {
                    IsoGridSquare sq = cell.getGridSquare(cx + dx, cy + dy, cz);
                    if (sq == null) continue;
                    if (zs != null) {
                        List<IsoMovingObject> mo = sq.getMovingObjects();
                        if (mo != null) for (int i = 0, n = mo.size(); i < n; i++) {
                            Object o = mo.get(i);
                            if (o instanceof IsoZombie) zs.rawset(++nz, o);
                        }
                    }
                    if (as != null) {
                        List<IsoAnimal> an = sq.getAnimals();
                        if (an != null) for (int i = 0, n = an.size(); i < n; i++) {
                            Object o = an.get(i);
                            if (o != null) as.rawset(++na, o);
                        }
                    }
                    if (fs != null) {
                        List<IsoObject> ob = sq.getObjects();
                        if (ob != null) for (int i = 0, n = ob.size(); i < n; i++) {
                            Object o = ob.get(i);
                            if (o instanceof IsoFeedingTrough) fs.rawset(++nf, o);
                        }
                    }
                    if (fs != null && dishType != null) {
                        List<IsoWorldInventoryObject> wo = sq.getWorldObjects();
                        if (wo != null) for (int i = 0, n = wo.size(); i < n; i++) {
                            IsoWorldInventoryObject w = wo.get(i);
                            InventoryItem item = w == null ? null : w.getItem();
                            if (item != null && dishType.equals(item.getFullType())) fs.rawset(++nf, w);
                        }
                    }
                }
            }
            if (zs != null) out.rawset("zombies", zs);
            if (as != null) out.rawset("animals", as);
            if (fs != null) out.rawset("feed", fs);
        } catch (Throwable ignored) { }
        return out;
    }
}
