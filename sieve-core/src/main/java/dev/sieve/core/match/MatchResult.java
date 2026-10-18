package dev.sieve.core.match;

import dev.sieve.core.model.SanctionedEntity;
import java.util.List;
import java.util.Objects;

/**
 * The result of matching a screening request against a single sanctioned entity.
 *
 * <p>Results are naturally ordered by score in descending order (highest match first).
 *
 * @param entity the matched sanctioned entity
 * @param score match confidence score in the range [0.0, 1.0], where 1.0 is a perfect match
 * @param matchedField identifies which field produced the match (e.g., "primaryName", "alias[2]")
 * @param matchAlgorithm the name of the algorithm that produced this score
 * @param nameScore the score of the name alone, before facts beyond the name were weighed; equal to
 *     {@code score} for a name-only screening
 * @param evidence how each fact beyond the name compared with the record; empty for a name-only
 *     screening
 */
public record MatchResult(
        SanctionedEntity entity,
        double score,
        String matchedField,
        String matchAlgorithm,
        double nameScore,
        List<FieldMatch> evidence)
        implements Comparable<MatchResult> {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any parameter is {@code null}
     * @throws IllegalArgumentException if {@code score} is outside [0.0, 1.0]
     */
    public MatchResult {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(matchedField, "matchedField must not be null");
        Objects.requireNonNull(matchAlgorithm, "matchAlgorithm must not be null");
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("Score must be between 0.0 and 1.0");
        }
        if (nameScore < 0.0 || nameScore > 1.0) {
            throw new IllegalArgumentException("Name score must be between 0.0 and 1.0");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * A result scored on the name alone.
     *
     * @param entity the matched entity
     * @param score the name score
     * @param matchedField the field that produced the match
     * @param matchAlgorithm the algorithm that produced the score
     */
    public MatchResult(
            SanctionedEntity entity, double score, String matchedField, String matchAlgorithm) {
        this(entity, score, matchedField, matchAlgorithm, score, List.of());
    }

    /**
     * Compares match results by score in descending order (highest score first).
     *
     * @param other the other result to compare to
     * @return negative if this result has a higher score, positive if lower, zero if equal
     */
    @Override
    public int compareTo(MatchResult other) {
        return Double.compare(other.score, this.score);
    }
}
