package pzbutter;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plan 11e (texCompress): with the game's textureCompression option, the driver compresses each texture inside glTexImage2D on
 * the render thread. The file-pool threads BC3-encode the decoded ImageData instead (the FileTask call() exits), and TexTransform
 * routes generateHwId's three glTexImage2D calls here, where a level with a matching entry goes up with glCompressedTexImage2D.
 * Anything that does not match exactly takes stock's call.
 */
public final class TexCompress {
    private TexCompress() {}

    public static final Guard guard = new Guard("texCompress");
    /**
     * The upload can take the blocks, decided at the first generateHwId (render thread) by {@link #decideReady}: the rewrite is in,
     * the GPU has S3TC, and the driver itself stores GL_COMPRESSED_RGBA as S3TC. false: nothing is encoded.
     */
    public static volatile boolean s3tc = false;
    private static volatile boolean s3tcKnown = false;
    /** Why {@link #s3tc} is false, for the summary; the driver's format for GL_COMPRESSED_RGBA (0: not probed). */
    static volatile String offReason = "not decided";
    static volatile int probedFormat;
    /** The ImageData generateHwId is uploading when its entry fits stock's path; render thread only. */
    static Object current;
    /** Whether a level of {@link #current} already went up compressed (the mixed-format check). */
    private static boolean currentCompressed;
    private static boolean firstCompressedDone, smallChecked, mixedLogged;
    private static int firstCompressedError;
    /** texImage2D calls (render thread): zero with uploads happening means the rewrite is not in the running class. */
    static int calls;

    public static final AtomicInteger encodedCount = new AtomicInteger(), droppedCount = new AtomicInteger(),
        compressedCount = new AtomicInteger(), fallbackPathCount = new AtomicInteger(), fallbackFormatCount = new AtomicInteger(),
        fallbackMismatchCount = new AtomicInteger(), fallbackNoEntryCount = new AtomicInteger();
    private static volatile boolean printed;

    public static final class Level {
        public final ByteBuffer rgba;
        public final int w;
        public final int h;
        public final ByteBuffer bc3;
        public Level(ByteBuffer rgba, int w, int h, ByteBuffer bc3) {
            this.rgba = rgba; this.w = w; this.h = h; this.bc3 = bc3;
        }
    }

    /**
     * One ImageData's blocks. mips: the levels are stock's mip chain (generateHwId's mip path); otherwise one level, ImageData.data
     * as the no-mip path uploads it. Used only when stock's own path decision at upload equals this one.
     */
    public record Entry(Level[] levels, boolean mips) {}

    /** Synchronized identity map: ImageData instance -> its encoded per-level BC3 blocks. */
    public static final Map<Object, Entry> encoded = Collections.synchronizedMap(new IdentityHashMap<>());

    private static java.lang.invoke.VarHandle flagsH, dataH, assetFlagsH;   // package-private / protected game fields
    private static volatile boolean flagsBound, uploadBound;

    /** The GL calls (tests replace them). */
    public interface Gl {
        void texImage2D(int target, int level, int internalformat, int w, int h, int border, int format, int type, ByteBuffer pixels);
        void compressedTexImage2D(int target, int level, int internalformat, int w, int h, int border, ByteBuffer pixels);
        int getError();
        /** The internal format the driver gives a 4x4 GL_COMPRESSED_RGBA texture (GL context current). */
        int probeCompressedFormat();
    }
    static final Gl REAL_GL = new Gl() {
        public void texImage2D(int target, int level, int internalformat, int w, int h, int border, int format, int type, ByteBuffer pixels) {
            org.lwjgl.opengl.GL11.glTexImage2D(target, level, internalformat, w, h, border, format, type, pixels);
        }
        public void compressedTexImage2D(int target, int level, int internalformat, int w, int h, int border, ByteBuffer pixels) {
            org.lwjgl.opengl.GL13.glCompressedTexImage2D(target, level, internalformat, w, h, border, pixels);
        }
        public int getError() { return org.lwjgl.opengl.GL11.glGetError(); }
        public int probeCompressedFormat() {
            int prev = org.lwjgl.opengl.GL11.glGetInteger(0x8069);   // GL_TEXTURE_BINDING_2D
            int t = org.lwjgl.opengl.GL11.glGenTextures();
            try {
                org.lwjgl.opengl.GL11.glBindTexture(0x0DE1, t);
                org.lwjgl.opengl.GL11.glTexImage2D(0x0DE1, 0, 34030, 4, 4, 0, 6408, 5121, ByteBuffer.allocateDirect(64));
                return org.lwjgl.opengl.GL11.glGetTexLevelParameteri(0x0DE1, 0, 0x1003);   // GL_TEXTURE_INTERNAL_FORMAT
            } finally {
                org.lwjgl.opengl.GL11.glDeleteTextures(t);
                org.lwjgl.opengl.GL11.glBindTexture(0x0DE1, prev);
            }
        }
    };
    static volatile Gl gl = REAL_GL;

