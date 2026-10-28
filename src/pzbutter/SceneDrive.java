package pzbutter;

/**
 * Dev: the scene action that drives a spawned car toward route waypoints.
 */
public final class SceneDrive implements SceneDriver.Action {
    public static final int FORWARD = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 4;

    public SceneDrive() { this.driver = new GameDriver(); }

    public SceneDrive(Driver driver) { this.driver = driver; }

    public interface Driver {
        zombie.characters.IsoPlayer player();
        // returns spawned vehicle or null
        Object spawn(zombie.characters.IsoPlayer player, int targetKmh);
        boolean enter(Object vehicle, zombie.characters.IsoPlayer player);
        void hotwire(Object vehicle);
        boolean startEngine(Object vehicle);
        boolean engineRunning(Object vehicle);
        float speed(Object vehicle);
        float heading(Object vehicle);
        zombie.iso.Vector2 position(Object vehicle);
        boolean inWorld(Object vehicle);
        /** Still one of the cell's vehicles (the game drops a car whose chunk it unloads). */
        boolean alive(Object vehicle);
        void holdForward(boolean on);
        void holdLeft(boolean on);
        void holdRight(boolean on);
        boolean dead(zombie.characters.IsoPlayer player);
    }

    public static class Fake implements Driver {
        public zombie.characters.IsoPlayer playerVal = null;
        public Object vehicle = new Object();
        public boolean enterOk = true;
        public boolean engineOk = true;
        public boolean inWorldOk = true;
        public boolean engineRunningOk = true;
        public float speedVal = 0;
        public float headingVal = 0;
        public zombie.iso.Vector2 pos = new zombie.iso.Vector2(0, 0);
        public boolean deadVal = false;
        public boolean heldF, heldL, heldR;
        public zombie.characters.IsoPlayer player() { return playerVal; }
        public Object spawn(zombie.characters.IsoPlayer player, int targetKmh) { return vehicle; }
        public boolean inWorld(Object vehicle) { return inWorldOk; }
        public boolean alive(Object vehicle) { return inWorldOk; }
        public boolean enter(Object vehicle, zombie.characters.IsoPlayer player) { return enterOk; }
        public void hotwire(Object vehicle) { }
        public boolean startEngine(Object vehicle) { return engineOk; }
        public boolean engineRunning(Object vehicle) { return engineRunningOk; }
        public float speed(Object vehicle) { return speedVal; }
        public float heading(Object vehicle) { return headingVal; }
        public zombie.iso.Vector2 position(Object vehicle) { return pos; }
        public void holdForward(boolean on) { heldF = on; }
        public void holdLeft(boolean on) { heldL = on; }
        public void holdRight(boolean on) { heldR = on; }
        public boolean dead(zombie.characters.IsoPlayer player) { return deadVal; }
    }

    // pure steering decision
    public static int steer(double headingDeg, double targetDeg, double speedKmh, int targetKmh) {
        double diff = targetDeg - headingDeg;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        int bits = 0;
        if (speedKmh < targetKmh) bits |= FORWARD;
        if (diff > 10) bits |= LEFT;
        else if (diff < -10) bits |= RIGHT;
        return bits;
    }

    /** The spawned car, for the removeFromWorld diagnostic in DevPatches. */
    public static volatile Object watched;

    private final Driver driver;
    private Object vehicle;
    private int fwdCode, leftCode, rightCode;
    private int[][] route;
    private int originX, originY, originZ;
    private int index;
    private boolean hasRoute;
    private zombie.characters.IsoPlayer player;
    private int targetKmh;
    private long windowStartMs = -1;
    private boolean windowChecked;

    @Override
    public void prepare() {
        try {
            player = driver.player();
            targetKmh = Settings.num("devDriveKmh", 60, 0, 10000);
            route = SceneDriver.parseRoute(Settings.text("devRoute", ""));
            hasRoute = route != null && route.length > 0;
            originX = originY = originZ = 0;
            if (player != null) {
                originX = (int) Math.floor(player.getX());
                originY = (int) Math.floor(player.getY());
                originZ = (int) Math.floor(player.getZ());
            }
            index = 0;

            fwdCode = zombie.core.Core.getInstance().getKeyBinding("Forward").keyValue();
            leftCode = zombie.core.Core.getInstance().getKeyBinding("Left").keyValue();
            rightCode = zombie.core.Core.getInstance().getKeyBinding("Right").keyValue();

            if (player == null) { SceneDriver.end("no player"); return; }

            vehicle = driver.spawn(player, targetKmh);
            if (vehicle == null) { SceneDriver.end("no car: spawn"); return; }
            if (!driver.inWorld(vehicle)) { SceneDriver.end("no car: invalid spot"); return; }
            zombie.iso.Vector2 at = driver.position(vehicle); // route origin = the spawn square
            originX = (int) Math.floor(at.x);
            originY = (int) Math.floor(at.y);
        } catch (Throwable t) { SceneDriver.guard.fail(t); SceneDriver.end("no car: " + t); }
    }

