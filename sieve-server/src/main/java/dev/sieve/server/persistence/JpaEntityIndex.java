package dev.sieve.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.IndexStats;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL-backed implementation of {@link EntityIndex}.
 *
 * <p>Stores each {@link SanctionedEntity} as a row in the {@code sanctioned_entity} table. The
 * complete domain object is serialized to JSON in the {@code data} column, while queryable fields
 * are denormalized into indexed columns.
 */
public class JpaEntityIndex implements EntityIndex {

    private static final Logger log = LoggerFactory.getLogger(JpaEntityIndex.class);

    private final SanctionedEntityRepository repository;
    private final ObjectMapper objectMapper;

    public JpaEntityIndex(SanctionedEntityRepository repository, ObjectMapper objectMapper) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    @Transactional
    public void addAll(Collection<SanctionedEntity> entities) {
        Objects.requireNonNull(entities, "entities must not be null");
        List<SanctionedEntityRow> rows = entities.stream().map(this::toRow).toList();
        repository.saveAll(rows);
        log.debug(
                "Added {} entities to PostgreSQL index [total={}]",
                entities.size(),
                repository.count());
    }

    @Override
    @Transactional
    public void add(SanctionedEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        repository.save(toRow(entity));
        log.debug(
                "Added entity to PostgreSQL index [id={}, source={}, total={}]",
                entity.id(),
                entity.listSource(),
                repository.count());
    }

    @Override
    @Transactional
    public void clear() {
        repository.deleteAllInBatch();
        log.info("PostgreSQL index cleared");
    }

    @Override
    @Transactional(readOnly = true)
    public int size() {
        return (int) repository.count();
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<SanctionedEntity> all() {
        return repository.findAll().stream().map(this::toEntity).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<SanctionedEntity> findBySource(ListSource source) {
        Objects.requireNonNull(source, "source must not be null");
        return repository.findByListSource(source.name()).stream().map(this::toEntity).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SanctionedEntity> findById(String id) {
        Objects.requireNonNull(id, "id must not be null");
        return repository.findById(id).map(this::toEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public IndexStats stats() {
        Map<ListSource, Integer> bySource = new EnumMap<>(ListSource.class);
        for (Object[] row : repository.countByListSource()) {
            bySource.put(ListSource.valueOf((String) row[0]), ((Long) row[1]).intValue());
        }

        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        for (Object[] row : repository.countByEntityType()) {
            byType.put(EntityType.valueOf((String) row[0]), ((Long) row[1]).intValue());
        }

        return new IndexStats((int) repository.count(), bySource, byType, Instant.now());
    }

    private SanctionedEntityRow toRow(SanctionedEntity entity) {
        try {
            String json = objectMapper.writeValueAsString(entity);
            return new SanctionedEntityRow(
                    entity.id(),
                    entity.entityType().name(),
                    entity.listSource().name(),
                    entity.primaryName().fullName(),
                    entity.remarks(),
                    entity.listedDate(),
                    entity.lastUpdated(),
                    json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SanctionedEntity to JSON", e);
        }
    }

    private SanctionedEntity toEntity(SanctionedEntityRow row) {
        try {
            return objectMapper.readValue(row.getData(), SanctionedEntity.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to deserialize SanctionedEntity from JSON: id=" + row.getId(), e);
        }
    }
}
