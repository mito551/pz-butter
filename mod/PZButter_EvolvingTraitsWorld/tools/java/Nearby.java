package etw.perf;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.characters.IsoZombie;
import zombie.iso.areas.IsoRoom;

/**
 * Fast nearby-living-zombie traversal for ETW_Perf. Returns one Lua table per
 * matching zombie so the Lua side can run the (small) per-zombie callbacks
 * without repeating the cell/square iteration in Lua.
 */
public final class Nearby {
    private Nearby() {}

    @LuaMethod(name = "etwPerfNearbyLivingZombies", global = true)
    public static KahluaTable nearbyLivingZombies(Object playerObject, double radius) {
        KahluaTable out = LuaManager.platform.newTable();
        if (!(playerObject instanceof IsoGameCharacter)) return out;
        IsoGameCharacter player = (IsoGameCharacter) playerObject;
        IsoCell cell = player.getCell();
        if (cell == null) return out;

        double r = radius < 0 ? 0 : radius;
        double r2 = r * r;
        float px = player.getX();
        float py = player.getY();
        int pz = (int) Math.floor(player.getZ());
        int tileX = (int) Math.floor(px);
        int tileY = (int) Math.floor(py);
        int tr = (int) Math.ceil(r);
        int n = 0;

        for (int x = tileX - tr; x <= tileX + tr; x++) {
            for (int y = tileY - tr; y <= tileY + tr; y++) {
                IsoGridSquare square = cell.getGridSquare(x, y, pz);
                if (square == null) continue;
                int count = square.getMovingObjects() == null ? 0 : square.getMovingObjects().size();
                for (int i = 0; i < count; i++) {
                    Object o = square.getMovingObjects().get(i);
                    if (!(o instanceof IsoZombie)) continue;
                    IsoZombie z = (IsoZombie) o;
                    if (z.isDead()) continue;
                    float dx = z.getX() - px;
                    float dy = z.getY() - py;
                    double d2 = dx * dx + dy * dy;
                    if (d2 <= r2) {
                        KahluaTable row = LuaManager.platform.newTable();
                        row.rawset("zombie", z);
                        row.rawset("distanceSquared", d2);
                        out.rawset(++n, row);
                    }
                }
            }
        }
        return out;
    }
}
