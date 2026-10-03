package dev.sieve.match;

/**
 * Scoring rule for matches that cover only part of a listed name.
 *
 * <p>A query such as "Vladimir" can match the given name of thousands of listed people. Treating
 * that as a full-name hit floods results with false positives, so a match is discounted by {@link
 * #FACTOR} when:
 *
 * <ul>
 *   <li>it was made against a single name component (given or family name) rather than a full
 *       primary name or alias, or
 *   <li>a single-token query was compared against a full name that has more than one token.
 * </ul>
 *
 * <p>With the default factor a perfect partial match scores 0.75, below the default screening
 * threshold of 0.80, so lone names only surface when the caller lowers the threshold.
 */
final class PartialNameMatch {

    /** Multiplier applied to the score of a partial-name match. */
    static final double FACTOR = 0.75;

    private PartialNameMatch() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /**
     * Discounts a score for a partial-name match.
     *
     * @param score the raw similarity score
     * @return the discounted score
     */
    static double discount(double score) {
        return score * FACTOR;
    }

    /**
     * Returns whether comparing the query against the given full name is a partial match, meaning
     * the query is one token and the name has more than one.
     *
     * @param queryTokenCount number of tokens in the normalized query
     * @param normalizedName the normalized full name (primary name or alias)
     * @return {@code true} if the comparison only covers part of the name
     */
    static boolean isLoneToken(int queryTokenCount, String normalizedName) {
        return queryTokenCount == 1 && tokenCount(normalizedName) > 1;
    }

    /**
     * Counts the tokens in a normalized name, treating whitespace, commas and hyphens as
     * separators, consistent with {@link TokenMatchEngine#tokenize(String)}.
     *
     * @param normalizedName the normalized name, may be {@code null}
     * @return the number of tokens
     */
    static int tokenCount(String normalizedName) {
        if (normalizedName == null) {
            return 0;
        }
        int count = 0;
        boolean inToken = false;
        for (int i = 0; i < normalizedName.length(); i++) {
            char c = normalizedName.charAt(i);
            boolean separator = Character.isWhitespace(c) || c == ',' || c == '-';
            if (!separator && !inToken) {
                count++;
            }
            inToken = !separator;
        }
        return count;
    }
}
