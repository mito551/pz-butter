package pzbutter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BC3 (DXT5) encoder for 4x4 blocks of RGBA8, as stock uploads it (premultiplied on the mip path, straight on the no-mip path).
 * Colour endpoints span the block's box along its colour line (variant round V11e: variant A's shape, 1.7x ours).
 */
public final class Bc3 {
    private Bc3() {}

    private static final byte[] ROUND5 = new byte[256];
    private static final byte[] ROUND6 = new byte[256];
    static {
        for (int i = 0; i < 256; i++) {
            ROUND5[i] = (byte) ((i * 31 + 127) / 255);
            ROUND6[i] = (byte) ((i * 63 + 127) / 255);
        }
    }

    public static int encodedSize(int w, int h) { return ((w + 3) >>> 2) * ((h + 3) >>> 2) * 16; }

    public static ByteBuffer encode(ByteBuffer rgba, int w, int h) {
        int size = encodedSize(w, h);
        int srcOff = 0;
        byte[] src;
        if (rgba.hasArray()) {
            src = rgba.array();
            srcOff = rgba.arrayOffset() + rgba.position();
        } else {
            src = new byte[w * h * 4];
            ByteBuffer d = rgba.duplicate();
            d.get(src);
        }
        byte[] dst = new byte[size];
        encodeTo(src, srcOff, w, h, dst);
        ByteBuffer out = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        out.put(dst);
        out.flip();
        return out;
    }

    static void encodeTo(byte[] src, int w, int h, byte[] dst) {
        encodeTo(src, 0, w, h, dst);
    }

