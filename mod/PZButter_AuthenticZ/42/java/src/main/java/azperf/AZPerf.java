package azperf;

import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.objects.IsoWorldInventoryObject;

import se.krka.kahlua.integration.annotations.LuaMethod;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class AZPerf {
    private static final int SCAN_RADIUS = 12;
    // Keep LIGHT_RADIUS and COLORS in sync with media/lua/server/AuthenticZ_Glowstick.lua
    // so carried and dropped sticks look identical.
    private static final int LIGHT_RADIUS = 8;

    private static final Map<String, float[]> COLORS = new HashMap<>();
    private static final Map<IsoWorldInventoryObject, IsoLightSource> LIGHTS = new IdentityHashMap<>();
    private static IsoCell lastCell;

    static {
        COLORS.put("AuthenticGlowstick_Blue_On", new float[]{0.0f, 0.0f, 1.0f});
        COLORS.put("AuthenticGlowstick_Red_On", new float[]{1.0f, 0.0f, 0.0f});
        COLORS.put("AuthenticGlowstick_Green_On", new float[]{0.0f, 1.0f, 0.0f});
        COLORS.put("AuthenticGlowstick_Purple_On", new float[]{1.0f, 0.0f, 1.0f});
        COLORS.put("AuthenticGlowstick_Yellow_On", new float[]{1.0f, 1.0f, 0.0f});
        COLORS.put("AuthenticGlowstick_Orange_On", new float[]{1.0f, 0.5f, 0.0f});
        COLORS.put("AuthenticGlowstick_Pink_On", new float[]{1.0f, 0.0f, 0.25f});
        COLORS.put("AuthenticGlowstick_White_On", new float[]{1.0f, 1.0f, 1.0f});
    }

    @LuaMethod(name = "AZ_Perf_scanGroundGlowsticks", global = true)
    public static void scanGroundGlowsticks(IsoPlayer player) {
        if (player == null) return;

        IsoCell cell = player.getCell();
        if (cell == null) return;

        int px = (int) Math.floor(player.getX());
        int py = (int) Math.floor(player.getY());
        int pz = (int) Math.floor(player.getZ());

        // A new cell means the old lampposts died with the previous world.
        if (cell != lastCell) {
            LIGHTS.clear();
            lastCell = cell;
        }

        // Lights exist only for the player's current floor; leaving it removes them.
        Set<IsoWorldInventoryObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int x = px - SCAN_RADIUS; x <= px + SCAN_RADIUS; x++) {
            int dx = x - px;
            for (int y = py - SCAN_RADIUS; y <= py + SCAN_RADIUS; y++) {
                int dy = y - py;
                // scan the full square (target scans +/-SCAN_RADIUS, not a circle)

                IsoGridSquare square = cell.getGridSquare(x, y, pz);
                if (square == null) continue;

                for (IsoWorldInventoryObject worldObj : square.getWorldObjects()) {
                    if (worldObj == null) continue;

                    InventoryItem item = worldObj.getItem();
                    if (item == null) continue;

                    float[] color = COLORS.get(item.getType());
                    if (color == null) continue;

                    seen.add(worldObj);

                    float remaining = item.getCurrentUsesFloat();
                    IsoLightSource light = LIGHTS.get(worldObj);

                    if (remaining > 0.0f) {
                        if (light == null) {
                            light = new IsoLightSource(square.getX(), square.getY(), square.getZ(),
                                    color[0], color[1], color[2], LIGHT_RADIUS);
                            light.setActive(true);
                            cell.addLamppost(light);
                            LIGHTS.put(worldObj, light);
                        }
                    } else if (light != null) {
                        light.setActive(false);
                        cell.removeLamppost(light);
                        LIGHTS.remove(worldObj);
                    }
                }
            }
        }

        Iterator<Map.Entry<IsoWorldInventoryObject, IsoLightSource>> it = LIGHTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<IsoWorldInventoryObject, IsoLightSource> e = it.next();
            if (seen.contains(e.getKey())) continue;

            IsoLightSource light = e.getValue();
            light.setActive(false);
            cell.removeLamppost(light);
            it.remove();
        }
    }
}
