package dev.sieve.core.dedup;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe index that maps raw source entity IDs to their canonical entities.
 *
 * <p>Populated after the deduplication process runs, this index is used by the screening path to
 * collapse match results from duplicate source entities into a single canonical result.
 *
 * <p>When deduplication has not been applied, all lookups return empty, and the screening path
 * falls back to treating each source entity as its own canonical entity.
 */
public final class DeduplicationIndex {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationIndex.class);

    private volatile Map<String, CanonicalEntity> canonicalEntities = Map.of();
    private volatile Map<String, String> entityToCanonicalId = Map.of();
    private volatile boolean populated = false;

    /** Creates a new, empty deduplication index. */
    public DeduplicationIndex() {}

    /**
     * Populates the index from a deduplication result.
     *
     * <p>Replaces any previous mapping atomically.
     *
     * @param result the deduplication result to load
     */
    public void apply(DeduplicationResult result) {
        Objects.requireNonNull(result, "result must not be null");
        // Snapshot into concurrent maps for safe concurrent reads
        this.canonicalEntities = new ConcurrentHashMap<>(result.canonicalEntities());
        this.entityToCanonicalId = new ConcurrentHashMap<>(result.entityToCanonicalId());
        this.populated = true;
        log.info(
                "Deduplication index applied [canonical={}, mappings={}, merged={}, eliminated={}]",
                result.totalCanonicalEntities(),
                result.entityToCanonicalId().size(),
                result.mergedGroups(),
                result.duplicatesEliminated());
    }

    /**
     * Returns the canonical entity ID for the given source entity ID.
     *
     * @param sourceEntityId the raw entity ID
     * @return the canonical entity ID, or empty if dedup has not been applied or entity not found
     */
    public Optional<String> canonicalIdOf(String sourceEntityId) {
        String id = entityToCanonicalId.get(sourceEntityId);
        return Optional.ofNullable(id);
    }

    /**
     * Returns the canonical entity for the given source entity ID.
     *
     * @param sourceEntityId the raw entity ID
     * @return the canonical entity, or empty if not found
     */
    public Optional<CanonicalEntity> canonicalOf(String sourceEntityId) {
        String canonicalId = entityToCanonicalId.get(sourceEntityId);
        if (canonicalId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(canonicalEntities.get(canonicalId));
    }

    /**
     * Returns the canonical entity by its canonical ID.
     *
     * @param canonicalId the canonical entity ID
     * @return the canonical entity, or empty if not found
     */
    public Optional<CanonicalEntity> getCanonical(String canonicalId) {
        return Optional.ofNullable(canonicalEntities.get(canonicalId));
    }

    /**
     * Returns whether the deduplication index has been populated.
     *
     * @return {@code true} if dedup data is available
     */
    public boolean isPopulated() {
        return populated;
    }

    /**
     * Returns the total number of canonical entities in the index.
     *
     * @return canonical entity count
     */
    public int size() {
        return canonicalEntities.size();
    }

    /** Clears the deduplication index. */
    public void clear() {
        canonicalEntities = Map.of();
        entityToCanonicalId = Map.of();
        populated = false;
        log.info("Deduplication index cleared");
    }
}
