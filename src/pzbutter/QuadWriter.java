package pzbutter;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;

/**
 * vboFastQuads: {@code VBORenderer.addQuad} with 24 floats, in mode 7 (quads), calls {@code addElement(9 floats)} four times, each one
 * testing {@code isFull}, writing an index, moving the position, counting the vertex and calling three setters. Ours writes the four
 * vertices and indices in one go, with the same bytes. Before it writes anything it evaluates the game's own flush predicates in the
 * game's order (the {@code reserve(4)}, then {@code isFull} for each of the four vertices); when any of them would flush, the game's body
 * runs this quad, so flushes stay the game's and the fast path never calls {@code flush}. Other modes and overloads are the game's. Only
 * the renderer instance's Java buffers are touched, and that instance is used where the GL context is (the render thread).
 */
final class QuadWriter {
    private QuadWriter() {}

    static final Guard guard = new Guard("vboFastQuads");

    /** The shim's argument pack, reused (render thread only). */
    static final float[] pack = new float[24];

    static void init() {
        guard.on = Settings.flag("vboFastQuads");
        if (guard.on && !H.ok) {
            guard.on = false;
            System.out.println("[PZButter] vboFastQuads off: cannot reach VBORenderer fields");
        }
    }

    /**
     * The handles for the game's private fields, resolved once when this class first loads. They are static finals, so the JIT treats
     * them as constants and inlines each field access; a field that is missing leaves {@code ok} false and the feature off.
     */
    private static final class H {
        static final VarHandle elements, indices, elementSize, numElements, vertexOffset, colorOffset, uv1Offset, dx, dy, dz, run, mode, vertexCount;
        static final boolean ok;

        static {
            Class<?> c = zombie.core.opengl.VBORenderer.class;
            Class<?> rc = null;
            try { rc = Class.forName("zombie.core.opengl.VBORenderer$Run"); }
            catch (ClassNotFoundException e) { System.out.println("[PZButter] vboFastQuads: " + e); }
            elements = Game.field(c, "elements", ByteBuffer.class);
            indices = Game.field(c, "indices", ByteBuffer.class);
            elementSize = Game.field(c, "elementSize", int.class);
            numElements = Game.field(c, "numElements", int.class);
            vertexOffset = Game.field(c, "vertexOffset", int.class);
            colorOffset = Game.field(c, "colorOffset", int.class);
            uv1Offset = Game.field(c, "uv1Offset", int.class);
            dx = Game.field(c, "dx", float.class);
            dy = Game.field(c, "dy", float.class);
            dz = Game.field(c, "dz", float.class);
            run = rc == null ? null : Game.field(c, "runInProgress", rc);
            mode = rc == null ? null : Game.field(rc, "mode", int.class);
            vertexCount = rc == null ? null : Game.field(rc, "vertexCount", int.class);
            ok = elements != null && indices != null && elementSize != null && numElements != null && vertexOffset != null && colorOffset != null
                && uv1Offset != null && dx != null && dy != null && dz != null && run != null && mode != null && vertexCount != null;
        }
    }

    /**
     * Whether the game's body would flush while adding one quad in the given mode: {@code reserve(4)} flushes when four elements do not
     * fit before the buffer's limit; then {@code isFull} is asked before each of the four vertices, with the position and vertex count
     * that vertex would see.
     */
    static boolean flushWouldFire(int mode, int vertexCount, int position, int limit, int elementSize, int numElements) {
        if (position + 4 * elementSize > limit) return true;
        int cap = elementSize * numElements;
        for (int k = 0; k < 4; k++) {
            int vc = vertexCount + k, pos = position + k * elementSize;
            if (mode == 4 && vc % 3 == 0 && pos + 3 * elementSize > cap) return true;
            if (mode == 7 && vc % 4 == 0 && pos + 4 * elementSize > cap) return true;
            if (pos >= cap) return true;
        }
        return false;
    }

    /**
     * The four vertices and their indices of one quad: q is four (x, y, z, u, v) groups and one (r, g, b, a). Offsets of -1 are skipped.
     * Absolute puts, so the order of the three writes does not change the bytes; the position ends after the fourth vertex.
     */
    static void write(ByteBuffer el, ByteBuffer ix, int position, int es, int vOff, int cOff, int uvOff, float dx, float dy, float dz, float[] q) {
        for (int k = 0; k < 4; k++) {
            int base = position + k * es;
            ix.putShort((short) (base / es));
            int g = 5 * k;
            if (vOff != -1) {
                el.putFloat(base + vOff, dx + q[g]);
                el.putFloat(base + vOff + 4, dy + q[g + 1]);
                el.putFloat(base + vOff + 8, dz + q[g + 2]);
            }
            if (cOff != -1) {
                el.putFloat(base + cOff, q[20]);
                el.putFloat(base + cOff + 4, q[21]);
                el.putFloat(base + cOff + 8, q[22]);
                el.putFloat(base + cOff + 12, q[23]);
            }
            if (uvOff != -1) {
                el.putFloat(base + uvOff, q[g + 3]);
                el.putFloat(base + uvOff + 4, q[g + 4]);
            }
        }
        el.position(position + 4 * es);
    }

    /** True when the quad was written here (the game's addQuad is skipped); false leaves this quad, with its flushes, to the game. */
    static boolean tryQuad(Object renderer, float[] q) {
        if (!H.ok) return false;
        ByteBuffer el = (ByteBuffer) H.elements.get(renderer);
        ByteBuffer ix = (ByteBuffer) H.indices.get(renderer);
        Object run = H.run.get(renderer);
        if (el == null || ix == null || run == null || (int) H.mode.get(run) != 7) return false;
        int es = (int) H.elementSize.get(renderer), position = el.position();
        if (es <= 0 || ix.limit() - ix.position() < 8) return false;
        int vc = (int) H.vertexCount.get(run);
        if (flushWouldFire(7, vc, position, el.limit(), es, (int) H.numElements.get(renderer))) return false;
        write(el, ix, position, es, (int) H.vertexOffset.get(renderer), (int) H.colorOffset.get(renderer), (int) H.uv1Offset.get(renderer),
            (float) H.dx.get(renderer), (float) H.dy.get(renderer), (float) H.dz.get(renderer), q);
        H.vertexCount.set(run, vc + 4);
        return true;
    }

    /** Back to stock: guard off. */
    static void resetForTest() {
        guard.on = false;
    }
}
