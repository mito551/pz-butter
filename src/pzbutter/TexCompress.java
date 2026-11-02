package pzbutter;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Pool-side BC3 (DXT5) encode for texture compression off the render thread (Plan 11e). */
public final class TexCompress {
    private TexCompress() {}

    public static final Guard guard = new Guard("texCompress");
    /** Set by Task 3 once the GL extension is known; default false keeps this task inert in game. */
    public static volatile boolean s3tc = false;

    public static final class Level {
        public final ByteBuffer rgba;
        public final int w;
        public final int h;
        public final ByteBuffer bc3;
        public Level(ByteBuffer rgba, int w, int h, ByteBuffer bc3) {
            this.rgba = rgba; this.w = w; this.h = h; this.bc3 = bc3;
        }
    }

    /** Synchronized identity map: ImageData instance -> its encoded per-level BC3 blocks. */
    public static final Map<Object, Level[]> encoded =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public static final java.util.concurrent.atomic.AtomicInteger encodedCount = new java.util.concurrent.atomic.AtomicInteger(),
        droppedCount = new java.util.concurrent.atomic.AtomicInteger(), usedCount = new java.util.concurrent.atomic.AtomicInteger();
    private static java.lang.invoke.VarHandle flagsH;   // FileTask_LoadPackImage.flags is package-private
    private static volatile boolean flagsBound;

    static void preInit() { init(); }
    public static void init() {
        try { guard.on = Settings.enabled && Settings.flag("texCompress"); }
        catch (Throwable t) { guard.on = false; }
    }

    /** Policy + state: is a pack texture (flags bit 4) to be encoded? */
    public static boolean wouldCompressPack(int flags) {
        if (!guard.on || !s3tc) return false;
        try { if (!zombie.core.textures.TextureID.useCompressionOption) return false; } catch (Throwable t) { return false; }
        return (flags & 4) != 0;
    }

    /** Policy + state: is a plain ImageData (null assetParams) to be encoded? */
    public static boolean wouldCompressImage() {
        if (!guard.on || !s3tc) return false;
        try { return zombie.core.textures.TextureID.useCompressionOption; } catch (Throwable t) { return false; }
    }

    /** OnExit of FileTask_LoadPackImage.call(): task carries pack flags; ret is the ImageData. */
    public static void onPackImageExit(Object task, Object ret) {
        if (!wouldCompressPack(reflectFlags(task))) return;
        encode(ret);
    }

    /** OnExit of FileTask_LoadImageData.call(): ret is the ImageData; null-assetParams rule. */
    public static void onImageExit(Object ret) {
        if (!wouldCompressImage()) return;
        encode(ret);
    }

    private static int reflectFlags(Object task) {
        if (task == null) return 0;
        if (!flagsBound) { flagsH = Game.field(zombie.asset.FileTask_LoadPackImage.class, "flags", int.class); flagsBound = true; }
        if (flagsH == null || !(task instanceof zombie.asset.FileTask_LoadPackImage)) return 0;
        return (int) flagsH.get(task);
    }

    /** Forces stock's mip generation and premultiply (getMipMapData caches both), then encodes every level. Pool thread. */
    private static void encode(Object o) {
        if (!(o instanceof zombie.core.textures.ImageData imageData)) return;
        try {
            int count = imageData.getMipMapCount();
            if (count <= 0) return;
            imageData.getMipMapData(count - 1);
            Level[] levels = new Level[count];
            byte[] src = null, dst = null;
            for (int i = 0; i < count; i++) {
                zombie.core.textures.MipMapLevel lvl = imageData.getMipMapData(i);
                int w = lvl.width, h = lvl.height, n = w * h * 4;
                ByteBuffer rgba = lvl.getBuffer();
                ByteBuffer dup = rgba.duplicate();
                dup.rewind();
                if (dup.remaining() < n) throw new IllegalStateException("level " + i + " buffer short");
                if (src == null || src.length < n) src = new byte[n];
                dup.get(src, 0, n);
                int size = Bc3.encodedSize(w, h);
                if (dst == null || dst.length < size) dst = new byte[size];
                Bc3.encodeTo(src, w, h, dst);
                ByteBuffer bc3 = ByteBuffer.allocateDirect(size).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                bc3.put(dst, 0, size).flip();
                levels[i] = new Level(rgba, w, h, bc3);
            }
            encoded.put(imageData, levels);
            encodedCount.incrementAndGet();
        } catch (Throwable t) {
            guard.fail(t);
            encoded.remove(o);
        }
    }

    /** OnEnter of ImageData.dispose(): drop the entry (and its BC3 buffers). */
    public static void onDispose(Object imageData) {
        if (imageData == null) return;
        if (encoded.remove(imageData) != null) droppedCount.incrementAndGet();
    }

    /** Task 3: lookup a stored level; identity + size match only. */
    public static Level levelFor(Object imageData, int level, ByteBuffer rgba, int w, int h) {
        Level[] arr = encoded.get(imageData);
        if (arr == null || level < 0 || level >= arr.length) return null;
        Level L = arr[level];
        if (L == null) return null;
        if (L.rgba != rgba) return null;
        if (L.w != w || L.h != h) return null;
        return L;
    }

    /** Task 3: drop the entry after upload. */
    public static void used(Object imageData) {
        if (imageData == null) return;
        if (encoded.remove(imageData) != null) usedCount.incrementAndGet();
    }

    static void resetForTest() {
        guard.on = false;
        s3tc = false;
        encoded.clear();
        encodedCount.set(0);
        droppedCount.set(0);
        usedCount.set(0);
        flagsBound = false;
        flagsH = null;
    }
}