package pzbutter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Access to private game statics. The game and the mod share the system class loader's unnamed module. */
final class Game {
    private Game() {}

    /** A VarHandle on a private static long, or null (with one log line) when it is not there. */
    static VarHandle staticLong(Class<?> owner, String field) {
        try {
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findStaticVarHandle(owner, field, long.class);
        } catch (Throwable t) {
            System.out.println("[PZButter] no field " + owner.getName() + "." + field + ": " + t);
            return null;
        }
    }
}
