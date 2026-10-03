package dev.sieve.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.IndexStats;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicLong version = new AtomicLong();

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
        version.incrementAndGet();
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
        version.incrementAndGet();
        log.debug(
                "Added entity to PostgreSQL index [id={}, source={}, total={}]",
                entity.id(),
                entity.listSource(),
                repository.count());
    }

    @Override
    @Transactional
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

        Set<String> removed = new HashSet<>(repository.findIdsByListSource(source.name()));
        removed.removeAll(incomingIds);
        repository.saveAll(entities.stream().map(this::toRow).toList());
        repository.deleteAllById(removed);
        version.incrementAndGet();
        log.info(
                "Replaced source in PostgreSQL index [source={}, entities={}, removed={}]",
                source,
                incomingIds.size(),
                removed.size());
        return Set.copyOf(removed);
    }

    @Override
    @Transactional
    public void clear() {
        repository.deleteAllInBatch();
        version.incrementAndGet();
        log.info("PostgreSQL index cleared");
    }

    @Override
    public long version() {
        return version.get();
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
            SanctionedEntityRow row =
                    new SanctionedEntityRow(
                            entity.id(),
                            entity.entityType().name(),
                            entity.listSource().name(),
                            entity.primaryName().fullName(),
                            entity.remarks(),
                            entity.listedDate(),
                            entity.lastUpdated(),
                            json);

            populateSearchableColumns(row, entity);
            mapAliases(row, entity);
            mapAddresses(row, entity);
            mapIdentifiers(row, entity);
            mapPrograms(row, entity);

            return row;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SanctionedEntity to JSON", e);
        }
    }

    private static void populateSearchableColumns(
            SanctionedEntityRow row, SanctionedEntity entity) {
        NameInfo name = entity.primaryName();
        row.setGivenName(name.givenName());
        row.setFamilyName(name.familyName());
        row.setMiddleName(name.middleName());
        row.setTitle(name.title());

        if (!entity.datesOfBirth().isEmpty()) {
            row.setDateOfBirth(entity.datesOfBirth().getFirst());
        }
        if (!entity.placesOfBirth().isEmpty()) {
            row.setPlaceOfBirth(entity.placesOfBirth().getFirst());
        }
        if (!entity.nationalities().isEmpty()) {
            row.setNationality(entity.nationalities().getFirst());
        }
        if (!entity.citizenships().isEmpty()) {
            row.setCitizenship(entity.citizenships().getFirst());
        }
    }

    private static void mapAliases(SanctionedEntityRow row, SanctionedEntity entity) {
        for (NameInfo alias : entity.aliases()) {
            row.getAliases()
                    .add(
                            new EntityAliasRow(
                                    row,
                                    alias.fullName(),
                                    alias.givenName(),
                                    alias.familyName(),
                                    alias.middleName(),
                                    alias.title(),
                                    alias.nameType() != null ? alias.nameType().name() : "ALIAS",
                                    alias.strength() != null ? alias.strength().name() : null,
                                    alias.script() != null ? alias.script().name() : null));
        }
    }

    private static void mapAddresses(SanctionedEntityRow row, SanctionedEntity entity) {
        for (Address addr : entity.addresses()) {
            row.getAddresses()
                    .add(
                            new EntityAddressRow(
                                    row,
                                    addr.street(),
                                    addr.city(),
                                    addr.stateOrProvince(),
                                    addr.postalCode(),
                                    addr.country(),
                                    addr.fullAddress()));
        }
    }

    private static void mapIdentifiers(SanctionedEntityRow row, SanctionedEntity entity) {
        for (Identifier ident : entity.identifiers()) {
            row.getIdentifiers()
                    .add(
                            new EntityIdentifierRow(
                                    row,
                                    ident.type() != null ? ident.type().name() : "OTHER",
                                    ident.value(),
                                    ident.issuingCountry(),
                                    ident.remarks()));
        }
    }

    private static void mapPrograms(SanctionedEntityRow row, SanctionedEntity entity) {
        for (SanctionsProgram prog : entity.programs()) {
            row.getPrograms().add(new EntityProgramRow(row, prog.code(), prog.name()));
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
