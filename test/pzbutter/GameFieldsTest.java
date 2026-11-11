package pzbutter;

import java.lang.invoke.MethodHandle;

/** GameFields: an int field read at run time, whether the game declares it as a live field or as a constant. */
final class GameFieldsTest {
    private GameFieldsTest() {}

    public static int live = 1;                 // a plain field, as Core.tileScale is on some builds
    public static final int CONSTANT = 7;       // a constant, as it is on others

    static void run() throws Exception {
        MethodHandle liveH = GameFields.getter(GameFieldsTest.class, "live");
        assert GameFields.read(liveH, -1) == 1 : "a live field reads its value";
        live = 3;
        assert GameFields.read(liveH, -1) == 3 : "and follows it (a baked-in constant would keep 1)";
        live = 1;
        assert GameFields.read(GameFields.getter(GameFieldsTest.class, "CONSTANT"), -1) == 7 : "a constant reads too";
        assert GameFields.getter(GameFieldsTest.class, "nope") == null : "no such field: no handle";
        assert GameFields.read(null, 42) == 42 : "no handle: the fallback";

        // the running game's own fields, whichever way this build declares them
        assert GameFields.tileScale() == zombie.core.Core.class.getField("tileScale").getInt(null) : "tileScale matches the game";
        assert GameFields.pixelsPerLevel() == zombie.iso.fboRenderChunk.FBORenderChunk.class.getField("PIXELS_PER_LEVEL").getInt(null)
            : "PIXELS_PER_LEVEL matches the game";
    }
}
