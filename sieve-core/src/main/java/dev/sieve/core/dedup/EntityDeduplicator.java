package dev.sieve.core.dedup;

import dev.sieve.core.model.SanctionedEntity;
import java.util.Collection;

/**
 * Service Provider Interface for entity deduplication strategies.
 *
 * <p>Implementations analyze a collection of {@link SanctionedEntity} records from potentially
 * multiple sanctions lists and identify which entries represent the same real-world entity. The
 * result is a set of {@link CanonicalEntity} records, each grouping one or more source entities.
 *
 * <p>The deduplication process is inspired by the nomenklatura framework used by OpenSanctions,
 * which merges entities across OFAC, EU, UN, and UK lists based on name similarity, identifier
 * overlap, and biographical data matching.
 */
public interface EntityDeduplicator {

    /**
     * Deduplicates the given collection of sanctioned entities.
     *
     * <p>Entities from different list sources that are determined to represent the same real-world
     * entity are merged into a single {@link CanonicalEntity}. Entities with no detected duplicates
     * become singleton canonical entities.
     *
     * @param entities the raw entities to deduplicate, must not be {@code null}
     * @return the deduplication result containing canonical entities and mapping
     */
    DeduplicationResult deduplicate(Collection<SanctionedEntity> entities);
}
