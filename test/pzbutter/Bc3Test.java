package pzbutter;

final class Bc3Test {
    private Bc3Test() {}

    static void run() throws Exception {
        bc3SizeIsBlockGrid();
        bc3ConstantBlockExact();
        bc3AlphaEndpoints();
        bc3EdgesPadded();
        bc3ErrorBounded();
        bc3NearWhiteNoWrap();
    }

    static int[] expand(int v) {
        int r5 = (v >>> 11) & 31;
        int g6 = (v >>> 5) & 63;
        int b5 = v & 31;
        return new int[] { (r5 << 3) | (r5 >>> 2), (g6 << 2) | (g6 >>> 4), (b5 << 3) | (b5 >>> 2) };
    }

    static byte[] decode(byte[] bc, int w, int h) {
        int bw = (w + 3) / 4, bh = (h + 3) / 4;
        byte[] out = new byte[w * h * 4];
        for (int by = 0; by < bh; by++)
            for (int bx = 0; bx < bw; bx++) {
                int off = (by * bw + bx) * 16;
                int a0 = bc[off] & 255;
                int a1 = bc[off + 1] & 255;
                long abits = 0;
                for (int b = 0; b < 6; b++) abits |= (bc[off + 2 + b] & 255L) << (8 * b);
                int c0 = (bc[off + 8] & 255) | ((bc[off + 9] & 255) << 8);
                int c1 = (bc[off + 10] & 255) | ((bc[off + 11] & 255) << 8);
                int cbits = 0;
                for (int b = 0; b < 4; b++) cbits |= (bc[off + 12 + b] & 255) << (8 * b);
                int[] apal = new int[8];
                if (a0 > a1) {
                    apal[0] = a0; apal[1] = a1;
                    apal[2] = (6 * a0 + 1 * a1) / 7;
                    apal[3] = (5 * a0 + 2 * a1) / 7;
                    apal[4] = (4 * a0 + 3 * a1) / 7;
                    apal[5] = (3 * a0 + 4 * a1) / 7;
                    apal[6] = (2 * a0 + 5 * a1) / 7;
                    apal[7] = (1 * a0 + 6 * a1) / 7;
                } else {
                    apal[0] = a0; apal[1] = a1;
                    apal[2] = (4 * a0 + 1 * a1) / 5;
                    apal[3] = (3 * a0 + 2 * a1) / 5;
                    apal[4] = (2 * a0 + 3 * a1) / 5;
                    apal[5] = (1 * a0 + 4 * a1) / 5;
                    apal[6] = 0; apal[7] = 255;
                }
                int[] e0 = expand(c0), e1 = expand(c1);
                int[] pal = new int[12];
                pal[0] = e0[0]; pal[1] = e0[1]; pal[2] = e0[2];
                pal[3] = e1[0]; pal[4] = e1[1]; pal[5] = e1[2];
                pal[6] = (2 * e0[0] + e1[0]) / 3; pal[7] = (2 * e0[1] + e1[1]) / 3; pal[8] = (2 * e0[2] + e1[2]) / 3;
                pal[9] = (e0[0] + 2 * e1[0]) / 3; pal[10] = (e0[1] + 2 * e1[1]) / 3; pal[11] = (e0[2] + 2 * e1[2]) / 3;
                for (int ty = 0; ty < 4; ty++)
                    for (int tx = 0; tx < 4; tx++) {
                        int x = bx * 4 + tx;
                        int y = by * 4 + ty;
                        if (x >= w || y >= h) continue;
                        int i = ty * 4 + tx;
                        int ai = (int) ((abits >>> (3 * i)) & 7);
                        int ci = (cbits >>> (2 * i)) & 3;
                        int p = (y * w + x) * 4;
                        out[p] = (byte) (pal[ci * 3] & 255);
                        out[p + 1] = (byte) (pal[ci * 3 + 1] & 255);
                        out[p + 2] = (byte) (pal[ci * 3 + 2] & 255);
                        out[p + 3] = (byte) (apal[ai] & 255);
                    }
            }
        return out;
    }

    /** Texels that differ but round to the same RGB565 endpoint (254/255): stays near white (a +1 nudge would wrap 0xffff to black). */
    static void bc3NearWhiteNoWrap() {
        byte[] src = new byte[4 * 4 * 4];
        for (int i = 0; i < 16; i++) { int v = (i & 1) == 0 ? 255 : 254; src[i * 4] = (byte) v; src[i * 4 + 1] = (byte) v; src[i * 4 + 2] = (byte) v; src[i * 4 + 3] = (byte) 255; }
        byte[] dst = new byte[Bc3.encodedSize(4, 4)];
        Bc3.encodeTo(src, 4, 4, dst);
        byte[] out = decode(dst, 4, 4);
        for (int i = 0; i < out.length; i++) assert (out[i] & 255) >= 246 : i + ": " + (out[i] & 255);
    }

