package dev.sieve.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds record ids for lists that publish none, from a hash of fields that stay the same while the
 * record does. Repeats of the same key get a {@code -2}, {@code -3} suffix in file order.
 */
public final class StableIds {

    private final String prefix;
    private final Map<String, Integer> seen = new HashMap<>();

    /**
     * Creates an id builder for one list read.
     *
     * @param prefix the id prefix, such as {@code nl-}
     */
    public StableIds(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Returns the id for a record, from its key fields; case and surrounding space do not count.
     *
     * @param parts the fields that identify the record; {@code null} counts as empty
     * @return the prefix, 12 hex digits of the key's SHA-256 and a suffix for repeats
     */
    public String next(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            key.append(part == null ? "" : part.strip().toLowerCase(Locale.ROOT)).append('|');
        }
        String hash = hash(key.toString());
        int count = seen.merge(hash, 1, Integer::sum);
        return prefix + hash + (count > 1 ? "-" + count : "");
    }

    private static String hash(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
