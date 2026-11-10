package azperf;

import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.DrainableComboItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoWorldInventoryObject;

import se.krka.kahlua.integration.annotations.LuaMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class AZPerf {
    // Values of the target's AuthenticZ_GlowstickGround.lua. The carried-stick file
    // (server/AuthenticZ_Glowstick.lua) uses radius 7 and a darker orange; that is the target's choice.
    private static final int SCAN_RADIUS = 12;
    private static final int LIGHT_RADIUS = 8;
    // GROUND_SCAN_RADIUS of server/AuthenticZ_Glowstick.lua
    private static final int DRAIN_RADIUS = 20;

    private static final Map<String, float[]> COLORS = new HashMap<>();
    private static final Map<IsoWorldInventoryObject, IsoLightSource> LIGHTS = new IdentityHashMap<>();
    private static final Set<IsoWorldInventoryObject> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());
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
        SEEN.clear();

        // The target scans the full +/-SCAN_RADIUS square, not a circle.
        for (int x = px - SCAN_RADIUS; x <= px + SCAN_RADIUS; x++) {
            for (int y = py - SCAN_RADIUS; y <= py + SCAN_RADIUS; y++) {
                IsoGridSquare square = cell.getGridSquare(x, y, pz);
                if (square == null) continue;

                ArrayList<IsoWorldInventoryObject> objects = square.getWorldObjects();
                for (int i = 0, n = objects.size(); i < n; i++) {
                    IsoWorldInventoryObject worldObj = objects.get(i);
                    if (worldObj == null) continue;

                    InventoryItem item = worldObj.getItem();
                    if (item == null) continue;

                    float[] color = COLORS.get(item.getType());
                    if (color == null) continue;

                    SEEN.add(worldObj);

                    IsoLightSource light = LIGHTS.get(worldObj);

                    if (item.getCurrentUsesFloat() > 0.0f) {
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
            if (SEEN.contains(e.getKey())) continue;

            IsoLightSource light = e.getValue();
            light.setActive(false);
            cell.removeLamppost(light);
            it.remove();
        }
        SEEN.clear();
    }

    /**
     * The once-a-minute drain of lit ground glowsticks around every local player
     * (updateGroundGlowsticks in server/AuthenticZ_Glowstick.lua, client/singleplayer branch).
     * A stick in range of two split-screen players drains once, as in the target.
     */
    @LuaMethod(name = "AZ_Perf_drainGroundGlowsticks", global = true)
    public static void drainGroundGlowsticks() {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell == null) return;

        int players = IsoPlayer.numPlayers;
        // One player's scan never meets a square twice; only overlapping scans need the set.
        Set<IsoWorldInventoryObject> processed =
                players > 1 ? Collections.newSetFromMap(new IdentityHashMap<>()) : null;

        for (int p = 0; p < players; p++) {
            IsoPlayer player = IsoPlayer.players[p];
            if (player == null) continue;

            int px = (int) Math.floor(player.getX());
            int py = (int) Math.floor(player.getY());
            int pz = (int) Math.floor(player.getZ());

            for (int x = px - DRAIN_RADIUS; x <= px + DRAIN_RADIUS; x++) {
                for (int y = py - DRAIN_RADIUS; y <= py + DRAIN_RADIUS; y++) {
                    IsoGridSquare square = cell.getGridSquare(x, y, pz);
                    if (square == null) continue;

                    ArrayList<IsoWorldInventoryObject> objects = square.getWorldObjects();
                    // Backwards, so a stick that leaves the list while draining cannot skip a neighbour.
                    for (int i = objects.size() - 1; i >= 0; i--) {
                        if (i >= objects.size()) continue;
                        IsoWorldInventoryObject worldObj = objects.get(i);
                        if (worldObj == null) continue;
                        if (processed != null && !processed.add(worldObj)) continue;

                        InventoryItem item = worldObj.getItem();
                        if (item instanceof DrainableComboItem && COLORS.containsKey(item.getType())) {
                            item.UseAndSync();
                        }
                    }
                }
            }
        }
    }
}
