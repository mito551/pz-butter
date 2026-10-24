package pzbutter;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * vboBatchKb: the game's {@code VBORenderer} (lines, shapes, the world map, fog, rain) fills 4 KB of vertices and 1 KB of indices
 * before it flushes, so every batch is two uploads and a draw. Ours replaces the two buffers after the first {@code setFormat} that
 * finds them empty, with kb KB of vertices and 16-bit indices for the smallest stride (28 bytes: at most 65,536 vertices, so 1,792 KB
 * is the cap), and rewrites {@code numElements} so isFull and hasRoomFor follow the new size. Stock buffers are never written to, and a
 * batch that is part-filled when the format changes keeps its buffers. The buffers are touched only where the instance is used, which
 * is the render thread, so there is no thread test.
 */
final class BatchSize {
    private BatchSize() {}

    static final Guard guard = new Guard("vboBatchKb");

    /** The batch size in KB; 0 = the game's 4 KB (nothing installed). */
    static int kb;

    /** Private VBORenderer fields, through Game (bound on first use). */
    private static VarHandle elementsH, indicesH, elementSizeH, numElementsH;
    private static volatile boolean bound;

    static void init() {
        int v = Settings.enabled ? Settings.num("vboBatchKb", 0, 0, 1792) : 0;
        kb = v == 0 ? 0 : Math.max(8, v);
        guard.on = kb > 0;
    }

    /** Bytes of vertices for a batch of kb KB. */
    static int elementBytes(int kb) { return kb << 10; }

    /** Bytes of 16-bit indices: one per vertex at the smallest stride (28), plus the two flush writes at 0 and 2. */
    static int indexBytes(int kb) { return 2 * ((kb << 10) / 28) + 16; }

    private static void bind() {
        Class<?> c = zombie.core.opengl.VBORenderer.class;
        elementsH = Game.field(c, "elements", ByteBuffer.class);
        indicesH = Game.field(c, "indices", ByteBuffer.class);
        elementSizeH = Game.field(c, "elementSize", int.class);
        numElementsH = Game.field(c, "numElements", int.class);
        bound = true;
    }

    /** The setFormat exit on one VBORenderer: installs the wider buffers when the batch is empty, then keeps numElements at their size. */
    static void afterSetFormat(Object renderer) {
        if (!bound) bind();
        ByteBuffer el = (ByteBuffer) elementsH.get(renderer);
        ByteBuffer ix = (ByteBuffer) indicesH.get(renderer);
        int stride = (int) elementSizeH.get(renderer);
        if (el == null || ix == null || stride <= 0) return;
        if (el.capacity() != elementBytes(kb) && el.position() == 0 && ix.position() == 0) {
            el = ByteBuffer.allocateDirect(elementBytes(kb)).order(ByteOrder.nativeOrder());
            ix = ByteBuffer.allocateDirect(indexBytes(kb)).order(ByteOrder.nativeOrder());
            elementsH.set(renderer, el);
            indicesH.set(renderer, ix);
        }
        if (el.capacity() == elementBytes(kb)) numElementsH.set(renderer, el.capacity() / stride);
    }

    /** Back to stock: kb 0 and the guard off. */
    static void resetForTest() {
        kb = 0;
        guard.on = false;
    }
}
