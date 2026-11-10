package pzbutter;

import java.io.File;
import java.util.ArrayList;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.skinnedmodel.model.ModelInstance;

/**
 * nullModelGuard (SPEC 6.6, OzFastBoot's OzNullModelGuard): ModelSlotRenderData.initModelInst calls model.isReady()
 * on every attached instance; an instance whose model is still null (a freshly spawned NPC) throws, which under
 * Viewpoint turns its 3D view off for the session. The loop gets a copy of the list without null-model instances,
 * only when there is one. Runs on the render thread.
 */
public final class ModelPatches {
    public static final Guard guard = new Guard("nullModelGuard");

    private ModelPatches() {}

    static void init() {
        try {
            guard.on = Settings.flag("nullModelGuard");
        } catch (Throwable t) {
            guard.fail(t);
        }
    }

    /**
     * The list without null-model instances, or null when nothing needs copying. Never writes the stock list.
     * Advice is inlined into the game class, so this must be public.
     */
    public static ArrayList filterNullModels(ArrayList insts) {
        ArrayList out = null;
        for (int i = 0, n = insts.size(); i < n; i++) {
            Object e = insts.get(i);
            if (e instanceof ModelInstance mi && mi.model == null) {
                if (out == null) {
                    out = new ArrayList(n);
                    for (int j = 0; j < i; j++) out.add(insts.get(j));
                }
            } else if (out != null) {
                out.add(e);
            }
        }
        return out;
    }

