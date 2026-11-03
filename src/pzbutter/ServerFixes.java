package pzbutter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import zombie.characters.animals.AnimalDefinitions;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;

/**
 * Small server fixes (Plan 18, SPEC 6.8.1 and 6.7.3): the container send guard, the batched
 * process-items merge and the first-tick prewarm. Advice is inlined into game classes, so every
 * method called from a holder is public.
 */
public final class ServerFixes {
    private ServerFixes() {}

    static final Guard sendGuard = new Guard("containerSendGuard");

    private static final AtomicLong skipped = new AtomicLong();

    /** True when stock GameServer.sendAddItemToContainer would send this container's item to anyone. */
    public static boolean sendable(ItemContainer c) {
        if (c == null) return false;
        // Exactly the three branches stock tests before it calls INetworkPacket.sendToRelative; a square is not one of them.
        return c.getCharacter() instanceof zombie.characters.IsoPlayer
            || c.getParent() != null
            || (c.inventoryContainer != null && c.inventoryContainer.getWorldItem() != null);
    }

    /** OnEnter(skipOn = true): true skips a call stock would only drop. Any doubt lets stock run. */
    public static boolean skipSend(ItemContainer c) {
        try {
            if (sendable(c)) return false;
            skipped.incrementAndGet();
            return true;
        } catch (Throwable t) {
            sendGuard.fail(t);
            return false;
        }
    }

    public static long skipped() { return skipped.get(); }

    /** Batches below 128 items use stock's per-item pass; the one-pass merge pays off from here. */
    public static boolean batch(int size) { return size >= 128; }

    public static void resetForTest() { skipped.set(0); }

    /**
     * Same list and order as stock's per-item pass: take each new item out of the remove set, add it
     * when the list does not already hold it. One pass over the existing list instead of one contains
     * per new item.
     */
    public static void mergeProcessItems(List<InventoryItem> list, Collection<InventoryItem> add,
                                         Set<InventoryItem> remove) {
        mergeLists(list, add, remove);
    }

    /** The merge itself, over any element type; package-private so tests need no game objects. */
    static <T> void mergeLists(List<T> list, Collection<T> add, Set<T> remove) {
        Set<T> fresh = Collections.newSetFromMap(new IdentityHashMap<>());
        List<T> order = new ArrayList<>(add.size());
        for (T it : add) {
            if (it == null) continue;
            remove.remove(it);
            if (fresh.add(it)) order.add(it);
        }
        for (T it : list) fresh.remove(it);
        for (T it : order) if (fresh.remove(it)) list.add(it);
    }

    // ---- first-tick prewarm ----

    private static volatile boolean prewarmed;

    /** PoolCaps.updateServerCaps, before ServerTick.begin measures the tick. Runs once, never throws. */
    public static void prewarmOnce() {
        if (prewarmed) return;
        try {
            boolean anims = Settings.flag("serverPrewarm");
            boolean classes = Settings.flag("serverClassPrewarm");
            if (!anims && !classes) return;
            prewarmed = true;
            if (anims) prewarmAnimations();
            if (classes) prewarmClasses();
        } catch (Throwable t) {
            System.out.println("[PZButter] server prewarm failed: " + t);
        }
    }

    static void resetPrewarmForTest() { prewarmed = false; }

    /** Animset names the first player, vehicle, zombie and animal of the server's life would load. */
    public static List<String> prewarmNames() {
        Set<String> names = new LinkedHashSet<>();
        Collections.addAll(names, "player", "player-vehicle", "zombie", "zombie-crawler");
        try {
            ArrayList<AnimalDefinitions> defs = AnimalDefinitions.getAnimalDefsArray();
            if (defs != null) for (AnimalDefinitions d : defs) if (d != null && d.animset != null && !d.animset.isEmpty()) names.add(d.animset);
        } catch (Throwable t) {
            System.out.println("[PZButter] serverPrewarm: no animal definitions: " + t);
        }
        return new ArrayList<>(names);
    }

    private static void prewarmAnimations() {
        long t0 = System.nanoTime();
        long heap = usedHeap();
        List<String> names = prewarmNames();
        int ok = 0;
        for (String n : names) {
            try {
                zombie.core.skinnedmodel.advancedanimation.AnimationSet.GetAnimationSet(n, true);
                zombie.characters.action.ActionGroup.getActionGroup(n);
                ok++;
            } catch (Throwable t) {
                System.out.println("[PZButter] serverPrewarm: " + n + " failed: " + t);
            }
        }
        System.out.println("[PZButter] serverPrewarm: " + ok + "/" + names.size() + " animsets in "
            + (System.nanoTime() - t0) / 1_000_000 + " ms, heap +" + Math.max(0, usedHeap() - heap) / (1024 * 1024) + " MB");
    }

    /**
     * Load every class our patches target, without static init, so ZombieBuddy's weaving happens on the
     * first tick instead of the first join. ponytail: our own holders' targets only; classes the game
     * first meets in loot code stay for a later plan.
     */
    private static void prewarmClasses() {
        long t0 = System.nanoTime();
        long heap = usedHeap();
        Set<String> names = new LinkedHashSet<>();
        for (ModCompat.Target t : ModCompat.ours()) names.add(t.className());
        int ok = 0;
        for (String n : names) {
            try {
                Class.forName(n, false, ServerFixes.class.getClassLoader());
                ok++;
            } catch (Throwable ignored) { }
        }
        System.out.println("[PZButter] serverClassPrewarm: " + ok + "/" + names.size() + " classes in "
            + (System.nanoTime() - t0) / 1_000_000 + " ms, heap +" + Math.max(0, usedHeap() - heap) / (1024 * 1024) + " MB");
    }

    private static long usedHeap() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}
