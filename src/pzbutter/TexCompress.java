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

    public static volatile int encodedCount;
    public static volatile int droppedCount;
    public static volatile int usedCount;

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
        try { return task.getClass().getField("flags").getInt(task); }
        catch (Throwable t) { return 0; }
    }

    private static void encode(Object imageData) {
        if (imageData == null) return;
        try {
            Class<?> c = imageData.getClass();
            int count = (Integer) c.getMethod("getMipMapCount").invoke(imageData);
            if (count <= 0) return;
            // generate mips + premultiply, then read each level
            Level[] levels = new Level[count];
            c.getMethod("getMipMapData", int.class).invoke(imageData, count - 1);
            for (int i = 0; i < count; i++) {
                Object lvl = c.getMethod("getMipMapData", int.class).invoke(imageData, i);
                Class<?> lc = lvl.getClass();
                int w = lc.getField("width").getInt(lvl);
                int h = lc.getField("height").getInt(lvl);
                java.nio.ByteBuffer rgba = (java.nio.ByteBuffer) lc.getMethod("getBuffer").invoke(lvl);
                java.nio.ByteBuffer rgbaDup = rgba.duplicate();
                int n = w * h * 4;
                byte[] src = new byte[n];
                rgbaDup.rewind();
                if (rgbaDup.remaining() < n) {
                    // skip this level: not enough data
                    throw new IllegalStateException("level buffer short");
                }
                rgbaDup.get(src);
                java.nio.ByteBuffer bc3 = Bc3.encode(java.nio.ByteBuffer.wrap(src), w, h);
                levels[i] = new Level(rgba, w, h, bc3);
            }
            encoded.put(imageData, levels);
            encodedCount++;
        } catch (Throwable t) {
            guard.fail(t);
            encoded.remove(imageData);
        }
    }

    /** OnEnter of ImageData.dispose(): drop the entry (and its BC3 buffers). */
    public static void onDispose(Object imageData) {
        if (imageData == null) return;
        if (encoded.remove(imageData) != null) droppedCount++;
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
        if (encoded.remove(imageData) != null) usedCount++;
    }

    static void resetForTest() {
        guard.on = false;
        s3tc = false;
        encoded.clear();
        encodedCount = 0;
        droppedCount = 0;
        usedCount = 0;
    }
}