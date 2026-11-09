package bgi.perf;

import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.objects.IsoGenerator;

/**
 * Moves the O(R^2) stencil build for Better Generator Info out of Lua and into
 * compiled code. Result tables are 1-based, matching Lua array conventions.
 */
public final class Stencil {
    private static boolean reported;

    private Stencil() {}

    @LuaMethod(name = "bgiStencil", global = true)
    public static KahluaTable stencil(int R, int pad) {
        KahluaTable out = LuaManager.platform.newTable();
        try {
            int reach = R + Math.max(0, pad);
            int size = (2 * reach + 1);
            boolean[][] powered = new boolean[size][size];
            int offset = reach;

            KahluaTable poweredOffsets = LuaManager.platform.newTable();
            int np = 0;
            for (int y = -reach; y <= reach; y++) {
                for (int x = -reach; x <= reach; x++) {
                    boolean p = IsoGenerator.isPoweringSquare(0, 0, 0, x, y, 0);
                    powered[y + offset][x + offset] = p;
                    if (p) {
                        KahluaTable t = LuaManager.platform.newTable();
                        t.rawset(1, (Object) Double.valueOf(x));
                        t.rawset(2, (Object) Double.valueOf(y));
                        poweredOffsets.rawset(++np, t);
                    }
                }
            }

            KahluaTable edgeOffsets = LuaManager.platform.newTable();
            int ne = 0;
            int[][] nbrs = { {-1,-1},{0,-1},{1,-1},{-1,0},{1,0},{-1,1},{0,1},{1,1} };
            for (int y = -reach; y <= reach; y++) {
                for (int x = -reach; x <= reach; x++) {
                    if (powered[y + offset][x + offset]) continue;
                    boolean touches = false;
                    for (int i = 0; i < nbrs.length; i++) {
                        int nx = x + nbrs[i][0];
                        int ny = y + nbrs[i][1];
                        if (nx < -reach || nx > reach || ny < -reach || ny > reach) continue;
                        if (powered[ny + offset][nx + offset]) { touches = true; break; }
                    }
                    if (touches) {
                        KahluaTable t = LuaManager.platform.newTable();
                        t.rawset(1, (Object) Double.valueOf(x));
                        t.rawset(2, (Object) Double.valueOf(y));
                        edgeOffsets.rawset(++ne, t);
                    }
                }
            }

            out.rawset("poweredOffsets", poweredOffsets);
            out.rawset("edgeOffsets", edgeOffsets);
        } catch (Throwable t) {
            // Return an empty table so Lua falls back, but say why (once).
            if (!reported) {
                reported = true;
                t.printStackTrace();
            }
        }
        return out;
    }
}