    static void bc3SizeIsBlockGrid() {
        for (int[] wh : new int[][] { {1, 1}, {3, 5}, {2, 2}, {4, 4}, {5, 5}, {1024, 1024} }) {
            int w = wh[0], h = wh[1];
            assert Bc3.encodedSize(w, h) == ((w + 3) / 4) * ((h + 3) / 4) * 16;
            byte[] src = new byte[w * h * 4];
            byte[] dst = new byte[Bc3.encodedSize(w, h)];
            Bc3.encodeTo(src, w, h, dst);
            java.nio.ByteBuffer bb = Bc3.encode(java.nio.ByteBuffer.wrap(src), w, h);
            assert bb.position() == 0 && bb.capacity() == dst.length;
        }
    }

    static void bc3ConstantBlockExact() {
        int w = 4, h = 4;
        byte[] src = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            src[i * 4] = (byte) 255;
            src[i * 4 + 1] = (byte) 255;
            src[i * 4 + 2] = (byte) 255;
            src[i * 4 + 3] = (byte) 123;
        }
        byte[] dst = new byte[Bc3.encodedSize(w, h)];
        Bc3.encodeTo(src, w, h, dst);
        byte[] dec = decode(dst, w, h);
        for (int i = 0; i < w * h; i++) {
            assert (dec[i * 4] & 255) == 255 : dec[i * 4];
            assert (dec[i * 4 + 1] & 255) == 255;
            assert (dec[i * 4 + 2] & 255) == 255;
            assert (dec[i * 4 + 3] & 255) == 123;
        }
    }

    static void bc3AlphaEndpoints() {
        int w = 4, h = 4;
        byte[] src = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            src[i * 4 + 3] = (byte) ((i == 0) ? 0 : (i == 1 ? 255 : 100));
            src[i * 4] = (byte) 10; src[i * 4 + 1] = (byte) 20; src[i * 4 + 2] = (byte) 30;
        }
        byte[] dst = new byte[Bc3.encodedSize(w, h)];
        Bc3.encodeTo(src, w, h, dst);
        assert (dst[0] & 255) == 255;
        assert (dst[1] & 255) == 0;
    }

    static void bc3EdgesPadded() throws Exception {
        for (int[] wh : new int[][] { {3, 5}, {1, 1}, {2, 2}, {2, 4} }) {
            int w = wh[0], h = wh[1];
            byte[] src = new byte[w * h * 4];
            for (int i = 0; i < src.length; i++) src[i] = (byte) ((i * 37) & 255);
            byte[] dst = new byte[Bc3.encodedSize(w, h)];
            Bc3.encodeTo(src, w, h, dst);
            assert dst.length == ((w + 3) / 4) * ((h + 3) / 4) * 16;
            byte[] dec = decode(dst, w, h);
            assert dec.length == w * h * 4;
        }
    }

    static void bc3ErrorBounded() {
        int w = 64, h = 64;
        byte[] src = new byte[w * h * 4];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int r = x * 2;
                int g = y * 2;
                int b = x + y;
                int a = x < 32 ? 255 : 64;
                if (a < 0) a = 0; if (a > 255) a = 255;
                src[i] = (byte) r;
                src[i + 1] = (byte) g;
                src[i + 2] = (byte) b;
                src[i + 3] = (byte) a;
            }
        byte[] dst = new byte[Bc3.encodedSize(w, h)];
        Bc3.encodeTo(src, w, h, dst);
        byte[] dec = decode(dst, w, h);
        int maxR = 0, maxG = 0, maxB = 0, maxA = 0;
        long sumR = 0, sumG = 0, sumB = 0, n = (long) w * h;
        for (int i = 0; i < w * h; i++) {
            int dr = Math.abs((src[i * 4] & 255) - (dec[i * 4] & 255));
            int dg = Math.abs((src[i * 4 + 1] & 255) - (dec[i * 4 + 1] & 255));
            int db = Math.abs((src[i * 4 + 2] & 255) - (dec[i * 4 + 2] & 255));
            int da = Math.abs((src[i * 4 + 3] & 255) - (dec[i * 4 + 3] & 255));
            if (dr > maxR) maxR = dr; if (dg > maxG) maxG = dg; if (db > maxB) maxB = db; if (da > maxA) maxA = da;
            sumR += dr; sumG += dg; sumB += db;
        }
        double mean = (sumR + sumG + sumB) / (3.0 * n);
        assert maxR <= 16 && maxG <= 16 && maxB <= 16 : "rgb max " + maxR + " " + maxG + " " + maxB;
        assert maxA <= 18 : "alpha max " + maxA;
        assert mean <= 4 : "mean " + mean;
    }
}
