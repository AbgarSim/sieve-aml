package dev.sieve.core.dedup;

/**
 * Configuration parameters for the entity deduplication process.
 *
 * <p>Controls the similarity thresholds and signal weights used to decide when two {@link
 * dev.sieve.core.model.SanctionedEntity} records from different sanctions lists represent the same
 * real-world entity.
 *
 * @param nameThreshold minimum Jaro-Winkler similarity between normalized names to consider a match
 *     (0.0–1.0) when a shared date of birth corroborates it. Default: 0.90
 * @param identifierMatchWeight weight added to the composite score when identifiers match exactly
 *     (e.g., passport numbers). Default: 0.40
 * @param dobMatchWeight weight added to the composite score when dates of birth match. Default:
 *     0.20
 * @param mergeThreshold minimum composite score to trigger a merge. Default: 0.85
 * @param blockingPrefixLength length of the normalized name prefix used as a blocking key to reduce
 *     pairwise comparisons. Default: 3
 * @param uncorroboratedNameThreshold minimum name similarity when neither an identifier nor a date
 *     of birth is there to back the names up, so that two similar names alone are not taken for one
 *     person. Default: 0.95
 */
public record DeduplicationConfig(
        double nameThreshold,
        double identifierMatchWeight,
        double dobMatchWeight,
        double mergeThreshold,
        int blockingPrefixLength,
        double uncorroboratedNameThreshold) {

    /** Name similarity required of a match nothing else corroborates, unless configured. */
    public static final double DEFAULT_UNCORROBORATED_NAME_THRESHOLD = 0.95;

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
            throw new IllegalArgumentException("identifierMatchWeight must be between 0.0 and 1.0");
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
        if (uncorroboratedNameThreshold < 0.0 || uncorroboratedNameThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "uncorroboratedNameThreshold must be between 0.0 and 1.0");
        }
    }

    /**
     * Creates a configuration with the default {@link #uncorroboratedNameThreshold()}, or the name
     * threshold when that is stricter.
     *
     * @param nameThreshold minimum name similarity for a corroborated match
     * @param identifierMatchWeight bonus for a shared identifier
     * @param dobMatchWeight bonus for a shared date of birth
     * @param mergeThreshold minimum composite score to merge
     * @param blockingPrefixLength length of the blocking key prefix
     */
    public DeduplicationConfig(
            double nameThreshold,
            double identifierMatchWeight,
            double dobMatchWeight,
            double mergeThreshold,
            int blockingPrefixLength) {
        this(
                nameThreshold,
                identifierMatchWeight,
                dobMatchWeight,
                mergeThreshold,
                blockingPrefixLength,
                Math.max(nameThreshold, DEFAULT_UNCORROBORATED_NAME_THRESHOLD));
    }
}
