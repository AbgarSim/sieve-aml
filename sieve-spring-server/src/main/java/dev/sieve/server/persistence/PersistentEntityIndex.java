package dev.sieve.server.persistence;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.index.IndexStats;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The screening index of the Spring server: an in-memory index built from PostgreSQL, the system of
 * record.
 *
 * <p>Every write goes to {@link PostgresEntityStore} first and reaches memory only once the
 * database has committed it, so the database never lags behind what is screened. Every read is
 * served from memory. {@link #load()} rebuilds memory from the database, which the server does at
 * startup before any list is fetched, so first seen times and delistings survive restarts.
 */
public class PersistentEntityIndex implements EntityIndex {

    private static final Logger log = LoggerFactory.getLogger(PersistentEntityIndex.class);

    private final PostgresEntityStore store;
    private final InMemoryEntityIndex memory = new InMemoryEntityIndex();

    /**
     * Creates an index over the store. Memory starts empty until {@link #load()} runs.
     *
     * @param store the system of record
     */
    public PersistentEntityIndex(PostgresEntityStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /**
     * Replaces what is in memory with everything the database holds.
     *
     * @return the number of entities loaded
     */
    public synchronized int load() {
        List<SanctionedEntity> entities = new ArrayList<>();
        int count = store.forEach(entities::add);
        memory.clear();
        memory.addAll(entities);
        log.info("Screening index built from PostgreSQL [entities={}]", count);
        return count;
    }

    @Override
    public synchronized void addAll(Collection<SanctionedEntity> entities) {
        Objects.requireNonNull(entities, "entities must not be null");
        Map<ListSource, List<SanctionedEntity>> bySource =
                entities.stream().collect(Collectors.groupingBy(SanctionedEntity::listSource));
        for (Map.Entry<ListSource, List<SanctionedEntity>> entry : bySource.entrySet()) {
            Map<String, SanctionedEntity> merged =
                    memory.findBySource(entry.getKey()).stream()
                            .collect(
                                    Collectors.toMap(
                                            SanctionedEntity::id,
                                            Function.identity(),
                                            (a, b) -> b,
                                            java.util.LinkedHashMap::new));
            entry.getValue().forEach(e -> merged.put(e.id(), e));
            store.replaceSource(entry.getKey(), merged.values());
        }
        memory.addAll(entities);
    }

    @Override
    public void add(SanctionedEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        addAll(List.of(entity));
    }

    @Override
    public synchronized Set<String> replaceSource(
            ListSource source, Collection<SanctionedEntity> entities) {
        Set<String> removed = store.replaceSource(source, entities);
        memory.replaceSource(source, entities);
        return removed;
    }

    @Override
    public synchronized void clear() {
        store.clear();
        memory.clear();
    }

    @Override
    public long version() {
        return memory.version();
    }

    @Override
    public int size() {
        return memory.size();
    }

    @Override
    public Collection<SanctionedEntity> all() {
        return memory.all();
    }

    @Override
    public Collection<SanctionedEntity> findBySource(ListSource source) {
        return memory.findBySource(source);
    }

    @Override
    public Optional<SanctionedEntity> findById(String id) {
        return memory.findById(id);
    }

    @Override
    public IndexStats stats() {
        return memory.stats();
    }
}
