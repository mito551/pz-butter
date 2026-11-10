import cpd.perf.Toggles;

import java.lang.reflect.Field;
import java.util.Map;

public class TogglesTest {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Field field = Toggles.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        Map<Object, Boolean> pending = (Map<Object, Boolean>) field.get(null);
        synchronized (pending) {
            pending.clear();
        }
        try {
            for (int i = 0; i < 4097; i++) Toggles.record(new Object());
            if (pending.size() != 4097) {
                throw new AssertionError("expected 4097 pending doors, got " + pending.size());
            }
        } finally {
            synchronized (pending) {
                pending.clear();
            }
        }
        System.out.println("TogglesTest OK");
    }
}
