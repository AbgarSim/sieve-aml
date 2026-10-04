package dev.sieve.server.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.provenance.ProvenanceStamper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The system of record: every entity Sieve holds, stored in PostgreSQL.
 *
 * <p>Each entity is one row of {@code sanctioned_entity}. The full record, provenance included, is
 * kept as JSON in the {@code data} column; names, dates, identifiers, addresses and programs are
 * also written to indexed columns and child tables so they can be queried in SQL. A refresh of a
 * list replaces that list's rows in one transaction and copies every entity the list dropped to
 * {@code entity_removal}, so delistings stay on record.
 */
public class PostgresEntityStore {

    private static final Logger log = LoggerFactory.getLogger(PostgresEntityStore.class);
    private static final int BATCH_SIZE = 1000;
    private static final int FETCH_SIZE = 2000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    /**
     * Creates the store.
     *
     * @param jdbc access to the database
     * @param transactions runs each refresh in one transaction
     * @param objectMapper writes and reads the {@code data} column
     */
    public PostgresEntityStore(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Reads every stored entity and hands each one to the consumer, without holding them all in
     * memory at once.
     *
     * @param consumer receives each entity
     * @return the number of entities read
     */
    public int forEach(Consumer<SanctionedEntity> consumer) {
        Objects.requireNonNull(consumer, "consumer must not be null");
        int[] count = {0};
        transactions.executeWithoutResult(
                status -> {
                    jdbc.setFetchSize(FETCH_SIZE);
                    jdbc.query(
                            "SELECT id, data FROM sanctioned_entity ORDER BY id",
                            rs -> {
                                consumer.accept(read(rs.getString(1), rs.getString(2)));
                                count[0]++;
                            });
                });
        return count[0];
    }

    /**
     * Returns the number of stored entities.
     *
     * @return the row count
     */
    public int count() {
        Integer count =
                jdbc.queryForObject("SELECT COUNT(*) FROM sanctioned_entity", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * Replaces all entities of one list in one transaction. Entities the list no longer carries are
     * deleted and copied to {@code entity_removal}.
     *
     * @param source the list
     * @param entities the list's entities as just fetched; when two share an id the last one wins
     * @return the ids of the entities removed
     * @throws IllegalArgumentException if an entity belongs to another list
     */
    public Set<String> replaceSource(ListSource source, Collection<SanctionedEntity> entities) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        Map<String, SanctionedEntity> byId = new LinkedHashMap<>();
        for (SanctionedEntity entity : entities) {
            if (entity.listSource() != source) {
                throw new IllegalArgumentException(
                        "Entity " + entity.id() + " belongs to " + entity.listSource());
            }
            byId.put(entity.id(), entity);
        }
        Set<String> removed =
                transactions.execute(
                        status -> {
                            Set<String> gone =
                                    new HashSet<>(
                                            jdbc.queryForList(
                                                    "SELECT id FROM sanctioned_entity WHERE list_source = ?",
                                                    String.class,
                                                    source.name()));
                            gone.removeAll(byId.keySet());
                            recordRemovals(source, gone);
                            jdbc.update(
                                    "DELETE FROM sanctioned_entity WHERE list_source = ?",
                                    source.name());
                            insert(new ArrayList<>(byId.values()));
                            return gone;
                        });
        log.info(
                "Stored list [source={}, entities={}, removed={}]",
                source,
                byId.size(),
                removed.size());
        return Set.copyOf(removed);
    }

    /** Deletes every stored entity and every recorded removal. */
    public void clear() {
        transactions.executeWithoutResult(
                status -> {
                    jdbc.update("DELETE FROM entity_removal");
                    jdbc.update("DELETE FROM sanctioned_entity");
                });
    }

    /**
     * Returns the ids of the entities a list has dropped, most recent first.
     *
     * @param source the list
     * @return the removed ids
     */
    public List<String> removedIds(ListSource source) {
        return jdbc.queryForList(
                "SELECT entity_id FROM entity_removal WHERE list_source = ? ORDER BY removed_at DESC",
                String.class,
                source.name());
    }

    private void recordRemovals(ListSource source, Set<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        List<String> list = new ArrayList<>(ids);
        jdbc.batchUpdate(
                "INSERT INTO entity_removal (entity_id, list_source, removed_at, data)"
                        + " SELECT id, list_source, ?, data FROM sanctioned_entity WHERE id = ?",
                list,
                BATCH_SIZE,
                (ps, id) -> {
                    ps.setTimestamp(1, now);
                    ps.setString(2, id);
                });
    }

    private void insert(List<SanctionedEntity> entities) {
        jdbc.batchUpdate(
                "INSERT INTO sanctioned_entity (id, entity_type, list_source, primary_name, remarks,"
                        + " listed_date, last_updated, data, given_name, family_name, middle_name,"
                        + " title, date_of_birth, place_of_birth, nationality, citizenship,"
                        + " first_seen, last_seen)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                entities,
                BATCH_SIZE,
                this::bindEntity);

        List<Child<NameInfo>> aliases = new ArrayList<>();
        List<Child<Address>> addresses = new ArrayList<>();
        List<Child<Identifier>> identifiers = new ArrayList<>();
        List<Child<SanctionsProgram>> programs = new ArrayList<>();
        for (SanctionedEntity e : entities) {
            e.aliases().forEach(v -> aliases.add(new Child<>(e.id(), v)));
            e.addresses().forEach(v -> addresses.add(new Child<>(e.id(), v)));
            e.identifiers().forEach(v -> identifiers.add(new Child<>(e.id(), v)));
            e.programs().forEach(v -> programs.add(new Child<>(e.id(), v)));
        }
        jdbc.batchUpdate(
                "INSERT INTO entity_alias (entity_id, full_name, given_name, family_name,"
                        + " middle_name, title, name_type, name_strength, script)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                aliases,
                BATCH_SIZE,
                (ps, c) -> {
                    NameInfo n = c.value();
                    ps.setString(1, c.entityId());
                    ps.setString(2, cut(n.fullName(), 1000));
                    ps.setString(3, cut(n.givenName(), 500));
                    ps.setString(4, cut(n.familyName(), 500));
                    ps.setString(5, cut(n.middleName(), 500));
                    ps.setString(6, cut(n.title(), 500));
                    ps.setString(7, n.nameType().name());
                    ps.setString(8, n.strength() == null ? null : n.strength().name());
                    ps.setString(9, n.script() == null ? null : n.script().name());
                });
        jdbc.batchUpdate(
                "INSERT INTO entity_address (entity_id, street, city, state_or_province,"
                        + " postal_code, country, full_address) VALUES (?, ?, ?, ?, ?, ?, ?)",
                addresses,
                BATCH_SIZE,
                (ps, c) -> {
                    Address a = c.value();
                    ps.setString(1, c.entityId());
                    ps.setString(2, cut(a.street(), 500));
                    ps.setString(3, cut(a.city(), 500));
                    ps.setString(4, cut(a.stateOrProvince(), 500));
                    ps.setString(5, cut(a.postalCode(), 100));
                    ps.setString(6, cut(a.country(), 500));
                    ps.setString(7, a.fullAddress());
                });
        jdbc.batchUpdate(
                "INSERT INTO entity_identifier (entity_id, type, value, issuing_country, remarks)"
                        + " VALUES (?, ?, ?, ?, ?)",
                identifiers,
                BATCH_SIZE,
                (ps, c) -> {
                    Identifier i = c.value();
                    ps.setString(1, c.entityId());
                    ps.setString(2, i.type().name());
                    ps.setString(3, cut(i.value(), 500));
                    ps.setString(4, cut(i.issuingCountry(), 500));
                    ps.setString(5, i.remarks());
                });
        jdbc.batchUpdate(
                "INSERT INTO entity_program (entity_id, code, name) VALUES (?, ?, ?)",
                programs,
                BATCH_SIZE,
                (ps, c) -> {
                    ps.setString(1, c.entityId());
                    ps.setString(2, cut(c.value().code(), 200));
                    ps.setString(3, cut(c.value().name(), 500));
                });
    }

    private void bindEntity(PreparedStatement ps, SanctionedEntity e) throws SQLException {
        NameInfo name = e.primaryName();
        ps.setString(1, e.id());
        ps.setString(2, e.entityType().name());
        ps.setString(3, e.listSource().name());
        ps.setString(4, cut(name.fullName(), 1000));
        ps.setString(5, e.remarks());
        ps.setTimestamp(6, timestamp(e.listedDate()));
        ps.setTimestamp(7, timestamp(e.lastUpdated()));
        ps.setString(8, write(e));
        ps.setString(9, cut(name.givenName(), 500));
        ps.setString(10, cut(name.familyName(), 500));
        ps.setString(11, cut(name.middleName(), 500));
        ps.setString(12, cut(name.title(), 500));
        ps.setObject(13, e.datesOfBirth().isEmpty() ? null : e.datesOfBirth().getFirst());
        ps.setString(14, cut(first(e.placesOfBirth()), 500));
        ps.setString(15, cut(first(e.nationalities()), 500));
        ps.setString(16, cut(first(e.citizenships()), 500));
        ps.setTimestamp(17, timestamp(ProvenanceStamper.firstSeen(e).orElse(null)));
        ps.setTimestamp(18, timestamp(ProvenanceStamper.lastSeen(e).orElse(null)));
    }

    private String write(SanctionedEntity entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize entity " + entity.id(), e);
        }
    }

    private SanctionedEntity read(String id, String json) {
        try {
            return objectMapper.readValue(json, SanctionedEntity.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read stored entity " + id, e);
        }
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? null : values.getFirst();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private record Child<T>(String entityId, T value) {}
}
