package dev.sieve.core.dedup;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of running entity deduplication across all ingested sanctions entities.
 *
 * <p>Contains the canonical entities produced by the dedup process, a mapping from each raw source
 * entity ID to its canonical entity ID, and summary statistics.
 *
 * @param canonicalEntities all canonical entities produced, keyed by canonical ID
 * @param entityToCanonicalId mapping from each source entity ID to its canonical entity ID
 * @param totalSourceEntities total number of raw source entities that were deduplicated
 * @param totalCanonicalEntities total number of canonical entities produced
 * @param mergedGroups number of canonical entities that contain more than one source entity
 * @param duration wall-clock time the deduplication took
 */
public record DeduplicationResult(
        Map<String, CanonicalEntity> canonicalEntities,
        Map<String, String> entityToCanonicalId,
        int totalSourceEntities,
        int totalCanonicalEntities,
        int mergedGroups,
        Duration duration) {

    /**
     * Compact constructor with validation and defensive copies.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public DeduplicationResult {
        Objects.requireNonNull(canonicalEntities, "canonicalEntities must not be null");
        Objects.requireNonNull(entityToCanonicalId, "entityToCanonicalId must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        canonicalEntities = Map.copyOf(canonicalEntities);
        entityToCanonicalId = Map.copyOf(entityToCanonicalId);
    }

    /**
     * Returns the number of source entities that were combined into multi-source canonical entities.
     *
     * @return the number of duplicates eliminated
     */
    public int duplicatesEliminated() {
        return totalSourceEntities - totalCanonicalEntities;
    }

    /**
     * Returns the canonical entity for the given source entity ID, or {@code null} if not found.
     *
     * @param sourceEntityId the raw entity ID
     * @return the canonical entity, or {@code null}
     */
    public CanonicalEntity canonicalFor(String sourceEntityId) {
        String canonicalId = entityToCanonicalId.get(sourceEntityId);
        return canonicalId != null ? canonicalEntities.get(canonicalId) : null;
    }

    /**
     * Returns all canonical entities as an unmodifiable list.
     *
     * @return the canonical entities, never {@code null}
     */
    public List<CanonicalEntity> canonicalEntityList() {
        return List.copyOf(canonicalEntities.values());
    }
}
