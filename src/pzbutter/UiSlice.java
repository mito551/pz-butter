package pzbutter;

import zombie.core.Styles.Style;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;

/**
 * A kept copy of one UI element's TextureDraw range (Plan 3c). record copies the range out of a render state, replay
 * writes it back into a state's own pooled slots, sameAs compares a kept range with a live one. The slice owns its
 * entries; nothing here touches global GL state (the caller brackets record and replay with GLState.startFrame).
 */
final class UiSlice {
    TextureDraw[] entries = new TextureDraw[0];
    Style[] styles = new Style[0];
    int n;
    int stencilIn, stencilOut;   // for the GL cache (Task 4); unused here

    private UiSlice() { }

    static boolean replayable(TextureDraw.Type t) {
        if (t == null) return false;
        switch (t) {
            case glDraw: case glStencilFunc: case glStencilOp: case glStencilMask: case glColorMask:
            case glAlphaFunc: case glBlendFunc: case glBlendFuncSeparate: case glEnable: case glDisable:
                return true;
            default:
                return false;
        }
    }

    static UiSlice record(GenericSpriteRenderState s, int from, int to, UiSlice reuse) {
        final TextureDraw[] src = s.sprite;
        int i = to;
        while (--i >= from) {   // validate tail-first; nothing is written until all pass
            TextureDraw d = src[i];
            if (d == null || !replayable(d.type) || d.drawer != null || d.future != null || d.probe != null
                    || d.imDrawData != null) return null;
            if (d.tex != null && !d.tex.isReady()) return null;
        }
        final int n = to - from;
        UiSlice sl = reuse != null ? reuse : new UiSlice();
        if (sl.entries.length < n) {
            TextureDraw[] e = java.util.Arrays.copyOf(sl.entries, n);
            for (int k = sl.entries.length; k < n; k++) e[k] = new TextureDraw();
            sl.entries = e;
        }
        if (sl.styles.length < n) sl.styles = new Style[n];
        final TextureDraw[] dst = sl.entries;
        final Style[] st = sl.styles;
        System.arraycopy(s.style, from, st, 0, n);
        int k = n;
        while (--k >= 0) copy(src[from + k], dst[k]);
        sl.n = n;
        return sl;
    }

    static void replay(UiSlice sl, GenericSpriteRenderState s) {
        final int n = sl.n;
        for (int k = 0; k < n; k++) { s.CheckSpriteSlots(); s.numSprites++; }
        final int base = s.numSprites - n;
        final TextureDraw[] dst = s.sprite;
        final TextureDraw[] src = sl.entries;
        System.arraycopy(sl.styles, 0, s.style, base, n);
        int k = n;
        while (--k >= 0) copy(src[k], dst[base + k]);
    }