    private long spawnedMs = -1;
    private boolean goneReported;
    private static final java.lang.invoke.VarHandle REMOVED =
        Game.field(zombie.vehicles.BaseVehicle.class, "removedFromWorld", boolean.class);

    /** Diagnostic: the first settle frame the spawned car is no longer one of the cell's vehicles, log its state. */
    @Override
    public void settleFrame(long nowMs) {
        if (vehicle == null || goneReported) return;
        if (spawnedMs < 0) spawnedMs = nowMs;
        if (nowMs - spawnedMs < 500 || driver.alive(vehicle)) return; // a new car joins the cell at the end of its first frame
        goneReported = true;
        if (!(vehicle instanceof zombie.vehicles.BaseVehicle v)) return;
        System.out.println("[PZButter] devScene drive: car gone " + (nowMs - spawnedMs) + " ms after spawn: square " + v.getSquare()
            + ", chunk " + v.getChunk() + ", in cell " + (v.getCell() != null && v.getCell().getVehicles().contains(v))
            + ", removedFromWorld " + (REMOVED == null ? "?" : (boolean) REMOVED.get(v)) + ", at " + v.getX() + "," + v.getY() + "," + v.getZ()
            + ", player at " + (player == null ? null : player.getX() + "," + player.getY()));
    }

    @Override
    public void start() {
        windowStartMs = -1;
        windowChecked = false;
        // enter and start here, a settle phase after the spawn: a car spawned this frame refuses enter()
        try {
            if (vehicle == null) return;
            if (!driver.alive(vehicle)) { SceneDriver.end("no car: removed from world"); return; }
            if (!driver.enter(vehicle, player)) { SceneDriver.end("no car: enter"); return; }
            driver.hotwire(vehicle);
            if (!driver.startEngine(vehicle)) { SceneDriver.end("no car: engine"); return; }
        } catch (Throwable t) { SceneDriver.guard.fail(t); SceneDriver.end("no car: " + t); }
    }

    @Override
    public void frame(long nowMs) {
        if (vehicle == null) return;
        if (windowStartMs < 0) windowStartMs = nowMs;
        if (!windowChecked && nowMs - windowStartMs >= 5000L) {
            windowChecked = true;
            try {
                if (!driver.engineRunning(vehicle) || driver.speed(vehicle) <= 1f) { SceneDriver.end("car not moving"); return; }
            } catch (Throwable t) { SceneDriver.guard.fail(t); SceneDriver.end("car not moving"); return; }
        }
        try {
            if (player != null && driver.dead(player)) { SceneDriver.end("player dead"); return; }
            // waypoint logic
            if (hasRoute && route != null && route.length > 0) {
                zombie.iso.Vector2 p = driver.position(vehicle);
                int wx = originX + route[index][0];
                int wy = originY + route[index][1];
                double ddx = wx - (p == null ? 0 : p.x);
                double ddy = wy - (p == null ? 0 : p.y);
                if (Math.sqrt(ddx * ddx + ddy * ddy) <= 4) {
                    index = SceneWalk.next(index, route.length);
                }
                // target degrees from vehicle heading to waypoint
                double targetDeg = 0;
                if (p != null) {
                    double dx = wx - p.x;
                    double dy = wy - p.y;
                    targetDeg = Math.toDegrees(Math.atan2(dy, dx));
                }
                int bits = steer(driver.heading(vehicle), targetDeg, driver.speed(vehicle), targetKmh);
                driver.holdForward((bits & FORWARD) != 0);
                driver.holdLeft((bits & LEFT) != 0);
                driver.holdRight((bits & RIGHT) != 0);
                // also VirtualKeys for real driver? Game driver handles internally.
            } else {
                // straight ahead: just hold forward by speed
                int bits = steer(driver.heading(vehicle), driver.heading(vehicle), driver.speed(vehicle), targetKmh);
                driver.holdForward((bits & FORWARD) != 0);
                driver.holdLeft(false);
                driver.holdRight(false);
            }
        } catch (Throwable t) { SceneDriver.guard.fail(t); VirtualKeys.releaseAll(); }
    }

    @Override
    public void stop() {
        try { driver.holdForward(false); driver.holdLeft(false); driver.holdRight(false); }
        catch (Throwable t) { SceneDriver.guard.fail(t); }
        VirtualKeys.releaseAll();
    }

