package dev.sieve.core.dedup;

/**
 * Configuration parameters for the entity deduplication process.
 *
 * <p>Controls the similarity thresholds and signal weights used to decide when two
 * {@link dev.sieve.core.model.SanctionedEntity} records from different sanctions lists represent
 * the same real-world entity.
 *
 * @param nameThreshold minimum Jaro-Winkler similarity between normalized names to consider a
 *     match (0.0–1.0). Default: 0.90
 * @param identifierMatchWeight weight added to the composite score when identifiers match exactly
 *     (e.g., passport numbers). Default: 0.40
 * @param dobMatchWeight weight added to the composite score when dates of birth match. Default:
 *     0.20
 * @param mergeThreshold minimum composite score to trigger a merge. Default: 0.85
 * @param blockingPrefixLength length of the normalized name prefix used as a blocking key to
 *     reduce pairwise comparisons. Default: 3
 */
public record DeduplicationConfig(
        double nameThreshold,
        double identifierMatchWeight,
        double dobMatchWeight,
        double mergeThreshold,
        int blockingPrefixLength) {

    /** Default configuration suitable for most sanctions screening use cases. */
    public static final DeduplicationConfig DEFAULT =
            new DeduplicationConfig(0.90, 0.40, 0.20, 0.85, 3);

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if any threshold is outside [0.0, 1.0] or prefix length is
     *     negative
     */
    public DeduplicationConfig {
        if (nameThreshold < 0.0 || nameThreshold > 1.0) {
            throw new IllegalArgumentException("nameThreshold must be between 0.0 and 1.0");
        }
        if (identifierMatchWeight < 0.0 || identifierMatchWeight > 1.0) {
            throw new IllegalArgumentException(
                    "identifierMatchWeight must be between 0.0 and 1.0");
        }
        if (dobMatchWeight < 0.0 || dobMatchWeight > 1.0) {
            throw new IllegalArgumentException("dobMatchWeight must be between 0.0 and 1.0");
        }
        if (mergeThreshold < 0.0 || mergeThreshold > 1.0) {
            throw new IllegalArgumentException("mergeThreshold must be between 0.0 and 1.0");
        }
        if (blockingPrefixLength < 1) {
            throw new IllegalArgumentException("blockingPrefixLength must be >= 1");
        }
    }
}
