package pzbutter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * {@code IsoGridSquare.getObjects()} on every game build PZ Butter runs on. The early 42.x builds return a
 * {@code zombie.util.list.PZArrayList} (with {@code getElements()}), later 42.22 builds a plain {@code java.util.List}; a call site compiled
 * against one fails with NoSuchMethodError on the other, because the descriptor carries the return type. The calls here are bound once from the
 * method's actual return type, through {@code static final} MethodHandles (which the JIT inlines), so one jar runs on both. The methods throw
 * nothing checked: a failure surfaces as the original exception and lands in the caller's guard.
 */
final class GridObjects {
    private GridObjects() {}

    /** The list calls bound to one return type; {@code elements} is null when the type has no getElements. */
    record Api(MethodHandle of, MethodHandle size, MethodHandle get, MethodHandle isEmpty, MethodHandle elements) {}

    private static final MethodType OBJ_OBJ = MethodType.methodType(Object.class, Object.class);

    /** Binds to {@code owner.getObjects()} and to the size/get/isEmpty (and getElements, when there is one) of whatever type it returns. */
    static Api bind(Class<?> owner) throws ReflectiveOperationException {
        MethodHandles.Lookup l = MethodHandles.lookup();
        Method m = owner.getMethod("getObjects");
        Class<?> rt = m.getReturnType();
        MethodHandle of = l.unreflect(m).asType(OBJ_OBJ);
        MethodHandle size = l.findVirtual(rt, "size", MethodType.methodType(int.class))
                .asType(MethodType.methodType(int.class, Object.class));
        MethodHandle get = l.findVirtual(rt, "get", MethodType.methodType(Object.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class));
        MethodHandle isEmpty = l.findVirtual(rt, "isEmpty", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        MethodHandle elements = null;
        try {
            elements = l.findVirtual(rt, "getElements", MethodType.methodType(Object[].class))
                    .asType(MethodType.methodType(Object[].class, Object.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            // a plain List: callers use get
        }
        return new Api(of, size, get, isEmpty, elements);
    }

    private static final Api API = load();

    private static Api load() {
        try {
            return bind(zombie.iso.IsoGridSquare.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final MethodHandle OF = API == null ? null : API.of();
    private static final MethodHandle SIZE = API == null ? null : API.size();
    private static final MethodHandle GET = API == null ? null : API.get();
    private static final MethodHandle IS_EMPTY = API == null ? null : API.isEmpty();
    private static final MethodHandle ELEMENTS = API == null ? null : API.elements();

    /** True when the binding to the running game worked. */
    static boolean bound() { return API != null; }

    /** True when the list type has getElements (then {@link #elements} answers the backing array). */
    static boolean hasElements() { return ELEMENTS != null; }

    /** The square's object list, as the game's own type. */
    static Object of(Object square) {
        try { return (Object) OF.invokeExact(square); } catch (Throwable t) { throw GridObjects.<RuntimeException>sneaky(t); }
    }

    static int size(Object list) {
        try { return (int) SIZE.invokeExact(list); } catch (Throwable t) { throw GridObjects.<RuntimeException>sneaky(t); }
    }

    static Object get(Object list, int i) {
        try { return (Object) GET.invokeExact(list, i); } catch (Throwable t) { throw GridObjects.<RuntimeException>sneaky(t); }
    }

    static boolean isEmpty(Object list) {
        try { return (boolean) IS_EMPTY.invokeExact(list); } catch (Throwable t) { throw GridObjects.<RuntimeException>sneaky(t); }
    }

    /** The backing array (valid up to size), or null when the list type has none; only call it when {@link #hasElements}. */
    static Object[] elements(Object list) {
        try { return (Object[]) ELEMENTS.invokeExact(list); } catch (Throwable t) { throw GridObjects.<RuntimeException>sneaky(t); }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T { throw (T) t; }
}
