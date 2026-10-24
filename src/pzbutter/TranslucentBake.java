package pzbutter;

import java.util.IdentityHashMap;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.fboRenderChunk.ObjectRenderLayer;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.objects.IsoMannequin;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;

/**
 * Plan 6 Task 4: translucentTilesInChunkTexture, glassTilesPerFrame, translucentLightsPerFrame. calculateObjectRenderLayer
 * puts every tile with the Translucent flag (IsoSprite.depthFlags & 2) in the per-frame layers Translucent /
 * TranslucentSE; an object that none of stock's reasons to stay per frame (or ours for glass and lights) applies to is
 * moved to MinusFloor / MinusFloorSE so the bake draws it into the chunk texture. Game thread, no locking.
 */
final class TranslucentBake {
    private TranslucentBake() {}

    // Reasons an object stays per frame. FIRE..MANNEQUIN are stock's own reasons (bakeable checks them as one mask).
    static final int TRANS = 1;
    static final int FIRE = 1 << 1, ANIM = 1 << 2, WIND = 1 << 3, TREE = 1 << 4, FX = 1 << 5, WINDOW = 1 << 6,
        DOOR_TRANS = 1 << 7, SOLID = 1 << 8, CURTAIN = 1 << 9, MANNEQUIN = 1 << 10;
    static final int GLASS = 1 << 11, LIGHT = 1 << 12;
    private static final int STAY_MASK = FIRE | ANIM | WIND | TREE | FX | WINDOW | DOOR_TRANS | SOLID | CURTAIN | MANNEQUIN;

    // Flags of stock's isObjectRenderLayer_MinusFloor offsets 122-521 (FIRE is shared with the reasons above).
    static final int FLOORISH = 1 << 13, ALWAYS_DRAW = 1 << 14, BUSHLIKE = 1 << 15, FLATTEN = 1 << 16, WEST_ROOF = 1 << 17,
        CLIMBING_NOT_SOLIDFLOOR = 1 << 18, SE_WALL = 1 << 19, WINDOWISH = 1 << 20, MANNEQUIN_EACH_FRAME = 1 << 21;

    static boolean glassPerFrame, lightsPerFrame;

    static void init() {
        ChunkPatches.translucentGuard.on = Settings.flag("translucentTilesInChunkTexture");
        glassPerFrame = Settings.flag("glassTilesPerFrame");
        lightsPerFrame = Settings.flag("translucentLightsPerFrame");
        spriteBits.clear();
    }

    /** True when the Translucent flag is the only reason the object is per frame (and the key's glass / light rules allow it). */
    static boolean bakeable(int reasons, boolean glassPerFrame, boolean lightsPerFrame) {
        if ((reasons & TRANS) == 0 || (reasons & STAY_MASK) != 0) return false;
        if (glassPerFrame && (reasons & GLASS) != 0) return false;
        return !(lightsPerFrame && (reasons & LIGHT) != 0);
    }

    /** Our rule for glass (no stock flag says "glass"): transparency flags, or a tileset named glass or window. */
    static boolean isGlass(String tilesetName, boolean transparentN, boolean transparentW, boolean wallNTrans, boolean wallWTrans) {
        if (transparentN || transparentW || wallNTrans || wallWTrans) return true;
        if (tilesetName == null) return false;
        String n = tilesetName.toLowerCase(java.util.Locale.ROOT);
        return n.contains("glass") || n.contains("window");
    }

    /** Our rule for lit fixtures: a light flag or switch, or a lighting_ tileset, or a tileset ending in _on. */
    static boolean isLight(String tilesetName, boolean hasLightOnSprite, boolean lightSwitch) {
        if (hasLightOnSprite || lightSwitch) return true;
        if (tilesetName == null) return false;
        String n = tilesetName.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("lighting_") || n.endsWith("_on");
    }

    /**
     * Stock isObjectRenderLayer_MinusFloor offsets 122-521, in order, without the depthFlags test: the result starts true;
     * a mannequin that renders each frame and a bush that is flattened return false at once; the climbing rule overrides
     * the falses before it; the later rules override it.
     */
    static boolean minusFloorRest(int flags, int z, int camZ) {
        if ((flags & MANNEQUIN_EACH_FRAME) != 0) return false;
        boolean result = true;
        if ((flags & FLOORISH) != 0) result = false;
        if ((flags & FIRE) != 0) result = false;
        if (z >= 1000 && (flags & ALWAYS_DRAW) == 0) result = false;
        if ((flags & BUSHLIKE) != 0 && (flags & FLATTEN) != 0) return false;
        if ((flags & WEST_ROOF) != 0 && z == 999 && z == camZ) result = false;
        if ((flags & CLIMBING_NOT_SOLIDFLOOR) != 0) result = true;
        if ((flags & SE_WALL) != 0) result = false;
        if ((flags & WINDOWISH) != 0) result = false;
        return result;
    }

