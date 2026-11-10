package pzbutter;

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
}
