package wkt.perf;

import java.util.List;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.inventory.types.Key;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoWorldInventoryObject;

/** Render-capture + native ground query for WKT_Perf. */
public final class GroundKeys {
    private static final SeenBuffer<IsoWorldInventoryObject> SEEN =
            new SeenBuffer<>();

    private GroundKeys() {}

    public static boolean seen(IsoWorldInventoryObject obj) {
        return SEEN.add(obj);
    }

    @LuaMethod(name = "wktDrainGroundKeys", global = true)
    public static KahluaTable drain() {
        List<IsoWorldInventoryObject> list = SEEN.drain();
        if (list == null || list.isEmpty()) return null;
        KahluaTable out = LuaManager.platform.newTable();
        int i = 1;
        for (IsoWorldInventoryObject o : list) {
            out.rawset(i++, o);
        }
        return out;
    }

    @LuaMethod(name = "wktGroundKeysNear", global = true)
    public static KahluaTable near(double x, double y, double z, double r, double playerNum) {
        KahluaTable out = LuaManager.platform.newTable();
        IsoWorld world = IsoWorld.instance;
        IsoCell cell = world == null ? null : world.getCell();
        if (cell == null) return out;

        int px = (int) Math.floor(x);
        int py = (int) Math.floor(y);
        int pz = (int) Math.floor(z);
        if (r < 0) r = 0;
        int maxD = (int) Math.ceil(r);

        int count = 0;
        for (int di = 0; di <= maxD; di++) {
            int[] cds = (di == 0) ? new int[] { 0 } : new int[] { di, -di };
            for (int cd : cds) {
                int sx = px + cd;
                double dxCol = (sx + 0.5) - x;
                double spanD = Math.sqrt(Math.max(0.0, r * r - dxCol * dxCol));
                int span = (int) Math.floor(spanD);
                for (int sy = py - span; sy <= py + span; sy++) {
                    IsoGridSquare square = cell.getGridSquare(sx, sy, pz);
                    if (square == null) continue;
                    if (!square.isCouldSee((int) playerNum)) continue;
                    List<IsoWorldInventoryObject> wos = square.getWorldObjects();
                    if (wos == null) continue;
                    for (IsoWorldInventoryObject wo : wos) {
                        if (wo == null) continue;
                        if (wo.getItem() instanceof Key) {
                            out.rawset(++count, wo);
                        }
                    }
                }
            }
        }
        return out;
    }
}
