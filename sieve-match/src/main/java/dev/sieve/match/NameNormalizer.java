package dev.sieve.match;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for normalizing name strings before comparison.
 *
 * <p>Applied consistently across all match engines to ensure comparable inputs.
 * Results are cached to avoid repeated regex and string operations for the same input.
 */
final class NameNormalizer {

    private static final int MAX_CACHE_SIZE = 100_000;
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    /** Pre-compiled pattern for collapsing whitespace — avoids regex recompilation. */
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    private NameNormalizer() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Normalizes a name string for matching: lowercases, trims, and collapses internal whitespace.
     *
     * @param name the name to normalize, may be {@code null}
     * @return the normalized name, or an empty string if input is {@code null} or blank
     */
    static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        String normalized = WHITESPACE.matcher(name.strip().toLowerCase()).replaceAll(" ");
        if (CACHE.size() < MAX_CACHE_SIZE) {
            CACHE.put(name, normalized);
        }
        return normalized;
    }
}