    private static final class GameDriver implements Driver {
        public zombie.characters.IsoPlayer player() { return zombie.characters.IsoPlayer.getInstance(); }
        public Object spawn(zombie.characters.IsoPlayer player, int targetKmh) {
            try {
                if (player == null) return null;
                zombie.iso.IsoDirections facing = player.getDir();
                zombie.iso.IsoCell cell = player.getCell();
                if (cell == null && zombie.iso.IsoWorld.instance != null) cell = zombie.iso.IsoWorld.instance.getCell();
                if (cell == null) { System.out.println("[PZButter] devScene drive: no cell"); return null; }
                int sx = (int) Math.floor(player.getX());
                int sy = (int) Math.floor(player.getY());
                int sz = (int) Math.floor(player.getZ());
                zombie.iso.IsoGridSquare sq = cell.getGridSquare(sx + facing.dx() * 3, sy + facing.dy() * 3, sz);
                if (sq == null) { System.out.println("[PZButter] devScene drive: no square at " + (sx + facing.dx() * 3) + "," + (sy + facing.dy() * 3) + "," + sz + " (facing " + facing + ")"); return null; }
                String car = Settings.text("devDriveCar", "Base.CarNormal");
                zombie.vehicles.BaseVehicle v = zombie.Lua.LuaManager.GlobalObject.addVehicleDebug(car, facing, null, sq);
                // a debug spawn has random missing parts (the seat, the battery...); the game's Trailer3 scenarios repair() it
                if (v != null) { v.repair(); watched = v; }
                return v;
            } catch (Throwable t) { System.out.println("[PZButter] devScene drive: spawn: " + t); SceneDriver.guard.fail(t); return null; }
        }
        public boolean enter(Object vehicle, zombie.characters.IsoPlayer player) {
            try {
                boolean ok = ((zombie.vehicles.BaseVehicle) vehicle).enter(0, player);
                if (!ok) {
                    zombie.vehicles.BaseVehicle v = (zombie.vehicles.BaseVehicle) vehicle;
                    zombie.vehicles.VehiclePart seat = v.getPartForSeatContainer(0);
                    System.out.println("[PZButter] devScene drive: enter(0, player) returned false; seat part " + seat
                        + ", seat item " + (seat == null ? null : seat.getInventoryItem())
                        + ", outside position " + v.getPassengerPosition(0, "outside")
                        + ", player in vehicle " + player.getVehicle() + ", seats " + v.getMaxPassengers());
                }
                return ok;
            } catch (Throwable t) { System.out.println("[PZButter] devScene drive: enter: " + t); SceneDriver.guard.fail(t); return false; }
        }
        public void hotwire(Object vehicle) {
            try { ((zombie.vehicles.BaseVehicle) vehicle).setHotwired(true); }
            catch (Throwable t) { SceneDriver.guard.fail(t); }
        }
        public boolean startEngine(Object vehicle) {
            try { ((zombie.vehicles.BaseVehicle) vehicle).tryStartEngine(); return true; }
            catch (Throwable t) { System.out.println("[PZButter] devScene drive: start: " + t); SceneDriver.guard.fail(t); return false; }
        }
        public boolean engineRunning(Object vehicle) {
            try { return ((zombie.vehicles.BaseVehicle) vehicle).isEngineRunning(); }
            catch (Throwable t) { SceneDriver.guard.fail(t); return false; }
        }
        public float speed(Object vehicle) {
            try { return ((zombie.vehicles.BaseVehicle) vehicle).getCurrentSpeedKmHour(); }
            catch (Throwable t) { SceneDriver.guard.fail(t); return 0; }
        }
        public float heading(Object vehicle) {
            try {
                org.joml.Vector3f v = new org.joml.Vector3f();
                ((zombie.vehicles.BaseVehicle) vehicle).getForwardVector(v);
                // map the forward vector's world X/Z plane to iso X/Y: isoX = X, isoY = Z; heading = atan2(isoY, isoX)
                return (float) Math.toDegrees(Math.atan2(v.z, v.x));
            } catch (Throwable t) { SceneDriver.guard.fail(t); return 0; }
        }
        public boolean alive(Object vehicle) {
            try {
                zombie.vehicles.BaseVehicle v = (zombie.vehicles.BaseVehicle) vehicle;
                return v.getSquare() != null && v.getCell() != null && v.getCell().getVehicles().contains(v);
            } catch (Throwable t) { SceneDriver.guard.fail(t); return false; }
        }
        public boolean inWorld(Object vehicle) {
            try { return ((zombie.vehicles.BaseVehicle) vehicle).getSquare() != null; }
            catch (Throwable t) { SceneDriver.guard.fail(t); return false; }
        }
        public zombie.iso.Vector2 position(Object vehicle) {
            try {
                zombie.vehicles.BaseVehicle v = (zombie.vehicles.BaseVehicle) vehicle;
                return new zombie.iso.Vector2(v.getX(), v.getY());
            } catch (Throwable t) { SceneDriver.guard.fail(t); return new zombie.iso.Vector2(0, 0); }
        }
        private int code(String name) {
            try { return zombie.core.Core.getInstance().getKeyBinding(name).keyValue(); }
            catch (Throwable t) { SceneDriver.guard.fail(t); return 0; }
        }
        public void holdForward(boolean on) {
            int c = code("Forward");
            if (on) VirtualKeys.hold(c); else VirtualKeys.release(c);
        }
        public void holdLeft(boolean on) {
            int c = code("Left");
            if (on) VirtualKeys.hold(c); else VirtualKeys.release(c);
        }
        public void holdRight(boolean on) {
            int c = code("Right");
            if (on) VirtualKeys.hold(c); else VirtualKeys.release(c);
        }
        public boolean dead(zombie.characters.IsoPlayer player) {
            try { return player == null || player.isDead(); }
            catch (Throwable t) { SceneDriver.guard.fail(t); return false; }
        }
    }
}