    static boolean sameAs(UiSlice a, GenericSpriteRenderState s, int from, int to) {
        if (a.n != to - from) return false;
        for (int i = 0; i < a.n; i++) {
            if (a.styles[i] != s.style[from + i]) return false;
            TextureDraw x = a.entries[i], y = s.sprite[from + i];
            if (x.type != y.type || x.tex != y.tex || x.tex1 != y.tex1 || x.tex2 != y.tex2
                    || x.drawer != y.drawer || x.future != y.future || x.probe != y.probe
                    || x.imDrawData != y.imDrawData) return false;
            if (!java.util.Arrays.equals(x.vars, y.vars)) return false;
            if (x.flipped != y.flipped
                    || x.a != y.a
                    || x.b != y.b
                    || Float.floatToRawIntBits(x.f1) != Float.floatToRawIntBits(y.f1)
                    || x.c != y.c
                    || x.d != y.d
                    || x.col0 != y.col0
                    || x.col1 != y.col1
                    || x.col2 != y.col2
                    || x.col3 != y.col3
                    || Float.floatToRawIntBits(x.x0) != Float.floatToRawIntBits(y.x0)
                    || Float.floatToRawIntBits(x.x1) != Float.floatToRawIntBits(y.x1)
                    || Float.floatToRawIntBits(x.x2) != Float.floatToRawIntBits(y.x2)
                    || Float.floatToRawIntBits(x.x3) != Float.floatToRawIntBits(y.x3)
                    || Float.floatToRawIntBits(x.y0) != Float.floatToRawIntBits(y.y0)
                    || Float.floatToRawIntBits(x.y1) != Float.floatToRawIntBits(y.y1)
                    || Float.floatToRawIntBits(x.y2) != Float.floatToRawIntBits(y.y2)
                    || Float.floatToRawIntBits(x.y3) != Float.floatToRawIntBits(y.y3)
                    || Float.floatToRawIntBits(x.u0) != Float.floatToRawIntBits(y.u0)
                    || Float.floatToRawIntBits(x.u1) != Float.floatToRawIntBits(y.u1)
                    || Float.floatToRawIntBits(x.u2) != Float.floatToRawIntBits(y.u2)
                    || Float.floatToRawIntBits(x.u3) != Float.floatToRawIntBits(y.u3)
                    || Float.floatToRawIntBits(x.v0) != Float.floatToRawIntBits(y.v0)
                    || Float.floatToRawIntBits(x.v1) != Float.floatToRawIntBits(y.v1)
                    || Float.floatToRawIntBits(x.v2) != Float.floatToRawIntBits(y.v2)
                    || Float.floatToRawIntBits(x.v3) != Float.floatToRawIntBits(y.v3)
                    || Float.floatToRawIntBits(x.z) != Float.floatToRawIntBits(y.z)
                    || Float.floatToRawIntBits(x.chunkDepth) != Float.floatToRawIntBits(y.chunkDepth)
                    || x.useAttribArray != y.useAttribArray
                    || Float.floatToRawIntBits(x.tex1U0) != Float.floatToRawIntBits(y.tex1U0)
                    || Float.floatToRawIntBits(x.tex1U1) != Float.floatToRawIntBits(y.tex1U1)
                    || Float.floatToRawIntBits(x.tex1U2) != Float.floatToRawIntBits(y.tex1U2)
                    || Float.floatToRawIntBits(x.tex1U3) != Float.floatToRawIntBits(y.tex1U3)
                    || Float.floatToRawIntBits(x.tex1V0) != Float.floatToRawIntBits(y.tex1V0)
                    || Float.floatToRawIntBits(x.tex1V1) != Float.floatToRawIntBits(y.tex1V1)
                    || Float.floatToRawIntBits(x.tex1V2) != Float.floatToRawIntBits(y.tex1V2)
                    || Float.floatToRawIntBits(x.tex1V3) != Float.floatToRawIntBits(y.tex1V3)
                    || x.tex1Col0 != y.tex1Col0
                    || x.tex1Col1 != y.tex1Col1
                    || x.tex1Col2 != y.tex1Col2
                    || x.tex1Col3 != y.tex1Col3
                    || Float.floatToRawIntBits(x.tex2U0) != Float.floatToRawIntBits(y.tex2U0)
                    || Float.floatToRawIntBits(x.tex2U1) != Float.floatToRawIntBits(y.tex2U1)
                    || Float.floatToRawIntBits(x.tex2U2) != Float.floatToRawIntBits(y.tex2U2)
                    || Float.floatToRawIntBits(x.tex2U3) != Float.floatToRawIntBits(y.tex2U3)
                    || Float.floatToRawIntBits(x.tex2V0) != Float.floatToRawIntBits(y.tex2V0)
                    || Float.floatToRawIntBits(x.tex2V1) != Float.floatToRawIntBits(y.tex2V1)
                    || Float.floatToRawIntBits(x.tex2V2) != Float.floatToRawIntBits(y.tex2V2)
                    || Float.floatToRawIntBits(x.tex2V3) != Float.floatToRawIntBits(y.tex2V3)
                    || x.singleCol != y.singleCol) return false;
        }
        return true;
    }

    static void copy(TextureDraw from, TextureDraw to) {
        to.imDrawData = null; to.probe = null; to.drawer = null; to.future = null;
        to.tex2 = from.tex2; to.tex1 = from.tex1; to.tex = from.tex;
        to.singleCol = from.singleCol;
        to.tex2V3 = from.tex2V3;
        to.tex2V2 = from.tex2V2;
        to.tex2V1 = from.tex2V1;
        to.tex2V0 = from.tex2V0;
        to.tex2U3 = from.tex2U3;
        to.tex2U2 = from.tex2U2;
        to.tex2U1 = from.tex2U1;
        to.tex2U0 = from.tex2U0;
        to.tex1Col3 = from.tex1Col3;
        to.tex1Col2 = from.tex1Col2;
        to.tex1Col1 = from.tex1Col1;
        to.tex1Col0 = from.tex1Col0;
        to.tex1V3 = from.tex1V3;
        to.tex1V2 = from.tex1V2;
        to.tex1V1 = from.tex1V1;
        to.tex1V0 = from.tex1V0;
        to.tex1U3 = from.tex1U3;
        to.tex1U2 = from.tex1U2;
        to.tex1U1 = from.tex1U1;
        to.tex1U0 = from.tex1U0;
        to.useAttribArray = from.useAttribArray;
        to.chunkDepth = from.chunkDepth;
        to.z = from.z;
        to.v3 = from.v3;
        to.v2 = from.v2;
        to.v1 = from.v1;
        to.v0 = from.v0;
        to.u3 = from.u3;
        to.u2 = from.u2;
        to.u1 = from.u1;
        to.u0 = from.u0;
        to.y3 = from.y3;
        to.y2 = from.y2;
        to.y1 = from.y1;
        to.y0 = from.y0;
        to.x3 = from.x3;
        to.x2 = from.x2;
        to.x1 = from.x1;
        to.x0 = from.x0;
        to.col3 = from.col3;
        to.col2 = from.col2;
        to.col1 = from.col1;
        to.col0 = from.col0;
        to.d = from.d;
        to.c = from.c;
        to.f1 = from.f1;
        to.b = from.b;
        to.a = from.a;
        to.flipped = from.flipped;
        final float[] v = from.vars;
        final float[] w = to.vars;
        if (v == null) to.vars = null;
        else if (w != null && w.length == v.length) System.arraycopy(v, 0, w, 0, v.length);
        else to.vars = java.util.Arrays.copyOf(v, v.length);
        to.type = from.type;
    }
}
