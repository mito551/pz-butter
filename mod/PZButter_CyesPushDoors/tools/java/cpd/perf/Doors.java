package cpd.perf;

import java.util.List;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;

/**
 * Native versions of the two things Cye's Push Doors polls from Lua every couple of ticks.
 */
public final class Doors {
    private Doors() {}

    /** Same test as CyesPushDoors.isSupportedDoor. */
    static boolean isSupportedDoor(IsoObject object) {
        if (object instanceof IsoDoor) return true;
        return object instanceof IsoThumpable thumpable && thumpable.isDoor();
    }

    /**
     * Every attached, supported door in the (2r+1) x (2r+1) block of squares around (x, y) on
     * floor z, as a Lua array. "Attached" is the scanner's own test: the door has a square and
     * is still in that square's object list.
     */
    @LuaMethod(name = "cpdDoorsNear", global = true)
    public static KahluaTable doorsNear(double x, double y, double z, double r) {
        KahluaTable out = LuaManager.platform.newTable();
        IsoWorld world = IsoWorld.instance;
        IsoCell cell = world == null ? null : world.getCell();
        if (cell == null) return out;

        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        int pz = (int) Math.floor(z);
        int radius = (int) Math.max(0, Math.min(8, r));
        int count = 0;
        for (int sx = px - radius; sx <= px + radius; sx++) {
            for (int sy = py - radius; sy <= py + radius; sy++) {
                IsoGridSquare square = cell.getGridSquare(sx, sy, pz);
                if (square == null) continue;
                List<IsoObject> objects = square.getObjects();
                if (objects == null) continue;
                for (int i = 0, n = objects.size(); i < n; i++) {
                    IsoObject object = objects.get(i);
                    if (object != null && isSupportedDoor(object)
                            && object.getSquare() != null && object.getObjectIndex() >= 0) {
                        out.rawset(++count, object);
                    }
                }
            }
        }
        return out;
    }
}
