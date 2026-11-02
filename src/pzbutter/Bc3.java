package pzbutter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** BC3 (DXT5) encoder for 4x4 blocks of premultiplied RGBA8. */
public final class Bc3 {
    private Bc3() {}

    public static int encodedSize(int w, int h) {
        int bw = (w + 3) >>> 2;
        int bh = (h + 3) >>> 2;
        return bw * bh * 16;
    }

    public static ByteBuffer encode(ByteBuffer rgba, int w, int h) {
        int n = w * h * 4;
        byte[] src = new byte[n];
        ByteBuffer dup = rgba.duplicate();
        dup.rewind();
        dup.get(src);
        byte[] dst = new byte[encodedSize(w, h)];
        encodeTo(src, w, h, dst);
        ByteBuffer out = ByteBuffer.allocateDirect(dst.length).order(ByteOrder.LITTLE_ENDIAN);
        out.put(dst);
        out.flip();
        return out;
    }

    static void encodeTo(byte[] src, int w, int h, byte[] dst) {
        int bw = (w + 3) >>> 2;
        int bh = (h + 3) >>> 2;
        int di = 0;
        int[] rr = new int[16];
        int[] gg = new int[16];
        int[] bb = new int[16];
        int[] aa = new int[16];
        int[] apal = new int[8];
        int[] c0rgb = new int[3], c1rgb = new int[3];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                // gather 16 texels (clamped)
                int rmin = 255, rmax = 0, gmin = 255, gmax = 0, bmin = 255, bmax = 0;
                int amin = 255, amax = 0;
                for (int ty = 0; ty < 4; ty++) {
                    int y = by * 4 + ty;
                    if (y >= h) y = h - 1;
                    int row = y * w;
                    for (int tx = 0; tx < 4; tx++) {
                        int x = bx * 4 + tx;
                        if (x >= w) x = w - 1;
                        int p = (row + x) * 4;
                        int rv = src[p] & 0xff;
                        int gv = src[p + 1] & 0xff;
                        int bv = src[p + 2] & 0xff;
                        int av = src[p + 3] & 0xff;
                        int ti = ty * 4 + tx;
                        rr[ti] = rv; gg[ti] = gv; bb[ti] = bv; aa[ti] = av;
                        if (rv < rmin) rmin = rv; if (rv > rmax) rmax = rv;
                        if (gv < gmin) gmin = gv; if (gv > gmax) gmax = gv;
                        if (bv < bmin) bmin = bv; if (bv > bmax) bmax = bv;
                        if (av < amin) amin = av; if (av > amax) amax = av;
                    }
                }

                // alpha endpoints
                int a0 = amax;
                int a1 = amin;
                // alpha palette
                if (amax == amin) {
                    apal[0] = amax; apal[1] = amin;
                    for (int i = 2; i < 8; i++) apal[i] = 0;
                } else {
                    apal[0] = amax;
                    apal[1] = amin;
                    apal[2] = (6 * amax + 1 * amin) / 7;
                    apal[3] = (5 * amax + 2 * amin) / 7;
                    apal[4] = (4 * amax + 3 * amin) / 7;
                    apal[5] = (3 * amax + 4 * amin) / 7;
                    apal[6] = (2 * amax + 5 * amin) / 7;
                    apal[7] = (1 * amax + 6 * amin) / 7;
                }
                // alpha indices
                long alphaBits = 0;
                for (int i = 0; i < 16; i++) {
                    int av = aa[i];
                    int best = 0, bestd = Math.abs(av - apal[0]);
                    for (int j = 1; j < 8; j++) {
                        int d = Math.abs(av - apal[j]);
                        if (d < bestd) { bestd = d; best = j; }
                    }
                    alphaBits |= ((long) best) << (3 * i);
                }

                // colour bounding box + inset
                int ri = (rmax - rmin) >>> 4; // /16
                int gi = (gmax - gmin) >>> 4;
                int bi = (bmax - bmin) >>> 4;
                int rlo = rmin + ri;
                int rhi = rmax - ri;
                int glo = gmin + gi;
                int ghi = gmax - gi;
                int blo = bmin + bi;
                int bhi = bmax - bi;

                // ensure ordering
                if (rhi < rlo) { rhi = rlo; }
                if (ghi < glo) { ghi = glo; }
                if (bhi < blo) { bhi = blo; }

                int cr0 = rhi, cg0 = ghi, cb0 = bhi;
                int cr1 = rlo, cg1 = glo, cb1 = blo;

                int c0 = rgbTo565(cr0, cg0, cb0);
                int c1 = rgbTo565(cr1, cg1, cb1);

                // c0 == c1 is a valid BC3 block (4-colour mode always): every index picks c0. No nudge (one would wrap 0xffff to 0).

                // expanded palette
                expand565(c0, c0rgb);
                expand565(c1, c1rgb);
                int p2r = (2 * c0rgb[0] + c1rgb[0]) / 3;
                int p2g = (2 * c0rgb[1] + c1rgb[1]) / 3;
                int p2b = (2 * c0rgb[2] + c1rgb[2]) / 3;
                int p3r = (c0rgb[0] + 2 * c1rgb[0]) / 3;
                int p3g = (c0rgb[1] + 2 * c1rgb[1]) / 3;
                int p3b = (c0rgb[2] + 2 * c1rgb[2]) / 3;

                // colour indices
                int colorBits = 0;
                for (int i = 0; i < 16; i++) {
                    int rv = rr[i], gv = gg[i], bv = bb[i];
                    int d0 = sqDist(rv, gv, bv, c0rgb[0], c0rgb[1], c0rgb[2]);
                    int d1 = sqDist(rv, gv, bv, c1rgb[0], c1rgb[1], c1rgb[2]);
                    int d2 = sqDist(rv, gv, bv, p2r, p2g, p2b);
                    int d3 = sqDist(rv, gv, bv, p3r, p3g, p3b);
                    int best = 0; int bestD = d0;
                    if (d1 < bestD) { bestD = d1; best = 1; }
                    if (d2 < bestD) { bestD = d2; best = 2; }
                    if (d3 < bestD) { best = 3; }
                    colorBits |= (best & 3) << (2 * i);
                }

                // write block
                dst[di] = (byte) (a0 & 0xff);
                dst[di + 1] = (byte) (a1 & 0xff);
                for (int b = 0; b < 6; b++) {
                    dst[di + 2 + b] = (byte) ((alphaBits >>> (8 * b)) & 0xff);
                }
                dst[di + 8] = (byte) (c0 & 0xff);
                dst[di + 9] = (byte) ((c0 >>> 8) & 0xff);
                dst[di + 10] = (byte) (c1 & 0xff);
                dst[di + 11] = (byte) ((c1 >>> 8) & 0xff);
                for (int b = 0; b < 4; b++) {
                    dst[di + 12 + b] = (byte) ((colorBits >>> (8 * b)) & 0xff);
                }
                di += 16;
            }
        }
    }

    static int rgbTo565(int r, int g, int b) {
        int r5 = (r * 31 + 127) / 255;
        int g6 = (g * 63 + 127) / 255;
        int b5 = (b * 31 + 127) / 255;
        if (r5 > 31) r5 = 31; if (r5 < 0) r5 = 0;
        if (g6 > 63) g6 = 63; if (g6 < 0) g6 = 0;
        if (b5 > 31) b5 = 31; if (b5 < 0) b5 = 0;
        return (r5 << 11) | (g6 << 5) | b5;
    }

    static void expand565(int v, int[] out) {
        int r5 = (v >>> 11) & 0x1f;
        int g6 = (v >>> 5) & 0x3f;
        int b5 = v & 0x1f;
        out[0] = (r5 << 3) | (r5 >>> 2);
        out[1] = (g6 << 2) | (g6 >>> 4);
        out[2] = (b5 << 3) | (b5 >>> 2);
    }

    static int sqDist(int r1, int g1, int b1, int r2, int g2, int b2) {
        int dr = r1 - r2;
        int dg = g1 - g2;
        int db = b1 - b2;
        return dr * dr + dg * dg + db * db;
    }
}
