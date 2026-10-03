package dev.sieve.core.index;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe, in-memory implementation of {@link EntityIndex}.
 *
 * <p>Backed by a {@link ConcurrentHashMap} keyed on entity ID, with a secondary index by {@link
 * ListSource} for efficient filtered queries. All mutating operations are safe for concurrent
 * access from multiple threads.
 */
public final class InMemoryEntityIndex implements EntityIndex {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEntityIndex.class);

    private final ConcurrentHashMap<String, SanctionedEntity> entitiesById =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ListSource, Set<String>> idsBySource =
            new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong();
    private volatile Instant lastUpdated;

    /** Creates a new, empty in-memory entity index. */
    public InMemoryEntityIndex() {
        this.lastUpdated = Instant.now();
    }

    @Override
    public void addAll(Collection<SanctionedEntity> entities) {
        Objects.requireNonNull(entities, "entities must not be null");
        for (SanctionedEntity entity : entities) {
            addInternal(entity);
        }
        log.debug("Added {} entities to index [total={}]", entities.size(), entitiesById.size());
    }

    @Override
    public void add(SanctionedEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        addInternal(entity);
        log.debug(
                "Added entity to index [id={}, source={}, total={}]",
                entity.id(),
                entity.listSource(),
                entitiesById.size());
    }

    @Override
    public Set<String> replaceSource(ListSource source, Collection<SanctionedEntity> entities) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        Set<String> incomingIds = HashSet.newHashSet(entities.size());
        for (SanctionedEntity entity : entities) {
            if (entity.listSource() != source) {
                throw new IllegalArgumentException(
                        "Entity " + entity.id() + " belongs to " + entity.listSource());
            }
            incomingIds.add(entity.id());
        }

        for (SanctionedEntity entity : entities) {
            addInternal(entity);
        }

        Set<String> removed = new HashSet<>();
        Set<String> currentIds = idsBySource.getOrDefault(source, Set.of());
        for (String id : currentIds) {
            if (!incomingIds.contains(id)) {
                currentIds.remove(id);
                entitiesById.computeIfPresent(id, (k, e) -> e.listSource() == source ? null : e);
                removed.add(id);
            }
        }
        if (!removed.isEmpty()) {
            touch();
        }
        log.info(
                "Replaced source in index [source={}, entities={}, removed={}, total={}]",
                source,
                incomingIds.size(),
                removed.size(),
                entitiesById.size());
        return Set.copyOf(removed);
    }

    @Override
    public void clear() {
        entitiesById.clear();
        idsBySource.clear();
        touch();
        log.info("Index cleared");
    }

    @Override
    public long version() {
        return version.get();
    }

    @Override
    public int size() {
        return entitiesById.size();
    }

    @Override
    public Collection<SanctionedEntity> all() {
        return Collections.unmodifiableCollection(entitiesById.values());
    }

    @Override
    public Collection<SanctionedEntity> findBySource(ListSource source) {
        Objects.requireNonNull(source, "source must not be null");
        Set<String> ids = idsBySource.getOrDefault(source, Set.of());
        return ids.stream().map(entitiesById::get).filter(Objects::nonNull).toList();
    }

    @Override
    public Optional<SanctionedEntity> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(entitiesById.get(id));
    }

    @Override
    public IndexStats stats() {
        Map<ListSource, Integer> bySource = new EnumMap<>(ListSource.class);
        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);

        for (SanctionedEntity entity : entitiesById.values()) {
            bySource.merge(entity.listSource(), 1, Integer::sum);
            byType.merge(entity.entityType(), 1, Integer::sum);
        }

        return new IndexStats(entitiesById.size(), bySource, byType, lastUpdated);
    }

    private void addInternal(SanctionedEntity entity) {
        SanctionedEntity previous = entitiesById.put(entity.id(), entity);
        if (previous != null && previous.listSource() != entity.listSource()) {
            Set<String> previousIds = idsBySource.get(previous.listSource());
            if (previousIds != null) {
                previousIds.remove(entity.id());
            }
            log.warn(
                    "Entity id reused across sources [id={}, previous={}, current={}]",
                    entity.id(),
                    previous.listSource(),
                    entity.listSource());
        }
        idsBySource
                .computeIfAbsent(entity.listSource(), k -> new CopyOnWriteArraySet<>())
                .add(entity.id());
        touch();
    }

    private void touch() {
        version.incrementAndGet();
        lastUpdated = Instant.now();
    }
}
