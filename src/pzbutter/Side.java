package pzbutter;

import java.lang.invoke.VarHandle;

/**
 * Which side of the game this JVM is. A dedicated or hosted server runs the same jar as a client
 * (media/java/common), so every feature asks here before it reads its key.
 *
 * <p>Read live, not cached at startup: a multiplayer client only sets GameClient.client after the
 * mod has loaded. The static reads use VarHandles so looking at a game class never initialises it.
 */
public final class Side {
    /** Keys that only make sense in the server process. */
    private static final java.util.Set<String> SERVER = java.util.Set.of(
            "serverTickLog", "serverLuaSample",
            "containerSendGuard", "processItemsMerge",
            "serverPrewarm", "serverClassPrewarm", "vehicleZoneKey", "serverCellSpread",
            "serverNioPool", "serverUdpBuffers", "serverPathfinding", "serverSqliteAsyncSave");

    /** Keys that run on both sides: the master switch, the frame log and the loader fixes. */
    private static final java.util.Set<String> BOTH = java.util.Set.of(
            "enabled", "frameLog", "modCompat", "loaderCpuFixes", "scriptParserFast", "modWatchAsync",
            "handlerCensus");

    private static final java.util.Set<String> told = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile boolean testSet, testServer, testMpClient;
    private static volatile boolean serverResolved, clientResolved;
    private static volatile VarHandle serverFlag, clientFlag;

    private Side() {}

    /** True in a dedicated or hosted server process. */
    public static boolean server() {
        if (testSet) return testServer;
        return read(serverHandle());
    }

    /** True in a multiplayer client process (connected or not); false on a server and in single player. */
    public static boolean mpClient() {
        if (testSet) return testMpClient;
        return read(clientHandle());
    }

    /**
     * Whether a key may be read on this side. Client keys are inert on a server and server keys are
     * inert on a client; one line says so the first time a suppressed key is asked for.
     */
    public static boolean allowed(String key) {
        boolean server = server();
        boolean ok = SERVER.contains(key) ? server : BOTH.contains(key) || !server;
        if (!ok && told.add(key)) System.out.println("[PZButter] " + key + ": off (" + (server ? "server" : "client") + ")");
        return ok;
    }

    /** Test seam: answers both side questions without a game process. */
    static void forTest(boolean server, boolean mpClient) {
        testServer = server;
        testMpClient = mpClient;
        testSet = true;
        told.clear();
    }

    /** Back to the live game flags. */
    static void resetForTest() {
        testSet = false;
        told.clear();
    }

    private static VarHandle serverHandle() {
        if (!serverResolved) {
            serverFlag = flag("zombie.network.GameServer", "server");
            serverResolved = true;
        }
        return serverFlag;
    }

    private static VarHandle clientHandle() {
        if (!clientResolved) {
            clientFlag = flag("zombie.network.GameClient", "client");
            clientResolved = true;
        }
        return clientFlag;
    }

    private static VarHandle flag(String className, String field) {
        try {
            Class<?> c = Class.forName(className, false, Side.class.getClassLoader());
            return Game.staticField(c, field, boolean.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean read(VarHandle h) {
        if (h == null) return false;
        try { return Boolean.TRUE.equals(h.get()); } catch (Throwable t) { return false; }
    }
}
