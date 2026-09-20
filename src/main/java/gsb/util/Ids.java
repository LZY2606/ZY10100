package gsb.util;

import java.security.SecureRandom;
import java.time.Instant;

/** Short, time-ordered, collision-resistant identifiers. */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private Ids() {}

    public static String create(String prefix) {
        long t = Instant.now().toEpochMilli();
        StringBuilder sb = new StringBuilder(prefix).append('_');
        appendB36(sb, t);
        sb.append('-');
        byte[] r = new byte[5];
        RANDOM.nextBytes(r);
        for (byte b : r) {
            sb.append(ALPHABET[b & 31]);
        }
        return sb.toString();
    }

    private static void appendB36(StringBuilder sb, long v) {
        String s = Long.toString(v, 36);
        sb.append(s);
    }
}
