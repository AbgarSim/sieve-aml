package dev.sieve.core.index;

import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.util.Collection;
import java.util.Optional;

/**
 * Abstraction over a store of {@link SanctionedEntity} instances.
 *
 * <p>Implementations must be thread-safe. The index supports bulk loading via {@link #addAll},
 * individual inserts via {@link #add}, and various query methods.
 */
public interface EntityIndex {

    /**
     * Adds all entities in the given collection to the index.
     *
     * @param entities the entities to add, must not be {@code null}
     */
    void addAll(Collection<SanctionedEntity> entities);

    /**
     * Adds a single entity to the index.
     *
     * @param entity the entity to add, must not be {@code null}
     */
    void add(SanctionedEntity entity);

    /** Removes all entities from the index. */
    void clear();

    /**
     * Returns the total number of entities in the index.
     *
     * @return entity count, always {@code >= 0}
     */
    int size();

    /**
     * Returns an unmodifiable view of all entities in the index.
     *
     * @return all entities, never {@code null}
     */
    Collection<SanctionedEntity> all();

    /**
     * Returns all entities from a specific sanctions list source.
     *
     * @param source the list source to filter by, must not be {@code null}
     * @return matching entities, never {@code null}
     */
    Collection<SanctionedEntity> findBySource(ListSource source);

    /**
     * Looks up a single entity by its source-specific ID.
     *
     * @param id the entity ID, must not be {@code null}
     * @return the entity if found, or empty
     */
    Optional<SanctionedEntity> findById(String id);

    /**
     * Returns statistical information about the index contents.
     *
     * @return current index statistics, never {@code null}
     */
    IndexStats stats();
}
