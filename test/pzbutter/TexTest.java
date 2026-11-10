package pzbutter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
        characterTexturePaths();
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

    // ---- characterTexturePaths: texture path strings built once per mask part ----

    static void characterTexturePaths() throws Exception {
        // cache answers once per (mask array identity, part index)
        String[] a1 = {"m1", "m2"};
        String[] a2 = {"m1", "m2"};
        String s1 = CharacterTexPatches.bloodMask(a1, 0);
        check("blood mask value", "media/textures/BloodTextures/m1.png".equals(s1));
        check("blood mask reused", s1 == CharacterTexPatches.bloodMask(a1, 0));
        check("blood mask per array", s1.equals(CharacterTexPatches.bloodMask(a2, 0)));
        String p1 = CharacterTexPatches.patchMask(a1, 0);
        check("patch mask value", "media/textures/patches/m1.png".equals(p1));
        check("patch mask reused", p1 == CharacterTexPatches.patchMask(a1, 0));

        // the re-implementations pass the cached strings to exactly the stock tails
        zombie.characterTextures.CharacterSmartTexture cs = new zombie.characterTextures.CharacterSmartTexture();
        zombie.characterTextures.ItemSmartTexture it =
            (zombie.characterTextures.ItemSmartTexture) unsafe().allocateInstance(zombie.characterTextures.ItemSmartTexture.class);
        zombie.characterTextures.BloodBodyPartType part = zombie.characterTextures.BloodBodyPartType.Head;
        int idx = part.index();
        List<Object[]> rec = new ArrayList<>();
        CharacterTexPatches.Tails stock = CharacterTexPatches.TAIL;
        CharacterTexPatches.Tails memory = new CharacterTexPatches.Tails() {
            public void overlay(zombie.core.textures.SmartTexture t, String file, String mask, float i, int cat) {
                rec.add(new Object[] {"overlay", t, file, mask, i, cat});
            }
            public void dirtOverlay(zombie.core.textures.SmartTexture t, String file, String mask, float i, int cat) {
                rec.add(new Object[] {"dirtOverlay", t, file, mask, i, cat});
            }
            public float addBlood(zombie.characterTextures.ItemSmartTexture t, String texName, String mask, float i, int cat) {
                rec.add(new Object[] {"addBlood", t, texName, mask, i, cat});
                return 3.25f;
            }
            public float addDirt(zombie.characterTextures.ItemSmartTexture t, String texName, String mask, float i, int cat) {
                rec.add(new Object[] {"addDirt", t, texName, mask, i, cat});
                return 1.5f;
            }
            public void overlayPatches(zombie.characterTextures.ItemSmartTexture t, String patch, String maskPng, int cat) {
                rec.add(new Object[] {"overlayPatches", t, patch, maskPng, cat});
            }
        };
        CharacterTexPatches.TAIL = memory;
        try {
            check("return delegated", CharacterTexPatches.applyAddBlood(it, "t.png", part, 0.5f) == 3.25f
                && CharacterTexPatches.applyAddDirt(it, "t.png", part, 0.25f) == 1.5f);
            CharacterTexPatches.applySetBlood(cs, part, 0.5f);
            CharacterTexPatches.applySetDirt(cs, part, 0.5f);
            CharacterTexPatches.applySetBasicPatches(it, part);
            CharacterTexPatches.applySetDenimPatches(it, part);
        } finally {
            CharacterTexPatches.TAIL = stock;
        }
        String bloodMask = CharacterTexPatches.bloodMask(zombie.characterTextures.CharacterSmartTexture.MaskFiles, idx);
        int n = 0;
        check("addBlood tail", rec.get(n).length == 6 && "addBlood".equals(rec.get(n)[0])
            && rec.get(n)[1] == it && "t.png".equals(rec.get(n)[2]) && bloodMask.equals(rec.get(n)[3])
            && (Float) rec.get(n)[4] == 0.5f && (Integer) rec.get(n)[5] == 300 + idx);
        n++;
        check("addDirt tail", "addDirt".equals(rec.get(n)[0]) && (Float) rec.get(n)[4] == 0.25f
            && (Integer) rec.get(n)[5] == 400 + idx && bloodMask.equals(rec.get(n)[3]));
        n++;
        String grimeMask = CharacterTexPatches.bloodMask(zombie.characterTextures.CharacterSmartTexture.MaskFiles, idx);
        check("setBlood tail", "dirtOverlay".equals(rec.get(n)[0]) == false && "overlay".equals(rec.get(n)[0])
            && rec.get(n)[1] == cs && "media/textures/BloodTextures/BloodOverlay.png".equals(rec.get(n)[2])
            && grimeMask.equals(rec.get(n)[3]) && (Float) rec.get(n)[4] == 0.5f && (Integer) rec.get(n)[5] == 300 + idx);
        n++;
        check("setDirt tail", "dirtOverlay".equals(rec.get(n)[0])
            && "media/textures/BloodTextures/GrimeOverlay.png".equals(rec.get(n)[2])
            && (Integer) rec.get(n)[5] == 400 + idx);
        n++;
        String basic = zombie.characterTextures.CharacterSmartTexture.BasicPatchesMaskFiles[idx];
        int expectPatches = zombie.util.StringUtils.isNullOrEmpty(basic) ? 0 : 1;
        if (expectPatches == 1) {
            check("setBasicPatches tail", "overlayPatches".equals(rec.get(n)[0])
                && ("media/textures/patches/" + basic + ".png").equals(rec.get(n)[2])
                && "media/textures/patches/patchesmask.png".equals(rec.get(n)[3])
                && (Integer) rec.get(n)[4] == 300 + idx);
            n++;
        }
        String denim = zombie.characterTextures.CharacterSmartTexture.DenimPatchesMaskFiles[idx];
        if (!zombie.util.StringUtils.isNullOrEmpty(denim)) {
            check("setDenimPatches tail", "overlayPatches".equals(rec.get(n)[0])
                && ("media/textures/patches/" + denim + ".png").equals(rec.get(n)[2])
                && "media/textures/patches/patchesmask.png".equals(rec.get(n)[3])
                && (Integer) rec.get(n)[4] == 300 + idx);
            n++;
        }
        check("no extra calls", rec.size() == n);

        // the intensity path updates the existing command's intensity param and its dirty flag
        java.lang.reflect.Field cmF = findField(zombie.core.textures.SmartTexture.class, "categoryMap");
        cmF.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.HashMap<Integer, java.util.ArrayList<Integer>> cm =
            (java.util.HashMap<Integer, java.util.ArrayList<Integer>>) cmF.get(cs);
        java.util.ArrayList<Integer> idxs = new java.util.ArrayList<>();
        idxs.add(0);
        cm.put(300 + idx, idxs);
        zombie.core.textures.TextureCombinerCommand cmd = zombie.core.textures.TextureCombinerCommand.get();
        cmd.shaderParams = new java.util.ArrayList<>();
        cmd.shaderParams.add(new zombie.core.textures.TextureCombinerShaderParam("intensity", 0.25f, 0.25f));
        cs.commands.add(cmd);
        java.lang.reflect.Field df = findField(zombie.core.textures.SmartTexture.class, "dirty");
        df.setAccessible(true);
        CharacterTexPatches.applySetBlood(cs, part, 0.9f);
        zombie.core.textures.TextureCombinerShaderParam sp =
            (zombie.core.textures.TextureCombinerShaderParam) cmd.shaderParams.get(0);
        check("intensity param updated", sp.min == 0.9f && sp.max == 0.9f);
        check("intensity dirty set", df.getBoolean(cs));
        df.setBoolean(cs, false);
        CharacterTexPatches.applySetBlood(cs, part, 0.9f);
        check("equal intensity left alone", sp.min == 0.9f && sp.max == 0.9f && !df.getBoolean(cs));

        // guard wiring: default template leaves it off, the key turns it on
        Settings.load(SelfTest.write("enabled=true\ncharacterTexturePaths=true\n"));
        CharacterTexPatches.init();
        check("guard on", CharacterTexPatches.guard.on);
        Settings.load(SelfTest.write("enabled=true\n"));
        CharacterTexPatches.init();
        check("guard default off", !CharacterTexPatches.guard.on);
    }
}
