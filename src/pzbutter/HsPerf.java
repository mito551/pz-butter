package pzbutter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the JVM's own perfdata file (/tmp/hsperfdata_&lt;user&gt;/&lt;pid&gt;) for the safepoint counters. Layout as this JDK
 * writes it: a 32-byte prologue (magic 0xcafec0c0 big-endian, entry count at 24) and 20-byte entry headers (length,
 * name offset, ..., data offset at 16); names are NUL-terminated in the ASCII byte order the header states. A damaged
 * file is an empty map, never a wrong number.
 */
final class HsPerf {
    private HsPerf() {}

    static final String SAFEPOINTS = "sun.rt.safepoints", SAFEPOINT_TIME = "sun.rt.safepointTime", SAFEPOINT_SYNC = "sun.rt.safepointSyncTime";
    private static final int MAGIC = 0xcafec0c0;
    // ponytail: the prologue size this JDK writes; a perfdata version change shows up as an empty map (the probe then prints n/a).
    private static final int HEADER = 32;

    static Map<String, Long> read(ByteBuffer in) {
        Map<String, Long> out = new HashMap<>();
        try {
            if (in.limit() < HEADER || in.order(ByteOrder.BIG_ENDIAN).getInt(0) != MAGIC) return out;
            in.order(in.get(4) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            int count = in.getInt(24);
            int pos = HEADER;
            for (int i = 0; i < count && pos + 20 <= in.limit(); i++) {
                int length = in.getInt(pos);
                if (length <= 0) break;
                String name = name(pos + in.getInt(pos + 4), in);
                int dataAt = pos + in.getInt(pos + 16);
                if ((SAFEPOINTS.equals(name) || SAFEPOINT_TIME.equals(name) || SAFEPOINT_SYNC.equals(name))
                        && dataAt >= 0 && dataAt + 8 <= in.limit()) {
                    out.put(name, in.getLong(dataAt));
                }
                pos += length;
            }
        } catch (Throwable t) {
            return Map.of();
        }
        return out;
    }

    private static String name(int at, ByteBuffer in) {
        StringBuilder sb = new StringBuilder();
        for (int i = at; i >= 0 && i < in.limit() && i < at + 128; i++) {
            byte b = in.get(i);
            if (b == 0) break;
            sb.append((char) (b & 0xff));
        }
        return sb.toString();
    }
}
