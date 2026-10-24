package pzbutter;

import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Stack;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * playerLosFast: a {@link Stack} for {@code IsoPlayer.lastSpotted} whose {@code contains} / {@code indexOf} ask an identity set instead
 * of searching the vector (the game asks once per seen object and once per spotted object a frame; a horde makes that millions of
 * compares). Same answers as the {@code Stack} for any operation sequence, given that the elements compare by identity (no class under
 * {@code IsoObject} declares {@code equals}).
 *
 * The set is kept in step by the operations the game uses ({@code add}, {@code addElement}, {@code push}, {@code clear},
 * {@code removeAllElements}); every other mutator marks it stale and the next {@code contains} / {@code indexOf} rebuilds it in one
 * pass. As a net for writes through a view created before a rebuild, the set also remembers the {@code modCount} it matches and a
 * mismatch counts as stale. Every override is {@code synchronized}, as {@link java.util.Vector}'s are.
 */
final class SpottedStack<E> extends Stack<E> {
    private static final long serialVersionUID = 1L;
    private static final Object PRESENT = new Object();

    /** Set rebuilds since start (a test counts them: the game's own operations never cause one). */
    static long rebuilds;

    private transient IdentityHashMap<Object, Object> set = new IdentityHashMap<>();
    private transient boolean stale;
    private transient int synced;

    SpottedStack() {}

    SpottedStack(Collection<? extends E> c) {
        for (E e : c) super.addElement(e);
        fill();
    }

    private void fill() {
        set.clear();
        Object[] d = elementData;
        for (int i = 0, n = elementCount; i < n; i++) set.put(d[i], PRESENT);
        stale = false;
        synced = modCount;
    }

    /** The set matches the vector: rebuilt when a mutator marked it stale or a view moved modCount behind its back. */
    private void refresh() {
        if (stale || modCount != synced) {
            fill();
            rebuilds++;
        }
    }

    // ---- kept fresh ----

    @Override
    public synchronized boolean add(E e) {
        boolean fresh = !stale && modCount == synced;
        super.add(e);
        if (fresh) { set.put(e, PRESENT); synced = modCount; }
        return true;
    }

    @Override
    public synchronized void addElement(E e) {
        boolean fresh = !stale && modCount == synced;
        super.addElement(e);
        if (fresh) { set.put(e, PRESENT); synced = modCount; }
    }

    @Override
    public synchronized E push(E e) {
        addElement(e);
        return e;
    }

    @Override
    public synchronized void clear() {
        super.clear();
        set.clear();
        stale = false;
        synced = modCount;
    }

    @Override
    public synchronized void removeAllElements() {
        super.removeAllElements();
        set.clear();
        stale = false;
        synced = modCount;
    }

    // ---- reads ----

    @Override
    public synchronized boolean contains(Object o) {
        refresh();
        return set.containsKey(o);
    }

    @Override
    public synchronized int indexOf(Object o) {
        refresh();
        return set.containsKey(o) ? super.indexOf(o) : -1;
    }

    // ---- marked stale ----

    @Override
    public synchronized void add(int index, E e) { stale = true; super.add(index, e); }

    @Override
    public synchronized void insertElementAt(E e, int index) { stale = true; super.insertElementAt(e, index); }

    @Override
    public synchronized E set(int index, E e) { stale = true; return super.set(index, e); }

    @Override
    public synchronized void setElementAt(E e, int index) { stale = true; super.setElementAt(e, index); }

    @Override
    public synchronized E remove(int index) { stale = true; return super.remove(index); }

    @Override
    public synchronized boolean remove(Object o) { stale = true; return super.remove(o); }

    @Override
    public synchronized boolean removeElement(Object o) { stale = true; return super.removeElement(o); }

    @Override
    public synchronized void removeElementAt(int index) { stale = true; super.removeElementAt(index); }

    @Override
    public synchronized E pop() { stale = true; return super.pop(); }

    @Override
    public synchronized boolean addAll(Collection<? extends E> c) { stale = true; return super.addAll(c); }

    @Override
    public synchronized boolean addAll(int index, Collection<? extends E> c) { stale = true; return super.addAll(index, c); }

    @Override
    public synchronized boolean removeAll(Collection<?> c) { stale = true; return super.removeAll(c); }

    @Override
    public synchronized boolean retainAll(Collection<?> c) { stale = true; return super.retainAll(c); }

    @Override
    public synchronized boolean removeIf(Predicate<? super E> filter) { stale = true; return super.removeIf(filter); }

    @Override
    public synchronized void replaceAll(UnaryOperator<E> op) { stale = true; super.replaceAll(op); }

    @Override
    public synchronized void sort(Comparator<? super E> c) { stale = true; super.sort(c); }

    @Override
    public synchronized void setSize(int size) { stale = true; super.setSize(size); }

    @Override
    protected synchronized void removeRange(int from, int to) { stale = true; super.removeRange(from, to); }

    @Override
    public synchronized Iterator<E> iterator() { stale = true; return super.iterator(); }

    @Override
    public synchronized ListIterator<E> listIterator() { stale = true; return super.listIterator(); }

    @Override
    public synchronized ListIterator<E> listIterator(int index) { stale = true; return super.listIterator(index); }

    @Override
    public synchronized List<E> subList(int from, int to) { stale = true; return super.subList(from, to); }

    // ---- copies ----

    /** A clone gets a set of its own (Object.clone would share the field). */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized Object clone() {
        SpottedStack<E> c = (SpottedStack<E>) super.clone();
        c.set = new IdentityHashMap<>();
        c.stale = true;
        return c;
    }

    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        set = new IdentityHashMap<>();
        stale = true;
    }
}
