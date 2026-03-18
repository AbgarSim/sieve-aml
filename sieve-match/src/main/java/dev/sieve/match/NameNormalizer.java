package dev.sieve.match;

/**
 * Utility for normalizing name strings before comparison.
 *
 * <p>Applied consistently across all match engines to ensure comparable inputs.
 */
final class NameNormalizer {

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
        return name.strip().toLowerCase().replaceAll("\\s+", " ");
    }
}
