package pzbutter;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scratch accessor called by transformed game code as {@code ldc k; invokestatic Scratch.get(I)Object; checkcast T}.
 * A frame worker ({@link LuaGate.Worker}) gets its own copy of the listed static field k, made on its first read and kept
 * in {@code Worker.scratch[k]}; every other thread gets the field's own object. The owner class is resolved on the first
 * read, not at {@link #init}, and the object is cached once it is non-null. Public because game classes call it.
 */
public final class Scratch {
    private Scratch() {}

    private static final class Entry {
        final String owner, field, desc;
        volatile VarHandle handle;
        volatile Object original;
        volatile boolean warned;
        volatile boolean failed;   // no copy path, or the owner field cannot be resolved

        Entry(String owner, String field, String desc) { this.owner = owner; this.field = field; this.desc = desc; }
    }

    private static volatile Entry[] entries = new Entry[0];
    private static final AtomicInteger copies = new AtomicInteger();
    /** Resolves owner classes (tests point it at their own loader). */
    static volatile ClassLoader loader = ClassLoader.getSystemClassLoader();

    /** Records the list's scratch fields by k; loads no class. */
    static void init(WorkerList list) {
        int n = 0;
        for (WorkerList.Scratch s : list.scratch()) n = Math.max(n, s.k() + 1);
        Entry[] e = new Entry[n];
        for (WorkerList.Scratch s : list.scratch()) if (e[s.k()] == null) e[s.k()] = new Entry(s.owner(), s.field(), s.desc());
        copies.set(0);
        entries = e;
    }

    /** Copies made so far, for the status line. */
    static int copies() { return copies.get(); }

    /** Entries whose copy failed or whose field could not be resolved: a worker reading those shares the original. */
    static int uncopyable() {
        int n = 0;
        for (Entry e : entries) if (e.failed) n++;
        return n;
    }

    /**
     * Resolves every entry and makes one trial copy of each; call it on the game thread after the game classes load,
     * never from {@link #init}. Returns "" when all succeed, else the first failure as {@code owner.field: reason}.
     */
    static String probe() {
        String first = "";
        for (Entry e : entries) {
            String why = null;
            try {
                Object o = e.original != null ? e.original : resolve(e);
                if (o == null) why = "unresolved";
                else makeCopy(o);
            } catch (Throwable t) { why = t.toString(); }
            if (why == null) continue;
            e.failed = true;
            if (first.isEmpty()) first = e.owner + "." + e.field + ": " + why;
        }
        return first;
    }

    /** Never throws. Null only when the original cannot be resolved (logged once). */
    public static Object get(int k) {
        try {
            Entry[] es = entries;
            Entry e = es[k];
            Object o = e.original;
            if (o == null) o = resolve(e);
            if (!(Thread.currentThread() instanceof LuaGate.Worker w) || o == null) return o;
            Object[] mine = w.scratch;
            if (mine == null || mine.length != es.length) w.scratch = mine = new Object[es.length];
            Object c = mine[k];
            if (c == null) mine[k] = c = copyOf(e, o);
            return c;
        } catch (Throwable t) {
            return fallback(k, t);
        }
    }

    private static Object fallback(int k, Throwable t) {
        try {
            Entry e = entries[k];
            warn(e, t);
            return e.original;
        } catch (Throwable ignored) { return null; }
    }

    private static Object resolve(Entry e) {
        try {
            VarHandle h = e.handle;
            if (h == null) {
                Class<?> owner = Class.forName(e.owner.replace('/', '.'), false, loader);
                Class<?> type = MethodType.fromMethodDescriptorString("()" + e.desc, loader).returnType();
                e.handle = h = MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findStaticVarHandle(owner, e.field, type);
            }
            Object o = h.get();
            if (o != null) e.original = o;   // null during a class-init cycle: ask again next time
            return o;
        } catch (Throwable t) {
            e.failed = true;
            warn(e, t);
            return null;
        }
    }

    /** An exact shallow copy: arrays and public clone() types are cloned, other value types are rebuilt field by field. */
    private static Object copyOf(Entry e, Object o) {
        try {
            Object r = makeCopy(o);
            copies.incrementAndGet();
            return r;
        } catch (Throwable t) {
            e.failed = true;
            warn(e, t);
            return o;   // stock behaviour: the shared object
        }
    }

    private static Object makeCopy(Object o) throws Exception {
        Class<?> c = o.getClass();
        if (c.isArray()) {
            int n = Array.getLength(o);
            Object r = Array.newInstance(c.getComponentType(), n);
            System.arraycopy(o, 0, r, 0, n);
            return r;
        }
        Method clone = o instanceof Cloneable ? publicClone(c) : null;
        if (clone != null) return clone.invoke(o);
        Object r = c.getConstructor().newInstance();
        for (Class<?> t = c; t != null && t != Object.class; t = t.getSuperclass())
            for (Field f : t.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(r, f.get(o));
            }
        return r;
    }

    private static Method publicClone(Class<?> c) {
        try { return c.getMethod("clone"); } catch (NoSuchMethodException ex) { return null; }
    }

    private static void warn(Entry e, Throwable t) {
        if (e == null || e.warned) return;
        e.warned = true;
        try { System.out.println("[PZButter] scratch " + e.owner + "." + e.field + ": " + t); } catch (Throwable ignored) {}
    }
}
