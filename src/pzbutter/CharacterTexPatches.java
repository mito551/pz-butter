package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characterTextures.CharacterSmartTexture;
import zombie.characterTextures.ItemSmartTexture;
import zombie.core.textures.SmartTexture;
import zombie.core.textures.TextureCombinerCommand;
import zombie.core.textures.TextureCombinerShaderParam;

/**
 * characterTexturePaths: the six smart-texture entry points build their mask path
 * strings per call; we build each once per (mask array identity, part index) and
 * re-run the same short stock bodies with the cached string.
 */
public final class CharacterTexPatches {
    private CharacterTexPatches() {}

    public static final Guard guard = new Guard("characterTexturePaths");

    static void init() {
        try {
            guard.on = Settings.flag("characterTexturePaths");
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    // once-per-(array, part) string caches. Identity keyed: a replaced mask array
    // simply starts a new cache entry.
    private static final ConcurrentHashMap<Key, String> BLOOD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Key, String> PATCHES = new ConcurrentHashMap<>();

    private static final class Key {
        final String[] masks;
        final int idx;

        Key(String[] masks, int idx) {
            this.masks = masks;
            this.idx = idx;
        }

        public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return k.masks == masks && k.idx == idx;
        }

        public int hashCode() {
            return System.identityHashCode(masks) * 31 + idx;
        }
    }

    /** The BloodTextures mask path for a body part, built once per mask array and part. */
    public static String bloodMask(String[] masks, int idx) {
        return BLOOD.computeIfAbsent(new Key(masks, idx),
            k -> "media/textures/BloodTextures/" + k.masks[k.idx] + ".png");
    }

    /** The patches mask path for a body part, built once per mask array and part. */
    public static String patchMask(String[] masks, int idx) {
        return PATCHES.computeIfAbsent(new Key(masks, idx),
            k -> "media/textures/patches/" + k.masks[k.idx] + ".png");
    }

    /** The stock tail of each entry point, wrapped so tests can record calls instead of
     * running the game's texture/shader machinery. Production never swaps it. */
    public static Tails TAIL = new Tails();

    public static class Tails {
        public void overlay(SmartTexture t, String file, String mask, float i, int cat) { t.addOverlay(file, mask, i, cat); }
        public void dirtOverlay(SmartTexture t, String file, String mask, float i, int cat) { t.addDirtOverlay(file, mask, i, cat); }
        public float addBlood(ItemSmartTexture t, String texName, String mask, float i, int cat) { return t.addBlood(texName, mask, i, cat); }
        public float addDirt(ItemSmartTexture t, String texName, String mask, float i, int cat) { return t.addDirt(texName, mask, i, cat); }
        public void overlayPatches(ItemSmartTexture t, String patch, String maskPng, int cat) { t.addOverlayPatches(patch, maskPng, cat); }
    }

    // stock-body re-implementations, shared by advice and tests.

    public static void applySetBlood(CharacterSmartTexture t, BloodBodyPartType part, float intensity) {
        float i = Math.max(0f, Math.min(1f, intensity));
        int cat = 300 + part.index();
        TextureCombinerCommand cmd = t.getFirstFromCategory(cat);
        if (cmd != null) {
            for (Object o : cmd.shaderParams) {
                TextureCombinerShaderParam p = (TextureCombinerShaderParam) o;
                if (p.name.equals("intensity") && (p.min != i || p.max != i)) {
                    p.max = i;
                    p.min = i;
                    dirty(t);
                }
            }
        } else if (i > 0f) {
            TAIL.overlay(t, "media/textures/BloodTextures/BloodOverlay.png",
                bloodMask(CharacterSmartTexture.MaskFiles, part.index()), i, cat);
        }
    }

    public static void applySetDirt(CharacterSmartTexture t, BloodBodyPartType part, float intensity) {
        float i = Math.max(0f, Math.min(1f, intensity));
        int cat = 400 + part.index();
        TextureCombinerCommand cmd = t.getFirstFromCategory(cat);
        if (cmd != null) {
            for (Object o : cmd.shaderParams) {
                TextureCombinerShaderParam p = (TextureCombinerShaderParam) o;
                if (p.name.equals("intensity") && (p.min != i || p.max != i)) {
                    p.max = i;
                    p.min = i;
                    dirty(t);
                }
            }
        } else if (i > 0f) {
            TAIL.dirtOverlay(t, "media/textures/BloodTextures/GrimeOverlay.png",
                bloodMask(CharacterSmartTexture.MaskFiles, part.index()), i, cat);
        }
    }

    public static float applyAddBlood(ItemSmartTexture t, String texName, BloodBodyPartType part, float intensity) {
        return TAIL.addBlood(t, texName, bloodMask(CharacterSmartTexture.MaskFiles, part.index()),
            intensity, 300 + part.index());
    }

    public static float applyAddDirt(ItemSmartTexture t, String texName, BloodBodyPartType part, float intensity) {
        return TAIL.addDirt(t, texName, bloodMask(CharacterSmartTexture.MaskFiles, part.index()),
            intensity, 400 + part.index());
    }

    public static void applySetBasicPatches(ItemSmartTexture t, BloodBodyPartType part) {
        String m = CharacterSmartTexture.BasicPatchesMaskFiles[part.index()];
        if (zombie.util.StringUtils.isNullOrEmpty(m)) return;
        TAIL.overlayPatches(t, patchMask(CharacterSmartTexture.BasicPatchesMaskFiles, part.index()),
            "media/textures/patches/patchesmask.png", 300 + part.index());
    }

    public static void applySetDenimPatches(ItemSmartTexture t, BloodBodyPartType part) {
        String m = CharacterSmartTexture.DenimPatchesMaskFiles[part.index()];
        if (zombie.util.StringUtils.isNullOrEmpty(m)) return;
        TAIL.overlayPatches(t, patchMask(CharacterSmartTexture.DenimPatchesMaskFiles, part.index()),
            "media/textures/patches/patchesmask.png", 300 + part.index());
    }

    // SmartTexture.setDirty is protected; a method handle keeps the advice lint clean.
    private static volatile MethodHandle DIRTY;

    static void dirty(SmartTexture t) {
        try {
            if (DIRTY == null) {
                synchronized (CharacterTexPatches.class) {
                    if (DIRTY == null) {
                        java.lang.reflect.Method m = SmartTexture.class.getDeclaredMethod("setDirty");
                        m.setAccessible(true);
                        DIRTY = MethodHandles.lookup().unreflect(m);
                    }
                }
            }
            DIRTY.invoke(t);
        } catch (Throwable x) {
            guard.fail(x);
        }
    }

    @Patch(className = "zombie.characterTextures.CharacterSmartTexture", methodName = "setBlood")
    public static class SetBlood {
        @Patch.OnEnter(skipOn = true)
        static boolean enter(@Patch.This CharacterSmartTexture self, @Patch.Argument(0) BloodBodyPartType part,
            @Patch.Argument(1) float intensity) {
            if (!guard.on) return false;
            try {
                applySetBlood(self, part, intensity);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }
    }

    @Patch(className = "zombie.characterTextures.CharacterSmartTexture", methodName = "setDirt")
    public static class SetDirt {
        @Patch.OnEnter(skipOn = true)
        static boolean enter(@Patch.This CharacterSmartTexture self, @Patch.Argument(0) BloodBodyPartType part,
            @Patch.Argument(1) float intensity) {
            if (!guard.on) return false;
            try {
                applySetDirt(self, part, intensity);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }
    }

    @Patch(className = "zombie.characterTextures.ItemSmartTexture", methodName = "addBlood", strictMatch = true)
    public static class AddBlood {
        @Patch.OnEnter(skipOn = true)
        static boolean enter(@Patch.This ItemSmartTexture self, @Patch.Argument(0) String texName,
            @Patch.Argument(1) BloodBodyPartType part, @Patch.Argument(2) float intensity,
            @Patch.Local("r") Object r) {
            if (!guard.on) return false;
            try {
                r = applyAddBlood(self, texName, part, intensity);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        static void exit(@Patch.Return(readOnly = false) float ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (Float) r;
        }
    }

    @Patch(className = "zombie.characterTextures.ItemSmartTexture", methodName = "addDirt", strictMatch = true)
    public static class AddDirt {
        @Patch.OnEnter(skipOn = true)
        static boolean enter(@Patch.This ItemSmartTexture self, @Patch.Argument(0) String texName,
            @Patch.Argument(1) BloodBodyPartType part, @Patch.Argument(2) float intensity,
            @Patch.Local("r") Object r) {
            if (!guard.on) return false;
            try {
                r = applyAddDirt(self, texName, part, intensity);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        static void exit(@Patch.Return(readOnly = false) float ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (Float) r;
        }
    }

    @Patch(className = "zombie.characterTextures.ItemSmartTexture", methodName = "setBasicPatches")
    public static class SetBasicPatches {
        @Patch.OnEnter(skipOn = true)
        static boolean enter(@Patch.This ItemSmartTexture self, @Patch.Argument(0) BloodBodyPartType part) {
            if (!guard.on) return false;
            try {
                applySetBasicPatches(self, part);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }
    }

    @Patch(className = "zombie.characterTextures.ItemSmartTexture", methodName = "setDenimPatches")
    public static class SetDenimPatches {
        @Patch.OnEnter(skipOn = true)
        static boolean enter(@Patch.This ItemSmartTexture self, @Patch.Argument(0) BloodBodyPartType part) {
            if (!guard.on) return false;
            try {
                applySetDenimPatches(self, part);
                return true;
            } catch (Throwable t) {
                guard.fail(t);
                return false;
            }
        }
    }
}
