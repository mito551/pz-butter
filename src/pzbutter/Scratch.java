package pzbutter;

import java.lang.invoke.MethodHandle;
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
        final boolean fresh;
        final String init;
        volatile VarHandle handle;
        volatile Object original;
        volatile boolean warned;
        volatile boolean failed;   // no copy path, or the owner field cannot be resolved
        volatile MethodHandle ctor;
        volatile MethodHandle initHandle;

        Entry(String owner, String field, String desc, boolean fresh, String init) {
            this.owner = owner; this.field = field; this.desc = desc; this.fresh = fresh; this.init = init;
        }
    }

    private static volatile Entry[] entries = new Entry[0];
    private static final AtomicInteger copies = new AtomicInteger();
    /** Resolves owner classes (tests point it at their own loader). */
    static volatile ClassLoader loader = ClassLoader.getSystemClassLoader();

    /** Records the list's scratch fields by k; loads no class. */
    static void init(WorkerList list) {
        int n = 0;
        for (WorkerList.Scratch s : list.scratch()) n = Math.max(n, s.k() + 1);
        for (WorkerList.Fresh f : list.fresh()) n = Math.max(n, f.k() + 1);
        Entry[] e = new Entry[n];
        for (WorkerList.Scratch s : list.scratch()) if (e[s.k()] == null) e[s.k()] = new Entry(s.owner(), s.field(), s.desc(), false, null);
        for (WorkerList.Fresh f : list.fresh()) if (e[f.k()] == null) e[f.k()] = new Entry(f.owner(), f.field(), f.desc(), true, f.init());
        copies.set(0);
        entries = e;
    }

    /** Copies made so far, for the status line. */
    static int copies() { return copies.get(); }

    /** Entries whose copy failed or whose field could not be resolved: a worker reading those shares the original. */
    static int uncopyable() {
        int n = 0;
        for (Entry e : entries) if (e != null && e.failed) n++;
        return n;
    }

    /**
     * Resolves every entry and makes one trial copy of each; call it on the game thread after the game classes load,
     * never from {@link #init}. Returns "" when all succeed, else the first failure as {@code owner.field: reason}.
     */
    static String probe() {
        String first = "";
        for (Entry e : entries) {
            if (e == null) continue;
            String why = null;
            try {
                if (e.fresh) {
                    makeFresh(e);
                } else {
                    Object o = e.original != null ? e.original : resolve(e);
                    if (o == null) why = "unresolved";
                    else makeCopy(o);
                }
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
            Entry e = (k >= 0 && k < es.length) ? es[k] : null;
            if (e == null) return null;
            Object o = e.original;
            if (o == null) o = resolve(e);
            if (!(Thread.currentThread() instanceof LuaGate.Worker w)) return o;
            if (o == null && !e.fresh) return o;
            Object[] mine = w.scratch;
            if (mine == null || mine.length != es.length) w.scratch = mine = new Object[es.length];
            Object c = mine[k];
            if (c == null) {
                if (e.fresh) {
                    try { c = makeFresh(e); copies.incrementAndGet(); }
                    catch (Throwable t) { e.failed = true; warn(e, t); c = o; }
                } else {
                    c = copyOf(e, o);
                }
                mine[k] = c;
            }
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

    private static Object makeFresh(Entry e) throws Exception {
        synchronized (e) {
            if (e.failed) throw new IllegalStateException("failed");
            if (e.ctor == null) {
                String desc = e.desc;
                String typeName = desc.startsWith("L") && desc.endsWith(";") ? desc.substring(1, desc.length() - 1) : desc;
                Class<?> owner = Class.forName(typeName.replace('/', '.'), false, Scratch.loader);
                MethodHandles.Lookup priv = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
                e.ctor = priv.findConstructor(owner, MethodType.methodType(void.class));
                if (e.init != null) e.initHandle = priv.findVirtual(owner, e.init, MethodType.methodType(void.class));
            }
        }
        Object o;
        try { o = e.ctor.invoke(); } catch (Throwable t) { throw new Exception(t); }
        MethodHandle ih = e.initHandle;
        try { if (ih != null) ih.invoke(o); } catch (Throwable t) { throw new Exception(t); }
        return o;
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
