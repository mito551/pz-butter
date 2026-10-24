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
        PersistentVbo.init();
        NoSync.init();
        GpuTimer.init();
        GpuStats.init();
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

    /** SpriteRenderer.postRender entry, render thread: the GPU timer's frame start (bakeBudgetGpu). */
    public static void onRenderFrameStart() {
        if (!GpuTimer.guard.on || !glThread()) return;
        try { GpuTimer.frameBegin(); }
        catch (Throwable t) { GpuTimer.guard.fail(t); }
    }

    /** Display.swapBuffers entry, render thread (after DeliveryPatches' own thread test). */
    public static void onSwapBegin() {
        if (GpuTimer.guard.on && glThread()) {
            try { GpuTimer.frameEnd(); }
            catch (Throwable t) { GpuTimer.guard.fail(t); }
        }
        if (NoSync.guard.on) {
            try { NoSync.topUp(); }
            catch (Throwable t) { NoSync.guard.fail(t); }
        }
    }

    /** Display.swapBuffers exit, render thread: the frame's fence and the poll for the signalled ones. */
    public static void onSwapDone() {
        if (PersistentVbo.anyAttached) {
            try { PersistentVbo.onSwap(); }
            catch (Throwable t) { PersistentVbo.guard.fail(t); }
        }
        if (GpuStats.guard.on) {
            try { GpuStats.tick(); }
            catch (Throwable t) { GpuStats.guard.fail(t); }
        }
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

    // ---- bakeNoSync, bakeNoSyncCheck ----
    // Each enter that skips the driver call leaves its answer in NoSync.pendingKind / pendingInt for its own exit (same thread, nothing
    // runs between them); an exit takes only the kind it expects and only on the GL thread, so no other thread's call can pick one up.

    private static boolean pend(int kind, int answer) {
        NoSync.pendingInt = answer;
        NoSync.pendingKind = kind;
        return true;
    }

    private static boolean pending(int kind) {
        if (NoSync.pendingKind != kind || !glThread()) return false;
        NoSync.pendingKind = NoSync.K_NONE;
        return true;
    }

    private static void mismatch(String what, int record, int driver) {
        if (NoSync.checker.log())
            System.out.println("[PZButter] bakeNoSync: the " + what + " record said " + record + ", the driver " + driver);
    }

    /** GL11.glGenTextures()I entry: true hands out a pooled name and skips the driver. */
    public static boolean onGenTexture() {
        if (!NoSync.guard.on || !glThread()) return false;
        try {
            int n = NoSync.textures.take();
            if (n <= 0) return false;
            NoSync.namesPooled++;
            return pend(NoSync.K_TEXTURE, n);
        } catch (Throwable t) { NoSync.guard.fail(t); return false; }
    }

    public static int onGenTextureExit(int ret) { return pending(NoSync.K_TEXTURE) ? NoSync.pendingInt : ret; }

    /** GLFramebufferObject30.glGenFramebuffers()I entry. */
    public static boolean onGenFramebuffer() {
        if (!NoSync.guard.on || !glThread()) return false;
        try {
            int n = NoSync.framebuffers.take();
            if (n <= 0) return false;
            NoSync.namesPooled++;
            return pend(NoSync.K_FRAMEBUFFER, n);
        } catch (Throwable t) { NoSync.guard.fail(t); return false; }
    }

    public static int onGenFramebufferExit(int ret) { return pending(NoSync.K_FRAMEBUFFER) ? NoSync.pendingInt : ret; }

    /** GLFramebufferObject30.glGenRenderbuffers()I entry. */
    public static boolean onGenRenderbuffer() {
        if (!NoSync.guard.on || !glThread()) return false;
        try {
            int n = NoSync.renderbuffers.take();
            if (n <= 0) return false;
            NoSync.namesPooled++;
            return pend(NoSync.K_RENDERBUFFER, n);
        } catch (Throwable t) { NoSync.guard.fail(t); return false; }
    }

    public static int onGenRenderbufferExit(int ret) { return pending(NoSync.K_RENDERBUFFER) ? NoSync.pendingInt : ret; }

    /** TextureFBO.initInternal entry: the attachment shape of the framebuffer being built (-1 when there is no colour texture). */
    public static void onFboInitEnter(Object color, Object depth, boolean depthRb) {
        if (!NoSync.guard.on || !glThread()) return;
        try {
            long key = -1;
            if (color instanceof zombie.interfaces.ITexture c) {
                boolean dt = depth instanceof zombie.interfaces.ITexture;
                int dw = 0, dh = 0;
                if (dt) { zombie.interfaces.ITexture d = (zombie.interfaces.ITexture) depth; dw = d.getWidthHW(); dh = d.getHeightHW(); }
                key = NoSync.ShapeMemo.key(c.getWidthHW(), c.getHeightHW(), dt, dw, dh, depthRb);
            }
            NoSync.shapes.enter(key);
        } catch (Throwable t) { NoSync.guard.fail(t); }
    }

    /** TextureFBO.initInternal exit (also when it throws). */
    public static void onFboInitExit() {
        if (!NoSync.guard.on || !glThread()) return;
        try { NoSync.shapes.exit(); }
        catch (Throwable t) { NoSync.guard.fail(t); }
    }

    /** GLFramebufferObject30.glCheckFramebufferStatus entry: inside initInternal, a shape the driver has called complete is complete. */
    public static boolean onFboStatus() {
        if (!NoSync.guard.on || !glThread()) return false;
        try {
            long k = NoSync.shapes.current;
            if (k < 0 || !NoSync.shapes.complete(k)) return false;
            NoSync.fboAnswered++;
            return pend(NoSync.K_STATUS, NoSync.FRAMEBUFFER_COMPLETE);
        } catch (Throwable t) { NoSync.guard.fail(t); return false; }
    }

    public static int onFboStatusExit(int ret) {
        if (NoSync.pendingKind != NoSync.K_STATUS && !NoSync.guard.on) return ret;
        if (!glThread()) return ret;
        try {
            if (pending(NoSync.K_STATUS)) {
                int a = NoSync.pendingInt;
                if (NoSync.check && NoSync.checker.due()) {
                    int d = NoSync.driverStatusForTest.getAsInt();
                    if (d != a) {
                        mismatch("framebuffer shape", a, d);
                        NoSync.shapes.forget(NoSync.shapes.current);
                        return d;
                    }
                }
                return a;
            }
            NoSync.asked++;
            NoSync.shapes.remember(NoSync.shapes.current, ret);
        } catch (Throwable t) { NoSync.guard.fail(t); }
        return ret;
    }

    /** GL20.glGetUniformLocation(int, CharSequence) entry: a location remembered for this program and name. */
    public static boolean onUniform(int program, CharSequence name) {
        if (!NoSync.guard.on || !glThread()) return false;
        try {
            int loc = NoSync.uniforms.get(program, name);
            if (loc == NoSync.UniformCache.MISS) return false;
            NoSync.uniformsAnswered++;
            return pend(NoSync.K_UNIFORM, loc);
        } catch (Throwable t) { NoSync.guard.fail(t); return false; }
    }

    public static int onUniformExit(int program, CharSequence name, int ret) {
        if (NoSync.pendingKind != NoSync.K_UNIFORM && !NoSync.guard.on) return ret;
        if (!glThread()) return ret;
        try {
            if (pending(NoSync.K_UNIFORM)) {
                int a = NoSync.pendingInt;
                if (NoSync.check && NoSync.checker.due()) {
                    int d = NoSync.driverUniformForTest.applyAsInt(program, name);
                    if (d != a) {
                        mismatch("uniform location", a, d);
                        NoSync.uniforms.put(program, name, d);
                        return d;
                    }
                }
                return a;
            }
            NoSync.asked++;
            NoSync.uniforms.put(program, name, ret);
        } catch (Throwable t) { NoSync.guard.fail(t); }
        return ret;
    }

    /** GL20.glLinkProgram / glDeleteProgram exit: the program's remembered locations are no longer valid. */
    public static void onProgramGone(int program) {
        if (!NoSync.guard.on || !glThread()) return;
        try { NoSync.uniforms.drop(program); }
        catch (Throwable t) { NoSync.guard.fail(t); }
    }

    /** GL20.glUseProgram exit. */
    public static void onUseProgram(int program) {
        if (!NoSync.guard.on || !glThread()) return;
        NoSync.programs.used(program);
    }

    /** GL11.glGetInteger entry: GL_CURRENT_PROGRAM from the record when it is known. */
    public static boolean onGetInteger(int pname) {
        if (!NoSync.guard.on || !glThread()) return false;
        int a = NoSync.programs.answer(pname);
        if (a == NoSync.UniformCache.MISS) return false;
        NoSync.programAnswered++;
        return pend(NoSync.K_INTEGER, a);
    }

    public static int onGetIntegerExit(int pname, int ret) {
        if (NoSync.pendingKind != NoSync.K_INTEGER) return ret;
        if (!pending(NoSync.K_INTEGER)) return ret;
        int a = NoSync.pendingInt;
        if (NoSync.check && NoSync.checker.due()) {
            try {
                int d = NoSync.driverIntegerForTest.applyAsInt(pname);
                if (d != a) {
                    mismatch("current shader", a, d);
                    NoSync.programs.used(d);
                    return d;
                }
            } catch (Throwable t) { NoSync.guard.fail(t); }
        }
        return a;
    }

    @Patch(className = "org.lwjgl.opengl.GL11", methodName = "glGenTextures", strictMatch = true)
    public static class GenTextures {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return GpuPatches.onGenTexture(); }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) { ret = GpuPatches.onGenTextureExit(ret); }
    }

    @Patch(className = "zombie.core.textures.GLFramebufferObject30", methodName = "glGenFramebuffers")
    public static class GenFramebuffers {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return GpuPatches.onGenFramebuffer(); }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) { ret = GpuPatches.onGenFramebufferExit(ret); }
    }

    @Patch(className = "zombie.core.textures.GLFramebufferObject30", methodName = "glGenRenderbuffers")
    public static class GenRenderbuffers {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return GpuPatches.onGenRenderbuffer(); }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) { ret = GpuPatches.onGenRenderbufferExit(ret); }
    }

    /** Private (ITexture colour, ITexture depth, boolean depthRenderbuffer)V. */
    @Patch(className = "zombie.core.textures.TextureFBO", methodName = "initInternal")
    public static class FboInit {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object color, @Patch.Argument(1) Object depth, @Patch.Argument(2) boolean depthRb) {
            GpuPatches.onFboInitEnter(color, depth, depthRb);
        }

        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit() { GpuPatches.onFboInitExit(); }
    }

    @Patch(className = "zombie.core.textures.GLFramebufferObject30", methodName = "glCheckFramebufferStatus")
    public static class FboStatus {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter() { return GpuPatches.onFboStatus(); }

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int ret) { ret = GpuPatches.onFboStatusExit(ret); }
    }

    /** The (int, CharSequence) overload only: the (int, ByteBuffer) one is not assignable to the bound CharSequence. */
    @Patch(className = "org.lwjgl.opengl.GL20", methodName = "glGetUniformLocation")
    public static class UniformLocation {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int program, @Patch.Argument(1) CharSequence name) { return GpuPatches.onUniform(program, name); }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int program, @Patch.Argument(1) CharSequence name, @Patch.Return(readOnly = false) int ret) {
            ret = GpuPatches.onUniformExit(program, name, ret);
        }
    }

    @Patch(className = "org.lwjgl.opengl.GL20", methodName = "glLinkProgram")
    public static class LinkProgram {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int program) { GpuPatches.onProgramGone(program); }
    }

    @Patch(className = "org.lwjgl.opengl.GL20", methodName = "glDeleteProgram")
    public static class DeleteProgram {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int program) { GpuPatches.onProgramGone(program); }
    }

    @Patch(className = "org.lwjgl.opengl.GL20", methodName = "glUseProgram")
    public static class UseProgram {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int program) { GpuPatches.onUseProgram(program); }
    }

    @Patch(className = "org.lwjgl.opengl.GL11", methodName = "glGetInteger")
    public static class GetInteger {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) int pname) { return GpuPatches.onGetInteger(pname); }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int pname, @Patch.Return(readOnly = false) int ret) { ret = GpuPatches.onGetIntegerExit(pname, ret); }
    }
}
