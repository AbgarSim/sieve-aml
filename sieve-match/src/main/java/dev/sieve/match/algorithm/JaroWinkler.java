package dev.sieve.match.algorithm;

/**
 * Pure-Java implementation of the Jaro-Winkler string similarity algorithm.
 *
 * <p>Jaro-Winkler is a string metric commonly used in record linkage and name matching. It produces
 * a similarity score between 0.0 (no similarity) and 1.0 (exact match), with a prefix bonus that
 * favors strings sharing a common prefix.
 *
 * <p>This implementation follows the original Winkler (1990) formulation with a default prefix
 * scaling factor of 0.1 and a maximum prefix length of 4 characters.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Jaro%E2%80%93Winkler_distance">Jaro–Winkler distance
 *     (Wikipedia)</a>
 */
public final class JaroWinkler {

    /** Default Winkler prefix scaling factor. */
    private static final double DEFAULT_PREFIX_SCALE = 0.1;

    /** Maximum prefix length considered by the Winkler adjustment. */
    private static final int MAX_PREFIX_LENGTH = 4;

    /** Initial capacity for thread-local reusable arrays. */
    private static final int INITIAL_ARRAY_SIZE = 128;

    /** Thread-local reusable boolean arrays to avoid allocation per call. */
    private static final ThreadLocal<boolean[]> TL_MATCHED_1 =
            ThreadLocal.withInitial(() -> new boolean[INITIAL_ARRAY_SIZE]);

    private static final ThreadLocal<boolean[]> TL_MATCHED_2 =
            ThreadLocal.withInitial(() -> new boolean[INITIAL_ARRAY_SIZE]);

    private JaroWinkler() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Computes the Jaro-Winkler similarity between two strings.
     *
     * <p>Both strings are compared as-is (no normalization is applied). Callers should pre-process
     * strings (e.g., lowercasing, trimming) before invoking this method if case-insensitive
     * comparison is desired.
     *
     * @param s1 the first string, may be {@code null} or empty
     * @param s2 the second string, may be {@code null} or empty
     * @return similarity score in the range [0.0, 1.0]
     */
    public static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }

        double jaroScore = jaro(s1, s2);

        int prefixLength = commonPrefixLength(s1, s2);
        return jaroScore + (prefixLength * DEFAULT_PREFIX_SCALE * (1.0 - jaroScore));
    }

    /**
     * Computes the Jaro-Winkler similarity, returning 0.0 early if it cannot meet the threshold.
     *
     * @param s1 the first string
     * @param s2 the second string
     * @param threshold minimum score; returns 0.0 if the result cannot meet this
     * @return similarity score, or 0.0 if below threshold
     */
    public static double similarityWithThreshold(String s1, String s2, double threshold) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }
        if (s1.equals(s2)) {
            return 1.0;
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }

        // Length-ratio upper bound: Jaro score <= (1 + 1 + min/max) / 3
        int len1 = s1.length();
        int len2 = s2.length();
        int shorter = Math.min(len1, len2);
        int longer = Math.max(len1, len2);
        double maxPossibleJaro = (2.0 + (double) shorter / longer) / 3.0;
        // With maximum Winkler boost (prefix=4)
        double maxPossible =
                maxPossibleJaro
                        + (MAX_PREFIX_LENGTH * DEFAULT_PREFIX_SCALE * (1.0 - maxPossibleJaro));
        if (maxPossible < threshold) {
            return 0.0;
        }

        return similarity(s1, s2);
    }

    /**
     * Computes the Jaro similarity between two strings.
     *
     * @param s1 the first string, must not be {@code null}
     * @param s2 the second string, must not be {@code null}
     * @return Jaro similarity in the range [0.0, 1.0]
     */
    static double jaro(String s1, String s2) {
        int s1Len = s1.length();
        int s2Len = s2.length();

        if (s1Len == 0 && s2Len == 0) {
            return 1.0;
        }

        int matchDistance = Math.max(s1Len, s2Len) / 2 - 1;
        if (matchDistance < 0) {
            matchDistance = 0;
        }

        // Reuse thread-local arrays to avoid allocation per call
        boolean[] s1Matched = borrowArray(TL_MATCHED_1, s1Len);
        boolean[] s2Matched = borrowArray(TL_MATCHED_2, s2Len);

        int matches = 0;
        int transpositions = 0;

        for (int i = 0; i < s1Len; i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, s2Len);

            for (int j = start; j < end; j++) {
                if (s2Matched[j] || s1.charAt(i) != s2.charAt(j)) {
                    continue;
                }
                s1Matched[i] = true;
                s2Matched[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) {
            clearArray(s1Matched, s1Len);
            clearArray(s2Matched, s2Len);
            return 0.0;
        }

        int k = 0;
        for (int i = 0; i < s1Len; i++) {
            if (!s1Matched[i]) {
                continue;
            }
            while (!s2Matched[k]) {
                k++;
            }
            if (s1.charAt(i) != s2.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        // Clean up for reuse
        clearArray(s1Matched, s1Len);
        clearArray(s2Matched, s2Len);

        return (((double) matches / s1Len)
                        + ((double) matches / s2Len)
                        + ((matches - transpositions / 2.0) / matches))
                / 3.0;
    }

    /**
     * Borrows a boolean array from a ThreadLocal, growing it if needed. The returned array is
     * guaranteed to have at least {@code size} elements, all false.
     */
    private static boolean[] borrowArray(ThreadLocal<boolean[]> tl, int size) {
        boolean[] arr = tl.get();
        if (arr.length < size) {
            arr = new boolean[size];
            tl.set(arr);
        }
        return arr;
    }

    /** Clears the first {@code length} elements of the array for reuse. */
    private static void clearArray(boolean[] arr, int length) {
        java.util.Arrays.fill(arr, 0, length, false);
    }

    /**
     * Returns the length of the common prefix between two strings, up to {@link
     * #MAX_PREFIX_LENGTH}.
     */
    private static int commonPrefixLength(String s1, String s2) {
        int maxLen = Math.min(Math.min(s1.length(), s2.length()), MAX_PREFIX_LENGTH);
        int prefixLength = 0;
        for (int i = 0; i < maxLen; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefixLength++;
            } else {
                break;
            }
        }
        return prefixLength;
    }
}
