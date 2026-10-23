package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

/**
 * Access to private game members. The game and the mod share the system class loader's unnamed module.
 * Each lookup returns null (with one log line) when the member is not there.
 */
final class Game {
    private Game() {}

    static VarHandle staticLong(Class<?> owner, String field) { return staticField(owner, field, long.class); }

    static VarHandle staticField(Class<?> owner, String field, Class<?> type) {
        try { return lookup(owner).findStaticVarHandle(owner, field, type); }
        catch (Throwable t) { return missing(owner, field, t); }
    }

    static VarHandle field(Class<?> owner, String field, Class<?> type) {
        try { return lookup(owner).findVarHandle(owner, field, type); }
        catch (Throwable t) { return missing(owner, field, t); }
    }

    static MethodHandle method(Class<?> owner, String name, MethodType type) {
        try { return lookup(owner).findVirtual(owner, name, type); }
        catch (Throwable t) { return missing(owner, name, t); }
    }

    private static MethodHandles.Lookup lookup(Class<?> owner) throws IllegalAccessException {
        return MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
    }

    private static <T> T missing(Class<?> owner, String name, Throwable t) {
        System.out.println("[PZButter] no member " + owner.getName() + "." + name + ": " + t);
        return null;
    }
}