    /** Premain (the transform's switch) and Main. Loads no game class. */
    static void preInit() { init(); }
    public static void init() {
        try { guard.on = Settings.enabled && Settings.flag("texCompress"); }
        catch (Throwable t) { guard.on = false; }
    }

    /** Policy + state: is a pack texture (flags bit 4) to be encoded? */
    public static boolean wouldCompressPack(int flags) {
        if (!guard.on || !s3tc || !TexTransform.transformState) return false;
        try { if (!zombie.core.textures.TextureID.useCompressionOption) return false; } catch (Throwable t) { return false; }
        return (flags & 4) != 0;
    }

    /** Policy + state: is a plain ImageData to be encoded? (its TextureID's flags are checked at upload) */
    public static boolean wouldCompressImage() {
        if (!guard.on || !s3tc || !TexTransform.transformState) return false;
        try { return zombie.core.textures.TextureID.useCompressionOption; } catch (Throwable t) { return false; }
    }

    /** OnExit of FileTask_LoadPackImage.call(): task carries pack flags; ret is the ImageData. */
    public static void onPackImageExit(Object task, Object ret) {
        int flags = reflectFlags(task);
        if (!wouldCompressPack(flags)) return;
        encode(ret, packMips(flags));
    }

    /**
     * generateHwId's mip path is flags 64 and not 16 (and its argument, true for decoded data); call() ran initMipMaps for flags
     * 64 (@44-55), so those levels and the premultiplied base are final. Without 64 the base is raw and stays so on the no-mip path.
     */
    static boolean packMips(int flags) { return (flags & 64) != 0 && (flags & 16) == 0; }

    /** OnExit of FileTask_LoadImageData.call(): ret is the ImageData. */
    public static void onImageExit(Object ret) {
        if (!wouldCompressImage()) return;
        encode(ret, false);   // raw base; the TextureID's flags are known only at upload, where onGenerateEnter checks the path
    }

    private static int reflectFlags(Object task) {
        if (task == null) return 0;
        if (!flagsBound) { flagsH = Game.field(zombie.asset.FileTask_LoadPackImage.class, "flags", int.class); flagsBound = true; }
        if (flagsH == null || !(task instanceof zombie.asset.FileTask_LoadPackImage)) return 0;
        return (int) flagsH.get(task);
    }

