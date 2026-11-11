package pzbutter;

import java.util.ArrayList;
import java.util.List;

/** GridObjects: IsoGridSquare.getObjects() as a PZArrayList-like class (early builds) and as a plain List (later 42.22 builds). */
final class GridObjectsTest {
    private GridObjectsTest() {}

    /** The early-build shape: not a java.util.List, with a backing array. */
    public static final class OldList {
        private final Object[] elements;
        private final int n;
        OldList(int n, Object... e) { this.n = n; this.elements = e; }
        public int size() { return n; }
        public Object get(int i) { return elements[i]; }
        public boolean isEmpty() { return n == 0; }
        public Object[] getElements() { return elements; }
    }

    public static final class OldSquare {
        final OldList list = new OldList(2, "a", "b", null);   // capacity 3, two used
        public OldList getObjects() { return list; }
    }

    public static final class NewSquare {
        final List<Object> list = new ArrayList<>(List.of("x", "y", "z"));
        public List<Object> getObjects() { return list; }
    }

    static void run() throws Exception {
        try {
            body();
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private static void body() throws Throwable {
        // the early shape: the backing array is there and size bounds it
        OldSquare os = new OldSquare();
        GridObjects.Api old = GridObjects.bind(OldSquare.class);
        Object ol = old.of().invoke((Object) os);
        assert ol == os.list : "of answers the square's own list";
        assert (int) old.size().invoke(ol) == 2 : "size";
        assert "b".equals(old.get().invoke(ol, 1)) : "get";
        assert !(boolean) old.isEmpty().invoke(ol) : "isEmpty";
        assert old.elements() != null && ((Object[]) old.elements().invoke(ol)).length == 3 : "the early shape has its backing array";

        // the later shape: a plain List, no getElements
        NewSquare ns = new NewSquare();
        GridObjects.Api nw = GridObjects.bind(NewSquare.class);
        Object nl = nw.of().invoke((Object) ns);
        assert nl == ns.list : "of answers the square's own list";
        assert (int) nw.size().invoke(nl) == 3 : "size";
        assert "z".equals(nw.get().invoke(nl, 2)) : "get";
        assert !(boolean) nw.isEmpty().invoke(nl) : "isEmpty";
        ns.list.clear();
        assert (boolean) nw.isEmpty().invoke(nl) : "isEmpty follows the list";
        assert nw.elements() == null : "a plain List has no backing array, callers use get";

        // a getObjects the adapter cannot use is refused, not guessed
        boolean refused = false;
        try { GridObjects.bind(Object.class); } catch (ReflectiveOperationException e) { refused = true; }
        assert refused : "no getObjects, no binding";

        // the running game: the binding resolves, and (on a build whose getObjects returns a List) the static calls work on a real square
        assert GridObjects.bound() : "bound to this game's IsoGridSquare.getObjects";
        Class<?> rt = zombie.iso.IsoGridSquare.class.getMethod("getObjects").getReturnType();
        assert GridObjects.hasElements() == hasGetElements(rt) : "the array fast path exists exactly when the type has getElements";
        if (List.class.isAssignableFrom(rt)) {
            java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object sq = ((sun.misc.Unsafe) uf.get(null)).allocateInstance(zombie.iso.IsoGridSquare.class);
            java.lang.reflect.Field f = zombie.iso.IsoGridSquare.class.getDeclaredField("objects");
            f.setAccessible(true);
            List<Object> real = new ArrayList<>(List.of("p", "q"));
            f.set(sq, real);
            Object l = GridObjects.of(sq);
            assert l == real && GridObjects.size(l) == 2 && "q".equals(GridObjects.get(l, 1)) && !GridObjects.isEmpty(l) : "static calls on a real square";
            real.clear();
            assert GridObjects.isEmpty(l) && GridObjects.size(l) == 0 : "and they follow the list";
        }
        System.out.println("[test] GridObjects: bound to " + rt.getName());
    }

    private static boolean hasGetElements(Class<?> t) {
        try { t.getMethod("getElements"); return true; } catch (NoSuchMethodException e) { return false; }
    }
}
