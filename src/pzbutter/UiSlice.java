package pzbutter;

import java.util.Arrays;

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
        return t != null && switch (t) {
            case glDraw, glStencilFunc, glStencilOp, glStencilMask, glColorMask, glAlphaFunc,
                 glBlendFunc, glBlendFuncSeparate, glEnable, glDisable -> true;
            default -> false;
        };
    }

    /**
     * The entries [from, to) of s, copied into reuse (or a new slice). Null when an entry is not replayable, holds a
     * drawer, future, probe or imDrawData, or holds a texture that is not ready. Null leaves reuse untouched.
     */
    static UiSlice record(GenericSpriteRenderState s, int from, int to, UiSlice reuse) {
        TextureDraw[] sprite = s.sprite;
        for (int i = from; i < to; i++) {
            TextureDraw d = sprite[i];
            if (!replayable(d.type) || d.drawer != null || d.future != null || d.probe != null || d.imDrawData != null) {
                return null;
            }
            if (d.tex != null && !d.tex.isReady()) return null;
        }
        int count = to - from;
        UiSlice sl = reuse != null ? reuse : new UiSlice();
        if (sl.entries.length < count) {
            sl.entries = Arrays.copyOf(sl.entries, count);
            sl.styles = Arrays.copyOf(sl.styles, count);
        }
        for (int i = 0; i < count; i++) {
            TextureDraw d = sl.entries[i];
            if (d == null) sl.entries[i] = d = new TextureDraw();
            copy(sprite[from + i], d);
            sl.styles[i] = s.style[from + i];
        }
        sl.n = count;
        return sl;
    }

    /** Appends the slice to s: each entry goes into the state's own slot, so the slice's objects are never stored. */
    static void replay(UiSlice sl, GenericSpriteRenderState s) {
        for (int i = 0; i < sl.n; i++) {
            s.CheckSpriteSlots();
            int at = s.numSprites;
            copy(sl.entries[i], s.sprite[at]);
            s.style[at] = sl.styles[i];
            s.numSprites++;
        }
    }

    /** True when the slice holds exactly the entries [from, to) of s, field by field. */
    static boolean sameAs(UiSlice a, GenericSpriteRenderState s, int from, int to) {
        if (a.n != to - from) return false;
        for (int i = 0; i < a.n; i++) {
            if (a.styles[i] != s.style[from + i] || !same(a.entries[i], s.sprite[from + i])) return false;
        }
        return true;
    }

    /** Copies every field of from into to. The handles future, probe, imDrawData and drawer end up null in to. */
    static void copy(TextureDraw from, TextureDraw to) {
        to.type = from.type;
        to.flipped = from.flipped;
        to.a = from.a;
        to.b = from.b;
        to.f1 = from.f1;
        to.vars = copyVars(from.vars, to.vars);
        to.c = from.c;
        to.d = from.d;
        to.col0 = from.col0;
        to.col1 = from.col1;
        to.col2 = from.col2;
        to.col3 = from.col3;
        to.x0 = from.x0;
        to.x1 = from.x1;
        to.x2 = from.x2;
        to.x3 = from.x3;
        to.y0 = from.y0;
        to.y1 = from.y1;
        to.y2 = from.y2;
        to.y3 = from.y3;
        to.u0 = from.u0;
        to.u1 = from.u1;
        to.u2 = from.u2;
        to.u3 = from.u3;
        to.v0 = from.v0;
        to.v1 = from.v1;
        to.v2 = from.v2;
        to.v3 = from.v3;
        to.z = from.z;
        to.chunkDepth = from.chunkDepth;
        to.tex = from.tex;
        to.tex1 = from.tex1;
        to.tex2 = from.tex2;
        to.useAttribArray = from.useAttribArray;
        to.tex1U0 = from.tex1U0;
        to.tex1U1 = from.tex1U1;
        to.tex1U2 = from.tex1U2;
        to.tex1U3 = from.tex1U3;
        to.tex1V0 = from.tex1V0;
        to.tex1V1 = from.tex1V1;
        to.tex1V2 = from.tex1V2;
        to.tex1V3 = from.tex1V3;
        to.tex1Col0 = from.tex1Col0;
        to.tex1Col1 = from.tex1Col1;
        to.tex1Col2 = from.tex1Col2;
        to.tex1Col3 = from.tex1Col3;
        to.tex2U0 = from.tex2U0;
        to.tex2U1 = from.tex2U1;
        to.tex2U2 = from.tex2U2;
        to.tex2U3 = from.tex2U3;
        to.tex2V0 = from.tex2V0;
        to.tex2V1 = from.tex2V1;
        to.tex2V2 = from.tex2V2;
        to.tex2V3 = from.tex2V3;
        to.singleCol = from.singleCol;
        to.drawer = null;
        to.future = null;
        to.probe = null;
        to.imDrawData = null;
    }

    // the destination keeps its own array when the length matches: a kept slice never aliases a pooled live array
    private static float[] copyVars(float[] from, float[] to) {
        if (from == null) return null;
        if (to == null || to.length != from.length) to = new float[from.length];
        System.arraycopy(from, 0, to, 0, from.length);
        return to;
    }

    private static boolean same(TextureDraw x, TextureDraw y) {
        return x.type == y.type && x.flipped == y.flipped && x.a == y.a && x.b == y.b && eq(x.f1, y.f1)
            && Arrays.equals(x.vars, y.vars) && x.c == y.c && x.d == y.d
            && x.col0 == y.col0 && x.col1 == y.col1 && x.col2 == y.col2 && x.col3 == y.col3
            && eq(x.x0, y.x0) && eq(x.x1, y.x1) && eq(x.x2, y.x2) && eq(x.x3, y.x3)
            && eq(x.y0, y.y0) && eq(x.y1, y.y1) && eq(x.y2, y.y2) && eq(x.y3, y.y3)
            && eq(x.u0, y.u0) && eq(x.u1, y.u1) && eq(x.u2, y.u2) && eq(x.u3, y.u3)
            && eq(x.v0, y.v0) && eq(x.v1, y.v1) && eq(x.v2, y.v2) && eq(x.v3, y.v3)
            && eq(x.z, y.z) && eq(x.chunkDepth, y.chunkDepth)
            && x.tex == y.tex && x.tex1 == y.tex1 && x.tex2 == y.tex2 && x.useAttribArray == y.useAttribArray
            && eq(x.tex1U0, y.tex1U0) && eq(x.tex1U1, y.tex1U1) && eq(x.tex1U2, y.tex1U2) && eq(x.tex1U3, y.tex1U3)
            && eq(x.tex1V0, y.tex1V0) && eq(x.tex1V1, y.tex1V1) && eq(x.tex1V2, y.tex1V2) && eq(x.tex1V3, y.tex1V3)
            && x.tex1Col0 == y.tex1Col0 && x.tex1Col1 == y.tex1Col1 && x.tex1Col2 == y.tex1Col2 && x.tex1Col3 == y.tex1Col3
            && eq(x.tex2U0, y.tex2U0) && eq(x.tex2U1, y.tex2U1) && eq(x.tex2U2, y.tex2U2) && eq(x.tex2U3, y.tex2U3)
            && eq(x.tex2V0, y.tex2V0) && eq(x.tex2V1, y.tex2V1) && eq(x.tex2V2, y.tex2V2) && eq(x.tex2V3, y.tex2V3)
            && x.singleCol == y.singleCol
            && x.drawer == y.drawer && x.future == y.future && x.probe == y.probe && x.imDrawData == y.imDrawData;
    }

    // exact bits: a NaN equals itself, and -0 differs from 0, so "same" means the replay would write the same value
    private static boolean eq(float x, float y) {
        return Float.floatToRawIntBits(x) == Float.floatToRawIntBits(y);
    }
}