    /**
     * Encodes what stock will upload, never changing the ImageData: getMipMapData generates and premultiplies on first use, so it
     * is called only where call() already did (mips). Pool thread.
     */
    private static void encode(Object o, boolean mips) {
        if (!(o instanceof zombie.core.textures.ImageData imageData) || imageData.data == null) return;
        try {
            int count = mips ? imageData.getMipMapCount() : 1;
            if (count <= 0) return;
            Level[] levels = new Level[count];
            byte[] src = null, dst = null;
            for (int i = 0; i < count; i++) {
                zombie.core.textures.MipMapLevel lvl = mips ? imageData.getMipMapData(i) : imageData.data;
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
            encoded.put(imageData, new Entry(levels, mips));
            encodedCount.incrementAndGet();
        } catch (Throwable t) {
            guard.fail(t);
            encoded.remove(o);
        }
    }

    private static synchronized boolean bindUpload() {
        if (!uploadBound) {
            uploadBound = true;
            dataH = Game.field(zombie.core.textures.TextureID.class, "data", zombie.core.textures.ImageData.class);
            assetFlagsH = Game.field(zombie.core.textures.TextureID.TextureIDAssetParams.class, "flags", int.class);
        }
        return dataH != null && assetFlagsH != null;
    }

    /**
     * OnEnter of TextureID.generateHwId(Z) (render thread). Sets {@link #current} only when the data's entry assumed the path stock
     * takes now: flags = assetParams.flags, or useCompressionOption ? 4 : 0 without params (37-65); mips = 64 && !16 && arg (105-126).
     */
    public static void onGenerateEnter(Object textureId, boolean arg) {
        current = null;
        currentCompressed = false;
        if (!s3tcKnown) {
            s3tcKnown = true;
            boolean caps = false;
            String capsErr = null;
            if (guard.on) {
                try {
                    var c = org.lwjglx.opengl.Display.capabilities;
                    caps = c.GL_ARB_texture_compression && c.GL_EXT_texture_compression_s3tc;   // stock needs the first for 34030
                } catch (Throwable t) { capsErr = String.valueOf(t); }
            }
            s3tc = decideReady(caps);
            if (capsErr != null && offReason.equals("no S3TC")) offReason = capsErr;
        }
        if (!guard.on || !s3tc || encoded.isEmpty() || !(textureId instanceof zombie.core.textures.TextureID tid) || !bindUpload()) return;
        Object data = dataH.get(tid);
        Entry e = data == null ? null : encoded.get(data);
        if (e == null) return;
        var ap = tid.assetParams;
        int flags = ap != null ? (int) assetFlagsH.get(ap) : (zombie.core.textures.TextureID.useCompressionOption ? 4 : 0);
        boolean mips = (flags & 64) != 0 && (flags & 16) == 0 && arg;
        if (e.mips() != mips || (mips && e.levels().length != ((zombie.core.textures.ImageData) data).getMipMapCount())) {
            encoded.remove(data);
            fallbackPathCount.incrementAndGet();
            return;
        }
        current = data;
    }

    /**
     * Once, render thread, GL context current. On only when the blocks replace work the driver would do: the rewrite is in, the GPU
     * has S3TC, and the driver stores GL_COMPRESSED_RGBA as S3TC itself (a driver may keep it uncompressed, and glGetError
     * cannot tell). The probe is one 4x4 texture; the binding is restored.
     */
    static boolean decideReady(boolean caps) {
        try {
            if (!guard.on) { offReason = "key off"; return false; }
            if (!TexTransform.transformState) { offReason = "rewrite off"; return false; }
            if (!caps) { offReason = "no S3TC"; return false; }
            drainErrors();
            probedFormat = gl.probeCompressedFormat();
            drainErrors();
            if (probedFormat != 0x83F3 && probedFormat != 0x83F2) {   // DXT5, DXT3
                offReason = "the driver keeps GL_COMPRESSED_RGBA as 0x" + Integer.toHexString(probedFormat);
                return false;
            }
            return true;
        } catch (Throwable t) {
            offReason = String.valueOf(t);
            return false;
        }
    }

    private static void drainErrors() { for (int k = 0; k < 8 && gl.getError() != 0; k++) { } }

    /** OnExit of generateHwId (also on a throw). */
    public static void onGenerateExit() { current = null; currentCompressed = false; }

    /**
     * Stands in for generateHwId's GL11.glTexImage2D calls (TexTransform). Compressed only for the current entry's level with the
     * same buffer object and size, as GL_COMPRESSED_RGBA from RGBA8; everything else is the stock call with the same arguments.
     */
    public static void texImage2D(int target, int level, int internalFormat, int w, int h, int border, int format, int type, ByteBuffer pixels) {
        boolean stock = true;
        calls++;
        try {
            Object cur = current;
            if (cur != null) {
                if (internalFormat != 34030 || format != 6408 || type != 5121) fallbackFormatCount.incrementAndGet();
                else if (!encoded.containsKey(cur)) fallbackNoEntryCount.incrementAndGet();
                else {
                    Level L = pixels == null ? null : levelFor(cur, level, pixels, w, h);
                    if (L == null) {
                        fallbackMismatchCount.incrementAndGet();
                        // ponytail: an earlier level already went up compressed, so this texture now mixes formats. onGenerateEnter's
                        // path check should make this unreachable; if the log ever shows it, encode-or-skip a whole texture at enter.
                        if (currentCompressed && !mixedLogged) { mixedLogged = true; System.out.println("[PZButter] texCompress: level " + level + " fell back after a compressed level (mixed formats in one texture)"); }
                    } else {
                        // glGetError after the first compressed upload, and after the first one below 4x4 (a stricter driver may refuse those)
                        boolean small = w < 4 || h < 4, check = !firstCompressedDone || (small && !smallChecked);
                        if (check) drainErrors();
                        gl.compressedTexImage2D(target, level, 0x83F3, w, h, border, L.bc3);
                        stock = false;
                        if (check) {
                            int err = gl.getError();
                            if (!firstCompressedDone || err != 0) firstCompressedError = err;
                            firstCompressedDone = true;
                            if (small) smallChecked = true;
                            if (err != 0) {
                                System.out.println("[PZButter] texCompress off: glCompressedTexImage2D (" + w + "x" + h + ") gave GL error 0x" + Integer.toHexString(err));
                                guard.on = false;
                                current = null;
                                stock = true;   // this level again the stock way, so the texture is complete
                            }
                        }
                        if (!stock) {
                            compressedCount.incrementAndGet();
                            currentCompressed = true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            guard.fail(t);
        }
        if (stock) gl.texImage2D(target, level, internalFormat, w, h, border, format, type, pixels);
    }

    /** OnEnter of ImageData.dispose(): drop the entry (and its BC3 buffers). */
    public static void onDispose(Object imageData) {
        if (imageData == null) return;
        if (encoded.remove(imageData) != null) droppedCount.incrementAndGet();
    }

    /** Lookup a stored level; identity + size match only. */
    public static Level levelFor(Object imageData, int level, ByteBuffer rgba, int w, int h) {
        Entry e = encoded.get(imageData);
        Level[] arr = e == null ? null : e.levels();
        if (arr == null || level < 0 || level >= arr.length) return null;
        Level L = arr[level];
        if (L == null) return null;
        if (L.rgba != rgba) return null;
        if (L.w != w || L.h != h) return null;
        return L;
    }

    /** GameLoadingState exit: the counts so far. */
    public static void printSummaryOnce() {
        if (printed || !(guard.on || encodedCount.get() > 0)) return;
        printed = true;
        String err = !firstCompressedDone ? "n/a" : firstCompressedError == 0 ? "clean" : "0x" + Integer.toHexString(firstCompressedError);
        System.out.println("[PZButter] texCompress: " + encodedCount.get() + " encoded, " + compressedCount.get() + " compressed uploads, fallbacks: path "
            + fallbackPathCount.get() + ", format " + fallbackFormatCount.get() + ", mismatch " + fallbackMismatchCount.get() + ", no entry "
            + fallbackNoEntryCount.get() + "; glGetError " + err + (s3tc ? "" : "; off: " + offReason)
            + (probedFormat != 0 ? "; driver format 0x" + Integer.toHexString(probedFormat) : "") + "; " + calls + " texImage2D calls, rewrite "
            + (TexTransform.transformState ? "on" : "off: " + TexTransform.transformReason));
    }

    static void resetForTest() {
        guard.on = false;
        s3tc = false;
        s3tcKnown = true;   // tests have no GL context; they set s3tc themselves
        current = null;
        currentCompressed = firstCompressedDone = smallChecked = mixedLogged = printed = false;
        offReason = "not decided";
        probedFormat = 0;
        TexTransform.transformState = true;   // tests run without the class rewrite; the gate's own test sets it false
        firstCompressedError = 0;
        calls = 0;
        encoded.clear();
        for (AtomicInteger c : new AtomicInteger[] { encodedCount, droppedCount, compressedCount, fallbackPathCount,
                fallbackFormatCount, fallbackMismatchCount, fallbackNoEntryCount }) c.set(0);
        flagsBound = false;
        flagsH = null;
        gl = REAL_GL;
    }
}
