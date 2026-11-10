package pzbutter;

import java.util.HashMap;

public final class TexTest {
    private TexTest() {}

    static void check(String what, boolean ok) {
        if (!ok) throw new AssertionError(what);
    }

    public static void run() throws Exception {
        HashMap<String, Object> table = TexPatches.table();
        table.clear();
        table.put("Item_Beta", null);
        table.put("Item_BETA", null);
        table.put("media/ui/Container_box.png", null);
        table.put("unrelated/other", null);
        String last = null;
        for (String k : table.keySet())
            if (k.equals("Item_Beta") || k.equals("Item_BETA")) last = k;
        HashMap<String, String> names = new HashMap<>();
        HashMap<String, String> icons = new HashMap<>();
        check("snapshot fills", TexPatches.collectAllIconsSnapshot(names, icons));
        check("unrelated skipped", !names.containsKey("other") && !icons.containsKey("unrelated/other"));
        check("container stripped", "media/ui/Container_box.png".equals(icons.get("box")) && "box".equals(names.get("box")));
        check("collision winner by order", last != null && last.equals(icons.get("beta")));
        check("collision name value", last != null && names.get("beta").equals(last.replaceFirst("Item_", "")));
        check("table untouched", table.containsKey("Item_Beta") && table.containsKey("Item_BETA") && table.containsKey("media/ui/Container_box.png"));
        // mutators take the same lock the snapshot does: a writer thread cannot disturb the collector.
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 3000; i++) TexPatches.putSharedUnderLock("Item_B" + i, null);
        });
        writer.start();
        for (int i = 0; i < 200; i++) {
            HashMap<String, String> n = new HashMap<>();
            HashMap<String, String> ic = new HashMap<>();
            if (!TexPatches.collectAllIconsSnapshot(n, ic)) check("stress snapshot", false);
        }
        writer.join();
        check("put under lock", TexPatches.putSharedUnderLock("Item_Z_test", null) && table.containsKey("Item_Z_test"));
        check("clear under lock", TexPatches.clearSharedUnderLock() && table.isEmpty());
        table.clear();
        spriteDrawReuse();
    }

    // ---- spriteDrawReuse: releaseUnusedSprites keeps the sprite draw objects for reuse ----

    static sun.misc.Unsafe unsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    static zombie.core.sprite.SpriteRenderState bareState() throws Exception {
        return (zombie.core.sprite.SpriteRenderState) unsafe().allocateInstance(zombie.core.sprite.SpriteRenderState.class);
    }

    static java.lang.reflect.Field gsrField(String name) throws Exception {
        java.lang.reflect.Field f = findField(zombie.core.sprite.SpriteRenderState.class, name);
        if (f == null) f = findField(zombie.core.sprite.GenericSpriteRenderState.class, name);
        f.setAccessible(true);
        return f;
    }

    static java.lang.reflect.Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                // walk up
            }
        return null;
    }

    static void spriteDrawReuse() throws Exception {
        zombie.core.sprite.GenericSpriteRenderState s = bareState();
        java.lang.reflect.Field spriteF = gsrField("sprite");
        java.lang.reflect.Field liveF = gsrField("liveSprites");
        java.lang.reflect.Field numF = gsrField("numSprites");
        zombie.core.textures.TextureDraw[] arr = new zombie.core.textures.TextureDraw[4];
        arr[0] = new zombie.core.textures.TextureDraw();
        arr[1] = new zombie.core.textures.TextureDraw();
        arr[2] = new zombie.core.textures.TextureDraw();
        arr[3] = new zombie.core.textures.TextureDraw();
        zombie.core.textures.TextureDraw kept2 = arr[2];
        zombie.core.textures.TextureDraw kept3 = arr[3];
        spriteF.set(s, arr);
        numF.setInt(s, 2);
        liveF.setInt(s, 4);
        GpuPatches.spriteReuseGuard.on = true;
        check("core keeps objects", GpuPatches.spriteReuseKeep(s));
        check("live shrunk to num", liveF.getInt(s) == 2);
        check("live slots kept", arr[2] == kept2 && arr[3] == kept3);
        check("first slots kept", arr[0] != null && arr[1] != null);
        // cap: at most 8 objects survive one call, like the stock loop.
        arr = new zombie.core.textures.TextureDraw[10];
        for (int i = 0; i < 10; i++) arr[i] = new zombie.core.textures.TextureDraw();
        spriteF.set(s, arr);
        numF.setInt(s, 1);
        liveF.setInt(s, 10);
        GpuPatches.spriteReuseKeep(s);
        check("capped at 8", liveF.getInt(s) == 2);
        check("cap keeps object", arr[2] != null);
        // guard off = stock behaviour.
        boolean wasOn = GpuPatches.spriteReuseGuard.on;
        GpuPatches.spriteReuseGuard.on = false;
        numF.setInt(s, 1);
        liveF.setInt(s, 10);
        check("stock when off", !GpuPatches.spriteReuseKeep(s));
        GpuPatches.spriteReuseGuard.on = wasOn;
        check("guard present", GpuPatches.spriteReuseGuard != null);
    }
}
