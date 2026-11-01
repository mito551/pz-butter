package pzbutter;

import java.io.File;
import java.util.Locale;

public final class DriveTrace {
    private DriveTrace() {}

    public static final Guard guard = new Guard("driveTrace");

    private static volatile boolean bound;
    private static java.lang.invoke.VarHandle xfH;
    private static java.lang.invoke.VarHandle vehicleH;
    private static java.lang.invoke.VarHandle gameTimeInstanceH;
    private static java.lang.invoke.VarHandle worldSimInstanceH;
    private static LogSink log;

    static void init() {
        try { guard.on = Settings.enabled && Settings.flag("driveTrace"); }
        catch (Throwable t) { guard.fail(t); guard.on = false; }
    }

    public static String line(long frameNo, long nanoTime,
                              float carX, float carY,
                              float playerX, float playerY,
                              float rightClickXf, float rightClickX,
                              float fpsMultiplier, int bulletFrameNo) {
        return String.format(Locale.ROOT, "%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d",
                frameNo, nanoTime, carX, carY, playerX, playerY, rightClickXf, rightClickX, fpsMultiplier, bulletFrameNo);
    }

    private static boolean bind() {
        if (bound) return xfH != null && vehicleH != null && gameTimeInstanceH != null && worldSimInstanceH != null;
        bound = true;
        xfH = Game.field(zombie.iso.PlayerCamera.class, "rightClickXf", float.class);
        vehicleH = Game.field(zombie.characters.IsoGameCharacter.class, "vehicle", zombie.vehicles.BaseVehicle.class);
        gameTimeInstanceH = Game.staticField(zombie.GameTime.class, "instance", zombie.GameTime.class);
        worldSimInstanceH = Game.staticField(zombie.core.physics.WorldSimulation.class, "instance", zombie.core.physics.WorldSimulation.class);
        if (xfH == null || vehicleH == null || gameTimeInstanceH == null || worldSimInstanceH == null) {
            System.out.println("[PZButter] driveTrace disabled: missing handle");
            guard.on = false;
            return false;
        }
        return true;
    }

    public static void record(Object self) {
        if (!guard.on) return;
        try {
            if (!bind() || !(self instanceof zombie.iso.PlayerCamera cam)) return;
            zombie.characters.IsoPlayer[] ps = zombie.characters.IsoPlayer.players;
            int pi = cam.playerIndex;
            if (pi < 0 || pi >= ps.length) return;
            zombie.characters.IsoPlayer p = ps[pi];
            if (p == null) return;
            zombie.vehicles.BaseVehicle v = (zombie.vehicles.BaseVehicle) vehicleH.get(p);
            if (v == null) return;
            long frameNo = FrameClock.frameNo;
            long now = System.nanoTime();
            float carX = v.getX(), carY = v.getY();
            float playerX = p.getX(), playerY = p.getY();
            float xf = (float) xfH.get(cam);
            float rightClickX = cam.rightClickX;
            zombie.GameTime gt = (zombie.GameTime) gameTimeInstanceH.get();
            float fps = (gt == null) ? 1.0f : gt.fpsMultiplier;
            zombie.core.physics.WorldSimulation ws = (zombie.core.physics.WorldSimulation) worldSimInstanceH.get();
            int bullet = (ws == null) ? 0 : ws.getBulletFrameNo();
            String ln = line(frameNo, now, carX, carY, playerX, playerY, xf, rightClickX, fps, bullet);
            LogSink s = log;
            if (s == null) {
                try {
                    File f = new File(System.getProperty("user.home"), "Zomboid/PZButter/drivetrace.csv");
                    s = new LogSink(f, FrameClock.logLimit);
                    log = s;
                    long e = System.currentTimeMillis();
                    s.begin(e).text("frameNo,nanoTime,carX,carY,playerX,playerY,rightClickXf,rightClickX,fpsMultiplier,bulletFrameNo").end(e);
                } catch (Throwable t) {
                    guard.fail(t);
                    log = null;
                    return;
                }
            }
            long e = System.currentTimeMillis();
            s.begin(e).text(ln).end(e);
        } catch (Throwable t) { guard.fail(t); }
    }

    static void resetForTest() {
        guard.on = false;
        bound = false;
        xfH = null;
        vehicleH = null;
        gameTimeInstanceH = null;
        worldSimInstanceH = null;
        LogSink l = log;
        log = null;
        if (l != null) try { l.close(); } catch (Throwable ignored) { }
    }
}