    @Patch(className = "zombie.core.skinnedmodel.model.ModelSlotRenderData", methodName = "initModelInst")
    public static class NullModel {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) ArrayList insts) {
            if (!guard.on) return;
            try {
                ArrayList filtered = ModelPatches.filterNullModels(insts);
                if (filtered != null) insts = filtered;   // writable binding: the stock loop reads the copy
            } catch (Throwable t) {
                guard.fail(t);
            }
        }
    }

    // animFolderMissing (SPEC 6.x): AdvancedAnimator.searchFolders walkFileTree's each mod's AnimSets and
    // actiongroups folders; a missing folder throws NoSuchFileException that the callers catch and log with a
    // stack. A folder that does not exist has no .xml files, so hand back an empty list instead of the walk.

    public static final Guard animFolderGuard = new Guard("animFolderMissing");

    static void initAnimFolder() {
        try {
            animFolderGuard.on = Settings.flag("animFolderMissing");
        } catch (Throwable t) {
            animFolderGuard.fail(t);
        }
    }

    /** True when the walk target does not exist (null or a broken path stays stock). */
    public static boolean skipFolder(java.nio.file.Path dir) {
        return dir != null && !java.nio.file.Files.exists(dir);
    }

    /** What the walk would find in a folder that is not there: nothing. */
    public static java.util.List<String> emptyList() {
        return java.util.Collections.emptyList();
    }

    @Patch(className = "zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator", methodName = "searchFolders")
    public static class AnimFolder {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(1) java.nio.file.Path dir, @Patch.Local("r") Object r) {
            if (!animFolderGuard.on) return false;
            try {
                if (skipFolder(dir)) { r = emptyList(); return true; }
                return false;
            } catch (Throwable t) {
                animFolderGuard.fail(t);
                return false;
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) java.util.List<String> ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (java.util.List<String>) r;
        }
    }

    // ---- animFolderScope (SPEC 6.x): one AnimationSet.Load call memoizes which state rels exist per walk root;
    // root passes for rels the dirs pass never saw are skipped (the stock dirs visitor itself did the isDirectory check). ----

    public static final Guard scopeGuard = new Guard("animFolderScope");

    static void initAnimScope() {
        try {
            scopeGuard.on = Settings.flag("animFolderScope");
        } catch (Throwable t) {
            scopeGuard.fail(t);
        }
    }

    static volatile boolean animScopeWindow;
    static java.util.HashMap<String, java.util.HashSet<String>> animScopeMemo;

    /** Returned by a dirs visitor when visit must be dropped: the walk for that root and rel never happens. */
    public static final Object ANIM_SCOPE_SKIP = new Object();

    public static void animScopeLoadEnter() {
        if (scopeGuard.on) {
            animScopeWindow = true;
            animScopeMemo = new java.util.HashMap<>();
        }
    }

    public static void animScopeLoadExit() {
        animScopeWindow = false;
        animScopeMemo = null;
    }

    /**
     * Advice face for ZomboidFileSystem.walkGameAndModFilesInternal. Returns the visitor the stock walk should use:
     * the same one (stock), a recording wrapper (dirs pass), or ANIM_SCOPE_SKIP (predicted miss: walk the game's
     * code drops). Never throws.
     */
    /** Advice face for ZomboidFileSystem.walkGameAndModFilesInternal. Returns the visitor the stock walk should use:
     * the same one (stock), a recording wrapper (dirs pass), or ANIM_SCOPE_SKIP (predicted miss: walk the game's
     * code drops). Never throws.
     */
    public static Object animScopeVisit(File root, String rel, boolean recurse, Object visitor) {
        if (!animScopeWindow || visitor == null || !rel.startsWith("media/AnimSets/")) return visitor;
        try {
            if (!recurse) {
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                animScopeMemo.put(root.getAbsolutePath(), seen);
                final zombie.ZomboidFileSystem.IWalkFilesVisitor stock = (zombie.ZomboidFileSystem.IWalkFilesVisitor) visitor;
                // Stock's own concat (recipe "\1/\1") is rel + "/" + childName: record exactly the rel form the
                // per-state file passes are called with.
                return new zombie.ZomboidFileSystem.IWalkFilesVisitor() {
                    @Override
                    public void visit(java.io.File f, String r) {
                        seen.add(r + "/" + f.getName());
                        stock.visit(f, r);
                    }
                };
            }
            java.util.HashSet<String> seen = animScopeMemo.get(root.getAbsolutePath());
            if (seen != null && !seen.contains(rel)) return ANIM_SCOPE_SKIP;
            return visitor;
        } catch (Throwable t) {
            scopeGuard.fail(t);
            return visitor;
        }
    }

    @Patch(className = "zombie.ZomboidFileSystem", methodName = "walkGameAndModFilesInternal")
    public static class AnimScope {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(final @Patch.Argument(0) File root, @Patch.Argument(1) String rel,
                @Patch.Argument(2) boolean recurse, @Patch.Argument(3) zombie.ZomboidFileSystem.IWalkFilesVisitor visitor) {
            if (!scopeGuard.on) return false;
            try {
                Object w = ModelPatches.animScopeVisit(root, rel, recurse, visitor);
                if (w == visitor) return false;
                if (w == ModelPatches.ANIM_SCOPE_SKIP) return true;
                visitor = (zombie.ZomboidFileSystem.IWalkFilesVisitor) w; // writable arg: the dirs pass now records rels
                return false;
            } catch (Throwable t) {
                scopeGuard.fail(t);
                return false;
            }
        }
    }

    // ---- skinnedUniformSkip: the skinned Shader setters skip an upload whose value has not changed
    // (a per-shader last-value table; the values are compared exactly). The direct uniforms (alpha,
    // tint, hue, lighting, rust) and the ShaderProgram-string setters (targetDepth, depth bias, scale,
    // ambient) all ride the same slots. ----

    public static final Guard suGuard = new Guard("skinnedUniformSkip");

    static void initSkinnedUniform() {
        try {
            suGuard.on = Settings.flag("skinnedUniformSkip");
            if (!suGuard.on) suLast.clear();
        } catch (Throwable t) {
            suGuard.fail(t);
        }
    }

    static final java.util.IdentityHashMap<Object, float[]> suLast = new java.util.IdentityHashMap<>();

    static final int SU_SLOTS = 7;      // alpha, scale, depthBias, targetDepth, hue, lightingAmount, rust (1f)
    static final int SU_AMBIENT = 7;    // 3f slots: base index
    static final int SU_TINT = 10;

    static float[] suLastOf(Object s) {
        float[] l = suLast.get(s);
        if (l == null) {
            l = new float[13];
            java.util.Arrays.fill(l, Float.NaN);
            suLast.put(s, l);
        }
        return l;
    }

    public static boolean suSkip1(Object s, int slot, float v) {
        if (!suGuard.on) return false;
        try {
            float[] l = suLastOf(s);
            if (l[slot] == v) return true;
            l[slot] = v;
            return false;
        } catch (Throwable t) {
            suGuard.fail(t);
            return false;
        }
    }

    public static boolean suSkip3(Object s, int slot, float a, float b, float c) {
        if (!suGuard.on) return false;
        try {
            float[] l = suLastOf(s);
            if (l[slot] == a && l[slot + 1] == b && l[slot + 2] == c) return true;
            l[slot] = a;
            l[slot + 1] = b;
            l[slot + 2] = c;
            return false;
        } catch (Throwable t) {
            suGuard.fail(t);
            return false;
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setAlpha")
    public static class SuAlpha {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float v) {
            return suSkip1(s, 0, v);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setScale")
    public static class SuScale {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float v) {
            return suSkip1(s, 1, v);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setDepthBias")
    public static class SuDepthBias {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float v) {
            return suSkip1(s, 2, v);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setTargetDepth")
    public static class SuTargetDepth {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float v) {
            return suSkip1(s, 3, v);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setHueShift")
    public static class SuHue {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float v) {
            return suSkip1(s, 4, v);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setLightingAmount")
    public static class SuLightAmt {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float v) {
            return suSkip1(s, 5, v);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setTextureRustA")
    public static class SuRust {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float v) {
            return suSkip1(s, 6, v);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setAmbient", strictMatch = true)
    public static class SuAmbient {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float a, @Patch.Argument(1) float b, @Patch.Argument(2) float c) {
            return suSkip3(s, SU_AMBIENT, a, b, c);
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.shader.Shader", methodName = "setTint", strictMatch = true)
    public static class SuTint {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object s, @Patch.Argument(0) float a, @Patch.Argument(1) float b, @Patch.Argument(2) float c) {
            return suSkip3(s, SU_TINT, a, b, c);
        }
    }

    // ---- characterMaskLookup: count/fromInt answer from a seeded table ----

    public static final Guard maskGuard = new Guard("characterMaskLookup");

    static void initMask() {
        try {
            maskGuard.on = Settings.flag("characterMaskLookup");
        } catch (Throwable t) {
            maskGuard.fail(t);
        }
    }

    /** The enum's constants, read once. Stock values() clones the backing array on every call. */
    public static volatile zombie.core.skinnedmodel.model.CharacterMask.Part[] PARTS;

    public static zombie.core.skinnedmodel.model.CharacterMask.Part[] partTable() {
        zombie.core.skinnedmodel.model.CharacterMask.Part[] t = PARTS;
        if (t == null) {
            t = zombie.core.skinnedmodel.model.CharacterMask.Part.values();
            PARTS = t;
        }
        return t;
    }

    public static int count() {
        return partTable().length;
    }

    public static zombie.core.skinnedmodel.model.CharacterMask.Part fromInt(int i) {
        zombie.core.skinnedmodel.model.CharacterMask.Part[] t = partTable();
        return i >= 0 && i < t.length ? t[i] : null;
    }

    @Patch(className = "zombie.core.skinnedmodel.model.CharacterMask$Part", methodName = "count")
    public static class MaskCount {
        @Patch.OnEnter(skipOn = true)
        public static void enter(@Patch.Local("r") Object r) {
            if (!maskGuard.on) return;
            try {
                r = Integer.valueOf(count());
            } catch (Throwable t) {
                maskGuard.fail(t);
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (Integer) r;
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.model.CharacterMask$Part", methodName = "fromInt")
    public static class MaskFromInt {
        @Patch.OnEnter(skipOn = true)
        public static void enter(@Patch.Argument(0) int i, @Patch.Local("r") Object r) {
            if (!maskGuard.on) return;
            try {
                zombie.core.skinnedmodel.model.CharacterMask.Part p = fromInt(i);
                if (p != null) r = p;
            } catch (Throwable t) {
                maskGuard.fail(t);
            }
        }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) zombie.core.skinnedmodel.model.CharacterMask.Part ret, @Patch.Local("r") Object r) {
            if (r != null) ret = (zombie.core.skinnedmodel.model.CharacterMask.Part) r;
        }
    }
}