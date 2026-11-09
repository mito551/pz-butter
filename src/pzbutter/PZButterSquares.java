package pzbutter;

import zombie.iso.IsoGridSquare;

/**
 * Java-side masked LoadGridsquare support (QoLC-1, PZ Butter core). Classifies a square against
 * a mask made of MASK_DOOR, MASK_WINDOW, MASK_VEHICLE so a handler can skip the common case.
 */
public final class PZButterSquares {
    private PZButterSquares() {}

    public static final int MASK_DOOR = 1;
    public static final int MASK_WINDOW = 2;
    public static final int MASK_VEHICLE = 4;

    public static boolean on = true;

    static void init() {
        try { on = Settings.enabled; } catch (Throwable t) { on = false; }
    }

    public static boolean want(zombie.iso.IsoGridSquare square, int mask) {
        if (square == null) return false;
        try {
            boolean door = (mask & MASK_DOOR) != 0 && (Boolean) square.getClass().getMethod("HasDoor", boolean.class).invoke(square, Boolean.TRUE);
            boolean window = (mask & MASK_WINDOW) != 0 && (Boolean) square.getClass().getMethod("hasWindowOrWindowFrame").invoke(square);
            boolean vehicle = (mask & MASK_VEHICLE) != 0 && square.getVehicleContainer() != null;
            return door || window || vehicle;
        } catch (Throwable t) {
            // An engine without these bits cannot be filtered by them safely, so run every handler.
            return true;
        }
    }

    public static void run() {
        int m = MASK_DOOR | MASK_WINDOW | MASK_VEHICLE;
        assert (m & MASK_DOOR) != 0 && (m & MASK_VEHICLE) != 0;
    }
}