    private static void encodeTo(byte[] src, int srcOff, int w, int h, byte[] dst) {
        int bw = (w + 3) >>> 2;
        int bh = (h + 3) >>> 2;
        int[] pix = new int[16];
        int d = 0;
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                // gather 16 texels (edge clamp by repetition)
                int ox = bx << 2;
                int oy = by << 2;
                for (int ty = 0; ty < 4; ty++) {
                    int y = oy + ty;
                    if (y >= h) y = h - 1;
                    int rowBase = (y * w) * 4 + srcOff;
                    for (int tx = 0; tx < 4; tx++) {
                        int x = ox + tx;
                        if (x >= w) x = w - 1;
                        int p = rowBase + (x << 2);
                        int r = src[p] & 255;
                        int g = src[p + 1] & 255;
                        int b = src[p + 2] & 255;
                        int a = src[p + 3] & 255;
                        pix[(ty << 2) | tx] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }

                // alpha endpoints
                int aMin = 255, aMax = 0;
                int rMin = 255, rMax = 0, gMin = 255, gMax = 0, bMin = 255, bMax = 0;
                int sr = 0, sg = 0, sb = 0, srg = 0, srb = 0, sgb = 0;
                for (int i = 0; i < 16; i++) {
                    int v = pix[i];
                    int a = v >>> 24;
                    int r = (v >>> 16) & 255;
                    int g = (v >>> 8) & 255;
                    int bb = v & 255;
                    if (a < aMin) aMin = a;
                    if (a > aMax) aMax = a;
                    if (r < rMin) rMin = r;
                    if (r > rMax) rMax = r;
                    if (g < gMin) gMin = g;
                    if (g > gMax) gMax = g;
                    if (bb < bMin) bMin = bb;
                    if (bb > bMax) bMax = bb;
                    sr += r; sg += g; sb += bb;
                    srg += r * g; srb += r * bb; sgb += g * bb;
                }
                // the colour line's direction: against the widest channel, a channel whose covariance is negative runs the other way
                int covRG = 16 * srg - sr * sg, covRB = 16 * srb - sr * sb, covGB = 16 * sgb - sg * sb;

                int inset;
                int rExt = rMax - rMin;
                inset = rExt >>> 4;
                rMin += inset; rMax -= inset;
                int gExt = gMax - gMin;
                inset = gExt >>> 4;
                gMin += inset; gMax -= inset;
                int bExt = bMax - bMin;
                inset = bExt >>> 4;
                bMin += inset; bMax -= inset;

                int a0 = aMax, a1 = aMin;

                // alpha palette
                int[] apal;
                if (a0 > a1) {
                    apal = new int[] { a0, a1, (6 * a0 + a1) / 7, (5 * a0 + 2 * a1) / 7,
                            (4 * a0 + 3 * a1) / 7, (3 * a0 + 4 * a1) / 7,
                            (2 * a0 + 5 * a1) / 7, (a0 + 6 * a1) / 7 };
                } else {
                    apal = new int[] { a0, a1, (4 * a0 + a1) / 5, (3 * a0 + 2 * a1) / 5,
                            (2 * a0 + 3 * a1) / 5, (a0 + 4 * a1) / 5, 0, 255 };
                }

                boolean flipR, flipG, flipB;
                if (rExt >= gExt && rExt >= bExt) { flipR = false; flipG = covRG < 0; flipB = covRB < 0; }
                else if (gExt >= bExt) { flipG = false; flipR = covRG < 0; flipB = covGB < 0; }
                else { flipB = false; flipR = covRB < 0; flipG = covGB < 0; }
                int rHi = flipR ? rMin : rMax, rLo = flipR ? rMax : rMin;
                int gHi = flipG ? gMin : gMax, gLo = flipG ? gMax : gMin;
                int bHi = flipB ? bMin : bMax, bLo = flipB ? bMax : bMin;
                int c0 = ((ROUND5[rHi] & 255) << 11) | ((ROUND6[gHi] & 255) << 5) | (ROUND5[bHi] & 255);
                int c1 = ((ROUND5[rLo] & 255) << 11) | ((ROUND6[gLo] & 255) << 5) | (ROUND5[bLo] & 255);
                if (c0 < c1) { int t = c0; c0 = c1; c1 = t; }   // c0 > c1 keeps 4-colour mode on decoders that apply DXT1's rule; indices are picked after

                int[] e0 = expand(c0);
                int[] e1 = expand(c1);
                int[] pal = new int[] {
                    e0[0], e0[1], e0[2],
                    e1[0], e1[1], e1[2],
                    (2 * e0[0] + e1[0]) / 3, (2 * e0[1] + e1[1]) / 3, (2 * e0[2] + e1[2]) / 3,
                    (e0[0] + 2 * e1[0]) / 3, (e0[1] + 2 * e1[1]) / 3, (e0[2] + 2 * e1[2]) / 3
                };

                long alphaBits = 0;
                int colorBits = 0;
                if (a0 == a1) {
                    // all alpha indices 0 already
                } else {
                    for (int i = 0; i < 16; i++) {
                        int a = (pix[i] >>> 24) & 255;
                        int best = 0, bestD = Math.abs(a - apal[0]);
                        for (int k = 1; k < 8; k++) {
                            int dd = Math.abs(a - apal[k]);
                            if (dd < bestD) { bestD = dd; best = k; }
                        }
                        alphaBits |= (long) best << (3 * i);
                    }
                }

                for (int i = 0; i < 16; i++) {
                    int v = pix[i];
                    int r = (v >>> 16) & 255;
                    int g = (v >>> 8) & 255;
                    int b = v & 255;
                    int best = 0, bestD = (r - pal[0]) * (r - pal[0]) + (g - pal[1]) * (g - pal[1]) + (b - pal[2]) * (b - pal[2]);
                    for (int k = 1; k < 4; k++) {
                        int dr = r - pal[k * 3];
                        int dg = g - pal[k * 3 + 1];
                        int db = b - pal[k * 3 + 2];
                        int dd = dr * dr + dg * dg + db * db;
                        if (dd < bestD) { bestD = dd; best = k; }
                    }
                    colorBits |= best << (2 * i);
                }

                dst[d] = (byte) (a0 & 255);
                dst[d + 1] = (byte) (a1 & 255);
                for (int b = 0; b < 6; b++) dst[d + 2 + b] = (byte) ((alphaBits >>> (8 * b)) & 0xff);
                dst[d + 8] = (byte) (c0 & 255);
                dst[d + 9] = (byte) ((c0 >>> 8) & 255);
                dst[d + 10] = (byte) (c1 & 255);
                dst[d + 11] = (byte) ((c1 >>> 8) & 255);
                for (int b = 0; b < 4; b++) dst[d + 12 + b] = (byte) ((colorBits >>> (8 * b)) & 0xff);
                d += 16;
            }
        }
    }

    private static int[] expand(int v) {
        int r5 = (v >>> 11) & 31;
        int g6 = (v >>> 5) & 63;
        int b5 = v & 31;
        return new int[] { (r5 << 3) | (r5 >>> 2), (g6 << 2) | (g6 >>> 4), (b5 << 3) | (b5 >>> 2) };
    }
}
