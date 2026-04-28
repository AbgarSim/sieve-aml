package dev.sieve.core.index;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Statistical summary of the contents of an {@link EntityIndex}.
 *
 * @param totalEntities total number of entities in the index
 * @param countBySource breakdown of entity count by {@link ListSource}
 * @param countByType breakdown of entity count by {@link EntityType}
 * @param lastUpdated timestamp of the most recent index modification
 */
public record IndexStats(
        int totalEntities,
        Map<ListSource, Integer> countBySource,
        Map<EntityType, Integer> countByType,
        Instant lastUpdated) {

    /**
     * Compact constructor with validation and defensive copies.
     *
     * @throws NullPointerException if any map parameter is {@code null}
     */
    public IndexStats {
        Objects.requireNonNull(countBySource, "countBySource must not be null");
        Objects.requireNonNull(countByType, "countByType must not be null");
        countBySource = Map.copyOf(countBySource);
        countByType = Map.copyOf(countByType);
    }
}
