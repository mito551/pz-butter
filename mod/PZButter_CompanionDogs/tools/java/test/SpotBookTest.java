import cd.perf.SpotBook;

public class SpotBookTest {
    public static void main(String[] args) {
        SpotBook<Object, Object> book = new SpotBook<>();
        Object k = new Object();

        // Unwatched keys record nothing, and begin says so.
        assert !book.begin(k, 100L) : "begin on an unwatched key returns false";
        book.add(k, "x");
        assert book.get(k) == null : "unwatched records nothing";

        // Watch and record.
        book.watch(k, true);
        assert book.begin(k, 200L) : "begin on a watched key returns true";
        book.add(k, "a");
        book.add(k, "b");
        assert book.get(k) != null && book.get(k).size() == 2 : "two values";

        // begin clears previous values.
        book.begin(k, 300L);
        assert book.get(k) != null && book.get(k).size() == 0 : "begin clears";

        // 129th add dropped.
        for (int i = 0; i < 130; i++) book.add(k, i);
        assert book.get(k).size() == 128 : "cap 128";

        // stamp returns begin time.
        assert book.stamp(k) == 300L : "stamp";

        // watch false makes get null.
        book.watch(k, false);
        assert book.get(k) == null : "unwatched after false";

        System.out.println("SpotBookTest OK");
    }
}
