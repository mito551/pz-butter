package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Plan 9 hooks (sprite buffers and GPU): one holder per game method. Advice is inlined into the game class, so it reaches only public
 * members: each holder's advice is one call into a public static shim here, which catches its own Throwable into the feature's Guard.
 * Every shim returns at its first static read while its key is off.
 */
public final class GpuPatches {
    private GpuPatches() {}

    public static void init() {
        SmartRelease.init();
        BatchSize.init();
        UploadSizer.init();
    }

    /** True on the thread that owns the GL context: the render thread, once the first swap has set it. */
    static boolean glThread() {
        Thread r = PresentLog.renderThread;
        return r != null && Thread.currentThread() == r;
    }

    /** GLVertexBufferObject.bufferData entry: true uploads the batch into size-class storage here and skips the game's upload. */
    public static boolean onBufferData(Object self, java.nio.ByteBuffer data) {
        if (!UploadSizer.guard.on) return false;
        if (!glThread()) { UploadSizer.passed++; return false; }
        try { return UploadSizer.upload(self, data); }
        catch (Throwable t) { UploadSizer.guard.fail(t); return false; }
    }

    @Patch(className = "zombie.core.VBO.GLVertexBufferObject", methodName = "bufferData")
    public static class BufferData {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self, @Patch.Argument(0) java.nio.ByteBuffer data) { return GpuPatches.onBufferData(self, data); }
    }

    /** SmartTexture.calculate entry, before the game's monitor: the old composite goes back to the combiner's pool. */
    public static void onSmartCalculate(Object self) {
        if (!SmartRelease.guard.on || self == null) return;
        try { SmartRelease.beforeCalculate((zombie.core.textures.SmartTexture) self); }
        catch (Throwable t) { SmartRelease.guard.fail(t); }
    }

    @Patch(className = "zombie.core.textures.SmartTexture", methodName = "calculate")
    public static class SmartCalculate {
        @Patch.OnEnter
        public static void enter(@Patch.This Object self) { GpuPatches.onSmartCalculate(self); }
    }

    /**
     * VBORenderer.setFormat exit (also after its early return, when the format is unchanged): installs the wider batch buffers
     * once a batch is empty, and keeps numElements at the installed size.
     */
    public static void onVboFormat(Object self) {
        if (!BatchSize.guard.on || self == null) return;
        try { BatchSize.afterSetFormat(self); }
        catch (Throwable t) { BatchSize.guard.fail(t); }
    }

    @Patch(className = "zombie.core.opengl.VBORenderer", methodName = "setFormat")
    public static class VboFormat {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) { GpuPatches.onVboFormat(self); }
    }
}
