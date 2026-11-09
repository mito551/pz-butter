package pzbutter;

import zombie.inventory.ItemPickerJava;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** LTPE-3 (core, gated): virtual insertion at parse time. For every distribution list that contains a source item,
 *  append the mapped target item with the same weight where it is missing, mutating the Java parsed structures
 *  (not the Lua tables). Off leaves both Lua and Java data untouched. */
public final class DistributionInsert {
    public static final Guard guard = new Guard("ltpeVirtualInsertion");

    private static final String[][] PAIRS = {
        {"WheatBagSeed", "LTPE.OatBagSeed"},
        {"JalapenoBagSeed", "LTPE.OatBagSeed"},
        {"WheatSeed", "LTPE.OatSeed"},
        {"WheatSheafDried", "LTPE.OatSheafDried"},
    };

    private DistributionInsert() {}

    public static void init() {
        try {
            guard.on = Settings.enabled && Settings.flag("ltpeVirtualInsertion");
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void apply() {
        if (!guard.on) return;
        try {
            insertAll();
        } catch (Throwable t) { guard.fail(t); }
    }

    public static void insertAll() {
        if (!guard.on) return;
        Set<ItemPickerJava.ItemPickerContainer> seen = new HashSet<>();
        // containers map (suburbs + containers + procedural wrapper)
        try {
            Object map = ItemPickerJava.containers; // THashMap
            for (Object v : values(map)) {
                if (v instanceof ItemPickerJava.ItemPickerContainer c) process(c, seen);
            }
        } catch (Throwable t) { /* try direct fields below */ }
        try {
            Object map = ItemPickerJava.ProceduralDistributions;
            for (Object v : values(map)) {
                if (v instanceof ItemPickerJava.ItemPickerContainer c) process(c, seen);
            }
        } catch (Throwable t) { }
        try { if (ItemPickerJava.getItemPickerContainers() != null)
            for (Object v : values(ItemPickerJava.getItemPickerContainers()))
                if (v instanceof ItemPickerJava.ItemPickerContainer c) process(c, seen);
        } catch (Throwable t) { }
    }

    private static Iterable<Object> values(Object map) {
        ArrayList<Object> out = new ArrayList<>();
        if (map == null) return out;
        try {
            java.lang.reflect.Method m = map.getClass().getMethod("values");
            Object coll = m.invoke(map);
            if (coll instanceof Iterable<?> it) for (Object o : it) out.add(o);
            else if (coll != null && coll.getClass().isArray()) for (Object o : (Object[]) coll) out.add(o);
        } catch (Throwable ignored) { }
        return out;
    }

    static void process(ItemPickerJava.ItemPickerContainer c, Set<ItemPickerJava.ItemPickerContainer> seen) {
        if (c == null || !seen.add(c)) return;
        mirrorItems(c);
        mirrorProcedural(c);
        if (c.junk != null) process(c.junk, seen);
        if (c.bags != null) process(c.bags, seen);
    }

    static void mirrorItems(ItemPickerJava.ItemPickerContainer c) {
        if (c.items == null) return;
        ArrayList<ItemPickerJava.ItemPickerItem> list = new ArrayList<>(Arrays.asList(c.items));
        // required count per (target, chance)
        Map<String, Map<Float, Integer>> required = new HashMap<>();
        for (String[] p : PAIRS) {
            required.computeIfAbsent(p[1], k -> new HashMap<>());
        }
        for (String[] p : PAIRS) {
            Map<Float, Integer> src = new HashMap<>();
            for (ItemPickerJava.ItemPickerItem it : list) {
                if (it != null && it.itemName != null && it.itemName.equals(p[0])) {
                    src.merge(it.chance, 1, Integer::sum);
                }
            }
            for (Map.Entry<Float, Integer> e : src.entrySet()) {
                Map<Float, Integer> r = required.get(p[1]);
                r.merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        // existing target counts
        Map<String, Map<Float, Integer>> existing = new HashMap<>();
        for (String[] p : PAIRS) existing.computeIfAbsent(p[1], k -> new HashMap<>());
        for (ItemPickerJava.ItemPickerItem it : list) {
            if (it == null || it.itemName == null) continue;
            for (String[] p : PAIRS) {
                if (it.itemName.equals(p[1])) {
                    existing.get(p[1]).merge(it.chance, 1, Integer::sum);
                    break;
                }
            }
        }
        boolean changed = false;
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        for (String[] p : PAIRS) targets.add(p[1]);
        for (String target : targets) {
            Map<Float, Integer> req = required.get(target);
            Map<Float, Integer> ex = existing.get(target);
            for (Map.Entry<Float, Integer> e : req.entrySet()) {
                float chance = e.getKey();
                int need = e.getValue();
                int have = ex.getOrDefault(chance, 0);
                while (have < need) {
                    ItemPickerJava.ItemPickerItem ni = new ItemPickerJava.ItemPickerItem();
                    ni.itemName = target;
                    ni.chance = chance;
                    list.add(ni);
                    have++;
                    ex.put(chance, have);
                    changed = true;
                }
            }
        }
        if (changed) c.items = list.toArray(new ItemPickerJava.ItemPickerItem[0]);
    }

    static void mirrorProcedural(ItemPickerJava.ItemPickerContainer c) {
        if (c.proceduralItems == null) return;
        ArrayList<ItemPickerJava.ProceduralItem> list = new ArrayList<>(c.proceduralItems);
        Map<String, Map<Integer, Integer>> required = new HashMap<>();
        for (String[] p : PAIRS) required.computeIfAbsent(p[1], k -> new HashMap<>());

        for (String[] p : PAIRS) {
            Map<Integer, Integer> src = new HashMap<>();
            for (ItemPickerJava.ProceduralItem it : list) {
                if (it != null && it.name != null && it.name.equals(p[0])) {
                    src.merge(it.weightChance, 1, Integer::sum);
                }
            }
            for (Map.Entry<Integer, Integer> e : src.entrySet()) {
                required.get(p[1]).merge(e.getKey(), e.getValue(), Math::max);
            }
        }
        // existing target counts
        Map<String, Map<Integer, Integer>> existing = new HashMap<>();
        for (String[] p : PAIRS) existing.computeIfAbsent(p[1], k -> new HashMap<>());
        for (ItemPickerJava.ProceduralItem it : list) {
            if (it == null || it.name == null) continue;
            for (String[] p : PAIRS) {
                if (it.name.equals(p[1])) {
                    existing.get(p[1]).merge(it.weightChance, 1, Integer::sum);
                    break;
                }
            }
        }
        boolean changed = false;
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        for (String[] p : PAIRS) targets.add(p[1]);
        for (String target : targets) {
            Map<Integer, Integer> req = required.get(target);
            Map<Integer, Integer> ex = existing.get(target);
            for (Map.Entry<Integer, Integer> e : req.entrySet()) {
                int weight = e.getKey();
                int need = e.getValue();
                int have = ex.getOrDefault(weight, 0);
                while (have < need) {
                    ItemPickerJava.ProceduralItem srcProto = null;
                    for (String[] pp : PAIRS) {
                        if (!pp[1].equals(target)) continue;
                        for (ItemPickerJava.ProceduralItem it : list) {
                            if (it != null && it.name != null && it.name.equals(pp[0]) && it.weightChance == weight) { srcProto = it; break; }
                        }
                        if (srcProto != null) break;
                    }
                    ItemPickerJava.ProceduralItem ni = new ItemPickerJava.ProceduralItem();
                    if (srcProto != null) {
                        ni.name = target;
                        ni.min = srcProto.min;
                        ni.max = srcProto.max;
                        ni.forceForItems = srcProto.forceForItems == null ? null : new ArrayList<>(srcProto.forceForItems);
                        ni.forceForZones = srcProto.forceForZones == null ? null : new ArrayList<>(srcProto.forceForZones);
                        ni.forceForTiles = srcProto.forceForTiles == null ? null : new ArrayList<>(srcProto.forceForTiles);
                        ni.forceForRooms = srcProto.forceForRooms == null ? null : new ArrayList<>(srcProto.forceForRooms);
                        ni.weightChance = weight;
                    } else {
                        ni.name = target;
                        ni.weightChance = weight;
                    }
                    list.add(ni);
                    have++;
                    ex.put(weight, have);
                    changed = true;
                }
            }
        }
        if (changed) c.proceduralItems = list;
    }

}