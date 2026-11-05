package pzbutter;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Hosted server child JVM (Plan 15). CoopMaster builds its command line locally;
 * the single reachable slot for an extra argument is the getGarbageCollector()
 * string, inserted after -Xms/-Xmx.
 */
public final class HostPatches {

    private HostPatches() {}

    @Patch(className = "zombie.network.CoopMaster", methodName = "getGarbageCollector")
    public static class Args {
        @Patch.OnExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Patch.Return(readOnly = false) String ret) {
            String add = HostLaunch.hostArg();
            if (add != null) ret = add;
        }
    }
}