    // GLASS / LIGHT bits per sprite (sprite properties never change after load); identity keys, game thread only.
    private static final IdentityHashMap<IsoSprite, Integer> spriteBits = new IdentityHashMap<>();

    private static int spriteBits(IsoSprite sprite) {
        Integer cached = spriteBits.get(sprite);
        if (cached != null) return cached;
        var props = sprite.getProperties();
        int bits = 0;
        if (isGlass(sprite.tilesetName, props.has(IsoFlagType.transparentN), props.has(IsoFlagType.transparentW),
                    props.has(IsoFlagType.WallNTrans), props.has(IsoFlagType.WallWTrans))) bits |= GLASS;
        if (isLight(sprite.tilesetName, props.has(IsoFlagType.HasLightOnSprite), false)) bits |= LIGHT;
        spriteBits.put(sprite, bits);
        return bits;
    }

    /** The stay-per-frame reasons of an object whose sprite has the Translucent flag (stock's order, cheap tests first). */
    static int reasons(IsoObject obj, IsoSprite sprite) {
        int r = TRANS;
        if (obj instanceof IsoTree) r |= TREE;
        if (obj instanceof IsoFire) r |= FIRE;
        if (obj instanceof IsoWindow) r |= WINDOW;
        if (obj instanceof IsoCurtain) r |= CURTAIN;
        if (obj instanceof IsoMannequin) r |= MANNEQUIN;
        if (obj instanceof IsoDoor d && d.getProperties() != null && d.getProperties().has("doorTrans")) r |= DOOR_TRANS;
        if (sprite.solid || sprite.solidTrans) r |= SOLID;
        if (obj.isAnimating()) r |= ANIM;
        if (obj.getObjectRenderEffects() != null || obj.getObjectRenderEffectsToApply() != null) r |= FX;
        if (Core.getInstance().getOptionDoWindSpriteEffects() && (obj instanceof IsoTree || obj.getWindRenderEffects() != null)) r |= WIND;
        r |= spriteBits(sprite);
        if (obj instanceof IsoLightSwitch) r |= LIGHT;
        return r;
    }

    /** The flags minusFloorRest reads, for an object that already passed bakeable (window, solid, fire and mannequin reasons are out). */
    static int restFlags(IsoObject obj, IsoSprite sprite, IsoGridSquare sq) {
        int f = 0;
        if ((sprite.solidfloor || sprite.renderLayer == 1) && sprite.getProperties().getSlopedSurfaceDirection() == null) f |= FLOORISH;
        if (sprite.alwaysDraw) f |= ALWAYS_DRAW;
        if (sprite.isBush || sprite.canBeRemoved || sprite.attachedFloor) f |= BUSHLIKE;
        if (sq.flattenGrassEtc) f |= FLATTEN;
        IsoObjectType t = sprite.getTileType();
        if (t == IsoObjectType.WestRoofB || t == IsoObjectType.WestRoofM || t == IsoObjectType.WestRoofT) f |= WEST_ROOF;
        if (!sprite.solidfloor) {
            IsoPlayer p = IsoPlayer.getInstance();
            if (p != null && p.isClimbing()) f |= CLIMBING_NOT_SOLIDFLOOR;
        }
        if (sq.isSpriteOnSouthOrEastWall(obj)) f |= SE_WALL;
        return f;
    }

    /**
     * calculateObjectRenderLayer exit: Translucent / TranslucentSE objects that can bake move to the baked layers; every
     * other layer, and every object that cannot bake, keeps the stock answer. (Because bakeable already rules out windows,
     * doorTrans doors, solid sprites, fire and mannequins, stock's WINDOWISH, FIRE and MANNEQUIN_EACH_FRAME branches cannot
     * fire here and are not computed.)
     */
    static ObjectRenderLayer layer(IsoObject obj, ObjectRenderLayer stock) {
        boolean se = stock == ObjectRenderLayer.TranslucentSE;
        if (!se && stock != ObjectRenderLayer.Translucent) return stock;
        IsoSprite sprite = obj.getSprite();
        if (sprite == null || (sprite.depthFlags & 2) == 0) return stock;
        if (!bakeable(reasons(obj, sprite), glassPerFrame, lightsPerFrame)) return stock;
        if (se) return ObjectRenderLayer.MinusFloorSE;
        IsoGridSquare sq = obj.square;
        if (sq == null) return stock;
        int camZ = PZMath.fastfloor(IsoCamera.getCameraCharacterZ());
        return minusFloorRest(restFlags(obj, sprite, sq), sq.z, camZ) ? ObjectRenderLayer.MinusFloor : stock;
    }
}
