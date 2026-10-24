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
        QuadWriter.init();
        PersistentVbo.init();
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

    /** VBORenderer.addQuad (24 floats) entry: true writes the four vertices here and skips the game's body. */
    public static boolean onAddQuad(Object self, float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3, float r, float g, float b, float a) {
        if (!QuadWriter.guard.on || self == null) return false;
        try {
            float[] q = QuadWriter.pack;
            q[0] = x0; q[1] = y0; q[2] = z0; q[3] = u0; q[4] = v0; q[5] = x1; q[6] = y1; q[7] = z1; q[8] = u1; q[9] = v1;
            q[10] = x2; q[11] = y2; q[12] = z2; q[13] = u2; q[14] = v2; q[15] = x3; q[16] = y3; q[17] = z3; q[18] = u3; q[19] = v3;
            q[20] = r; q[21] = g; q[22] = b; q[23] = a;
            return QuadWriter.tryQuad(self, q);
        } catch (Throwable t) { QuadWriter.guard.fail(t); return false; }
    }

    @Patch(className = "zombie.core.opengl.VBORenderer", methodName = "addQuad")
    public static class VboQuad {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self,
                @Patch.Argument(0) float a0, @Patch.Argument(1) float a1, @Patch.Argument(2) float a2, @Patch.Argument(3) float a3,
                @Patch.Argument(4) float a4, @Patch.Argument(5) float a5, @Patch.Argument(6) float a6, @Patch.Argument(7) float a7,
                @Patch.Argument(8) float a8, @Patch.Argument(9) float a9, @Patch.Argument(10) float a10, @Patch.Argument(11) float a11,
                @Patch.Argument(12) float a12, @Patch.Argument(13) float a13, @Patch.Argument(14) float a14, @Patch.Argument(15) float a15,
                @Patch.Argument(16) float a16, @Patch.Argument(17) float a17, @Patch.Argument(18) float a18, @Patch.Argument(19) float a19,
                @Patch.Argument(20) float a20, @Patch.Argument(21) float a21, @Patch.Argument(22) float a22, @Patch.Argument(23) float a23) {
            return GpuPatches.onAddQuad(self, a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20, a21, a22, a23);
        }
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

    // ---- persistentVbo ----

    /**
     * GLVertexBufferObject.map() entry: true serves the batch from a persistently mapped storage (the buffer's id and binding are set
     * here and the buffer waits in {@code pending} for the exit advice) and skips the game's body. Any failure sends this buffer back to the
     * game's own storage first (a persistent name must never meet the game's map()), then lets the game's body run.
     */
    public static boolean onMapEnter(Object self) {
        if (!PersistentVbo.guard.on && !PersistentVbo.anyAttached) return false;
        if (!glThread()) return false;
        try {
            java.nio.ByteBuffer b = PersistentVbo.map(self);
            if (b == null) return false;
            PersistentVbo.pending = b;
            return true;
        } catch (Throwable t) {
            PersistentVbo.guard.fail(t);
            PersistentVbo.failover(self);
            return false;
        }
    }

    /** GLVertexBufferObject.map() exit: the persistent buffer when the enter served one, else the game's answer. */
    public static java.nio.ByteBuffer onMapExit(java.nio.ByteBuffer ret) {
        java.nio.ByteBuffer p = PersistentVbo.pending;
        if (p == null || !glThread()) return ret;
        PersistentVbo.pending = null;
        return p;
    }

    /** GLVertexBufferObject.unmap() entry: true for a buffer served by map() above (persistent mappings stay mapped). */
    public static boolean onUnmapEnter(Object self) {
        if (!PersistentVbo.anyAttached || !glThread()) return false;
        try {
            if (!PersistentVbo.unmap(self)) return false;
            PersistentVbo.unmapSkipped = true;
            return true;
        } catch (Throwable t) { PersistentVbo.guard.fail(t); return false; }
    }

    /** GLVertexBufferObject.unmap() exit: true (the game's answer for a buffer that is not mapped) when the enter skipped the body. */
    public static boolean onUnmapExit(boolean ret) {
        if (!PersistentVbo.unmapSkipped || !glThread()) return ret;
        PersistentVbo.unmapSkipped = false;
        return true;
    }

    /** SpriteRenderer$RingBuffer.render() exit, per-batch fence mode only. */
    public static void onRingRendered(Object ring) {
        if (!PersistentVbo.anyAttached || PersistentVbo.frameSync || ring == null || !glThread()) return;
        try { PersistentVbo.afterRingRender(ring); }
        catch (Throwable t) { PersistentVbo.guard.fail(t); PersistentVbo.batchFailed(); }
    }

    /** Display.swapBuffers entry, render thread (after DeliveryPatches' own thread test). */
    public static void onSwapBegin() { }

    /** Display.swapBuffers exit, render thread: the frame's fence and the poll for the signalled ones. */
    public static void onSwapDone() {
        if (!PersistentVbo.anyAttached) return;
        try { PersistentVbo.onSwap(); }
        catch (Throwable t) { PersistentVbo.guard.fail(t); }
    }

    /** map() with no argument only (strictMatch with no @Argument, research Surprise 4): map(int) has no caller and stays the game's. */
    @Patch(className = "zombie.core.VBO.GLVertexBufferObject", methodName = "map", strictMatch = true)
    public static class VboMap {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) { return GpuPatches.onMapEnter(self); }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) java.nio.ByteBuffer ret) { ret = GpuPatches.onMapExit(ret); }
    }

    @Patch(className = "zombie.core.VBO.GLVertexBufferObject", methodName = "unmap")
    public static class VboUnmap {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object self) { return GpuPatches.onUnmapEnter(self); }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean ret) { ret = GpuPatches.onUnmapExit(ret); }
    }

    @Patch(className = "zombie.core.SpriteRenderer$RingBuffer", methodName = "render")
    public static class RingRender {
        @Patch.OnExit
        public static void exit(@Patch.This Object self) { GpuPatches.onRingRendered(self); }
    }
}
