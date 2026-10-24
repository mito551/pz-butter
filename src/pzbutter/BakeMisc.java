package pzbutter;

import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import zombie.debug.DebugOptions;
import zombie.iso.fboRenderChunk.FBORenderChunk;

/**
 * Plan 6 small keys (Task 3): bloodFadeFix and bakeMipLevels. The holders in ChunkPatches call these; the pure cores
 * are tested in ChunkTest.
 */
final class BakeMisc {
    private BakeMisc() {}

    static int mipLevels;

    static void init() {
        ChunkPatches.bloodFadeGuard.on = Settings.flag("bloodFadeFix");
        mipLevels = Settings.num("bakeMipLevels", 0, 0, 12);
        ChunkPatches.mipGuard.on = Settings.enabled && mipLevels > 0;
    }

    /** Pushed-out splats still fading are dropped before stock buckets them. */
    static void dropFading(List<?> fade) {
        fade.clear();
    }

    /** The stock mip build runs when the MipMaps debug option is on and the texture is not high-res. */
    static boolean mipApplies(int levels, boolean mipMapsOption, boolean highRes) {
        return levels > 0 && mipMapsOption && !highRes;
    }

    static int maxLevel(int levels) {
        return levels - 1;
    }

    /** Render thread, before stock's glGenerateMipmap: the texture's chain stops at mipLevels - 1. */
    static void mipBeforeGenerate(FBORenderChunk rc) {
        if (mipLevels <= 0 || rc.tex == null) return;
        if (!mipApplies(mipLevels, DebugOptions.instance.fboRenderChunk.mipMaps.getValue(), rc.highRes)) return;
        rc.tex.bind();
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, maxLevel(mipLevels));
    }
}
