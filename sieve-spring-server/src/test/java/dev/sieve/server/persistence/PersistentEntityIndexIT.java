package dev.sieve.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Provenance;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.core.model.SourcedValue.Key;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the system of record against a real PostgreSQL database. Set {@code SIEVE_TEST_DB_URL} (with
 * {@code SIEVE_TEST_DB_USER} and {@code SIEVE_TEST_DB_PASSWORD}) to an empty database to run it; CI
 * provides one. Without it the tests are skipped.
 */
class PersistentEntityIndexIT {

    private PostgresEntityStore store;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        String url = System.getenv("SIEVE_TEST_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "SIEVE_TEST_DB_URL is not set");
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        url,
                        System.getenv().getOrDefault("SIEVE_TEST_DB_USER", "sieve"),
                        System.getenv().getOrDefault("SIEVE_TEST_DB_PASSWORD", "sieve"));
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store =
                new PostgresEntityStore(
                        jdbc,
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                        new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void shouldStoreEntitiesAndRebuildTheScreeningIndexFromTheDatabase() {
        PersistentEntityIndex index = new PersistentEntityIndex(store);
        index.replaceSource(ListSource.OFAC_SDN, List.of(entity("ofac-sdn-1", "P1")));

        PersistentEntityIndex restarted = new PersistentEntityIndex(store);
        int loaded = restarted.load();

        assertThat(loaded).isEqualTo(1);
        SanctionedEntity back = restarted.findById("ofac-sdn-1").orElseThrow();
        assertThat(back).isEqualTo(entity("ofac-sdn-1", "P1"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT value FROM entity_identifier WHERE entity_id = 'ofac-sdn-1'",
                                String.class))
                .isEqualTo("P1");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM entity_alias WHERE entity_id = 'ofac-sdn-1'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void shouldRemoveDelistedEntitiesAndRecordTheRemoval() {
        PersistentEntityIndex index = new PersistentEntityIndex(store);
        index.replaceSource(
                ListSource.OFAC_SDN,
                List.of(entity("ofac-sdn-1", "P1"), entity("ofac-sdn-2", "P2")));

        Set<String> removed =
                index.replaceSource(ListSource.OFAC_SDN, List.of(entity("ofac-sdn-1", "P1")));

        assertThat(removed).containsExactly("ofac-sdn-2");
        assertThat(store.count()).isEqualTo(1);
        assertThat(store.removedIds(ListSource.OFAC_SDN)).containsExactly("ofac-sdn-2");
        assertThat(index.findById("ofac-sdn-2")).isEmpty();
    }

    @Test
    void shouldKeepFirstSeenAcrossARestart() throws Exception {
        AtomicReference<List<SanctionedEntity>> fetched =
                new AtomicReference<>(List.of(entity("ofac-sdn-1", "P1")));
        ListProvider provider = provider(fetched);

        PersistentEntityIndex first = new PersistentEntityIndex(store);
        first.load();
        new IngestionOrchestrator(List.of(provider)).ingest(first);
        Instant firstSeen =
                first.findById("ofac-sdn-1")
                        .orElseThrow()
                        .provenanceOf(Key.of(passport("P1")))
                        .orElseThrow()
                        .firstSeen();

        fetched.set(List.of(entity("ofac-sdn-1", "P1")));
        PersistentEntityIndex restarted = new PersistentEntityIndex(store);
        restarted.load();
        new IngestionOrchestrator(List.of(provider)).ingest(restarted);

        Provenance after =
                restarted
                        .findById("ofac-sdn-1")
                        .orElseThrow()
                        .provenanceOf(Key.of(passport("P1")))
                        .orElseThrow();
        assertThat(after.firstSeen()).isEqualTo(firstSeen);
        assertThat(after.sourceUrl()).isEqualTo("https://example.org/sdn.xml");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT first_seen IS NOT NULL FROM sanctioned_entity",
                                Boolean.class))
                .isTrue();
    }

    private static ListProvider provider(AtomicReference<List<SanctionedEntity>> fetched) {
        return new ListProvider() {
            @Override
            public ListSource source() {
                return ListSource.OFAC_SDN;
            }

            @Override
            public ListMetadata metadata() {
                return new ListMetadata(
                        ListSource.OFAC_SDN,
                        Instant.now(),
                        null,
                        null,
                        URI.create("https://example.org/sdn.xml"),
                        1);
            }

            @Override
            public List<SanctionedEntity> fetch() {
                return fetched.get();
            }

            @Override
            public boolean hasUpdates(ListMetadata previousMetadata) {
                return true;
            }
        };
    }

    private static Identifier passport(String number) {
        return new Identifier(IdentifierType.PASSPORT, number, "RU", null);
    }

    private static SanctionedEntity entity(String id, String passport) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                name("PETROV, Ivan", NameType.PRIMARY),
                List.of(name("Ivan Petrov", NameType.AKA)),
                List.of(),
                List.of(passport(passport)),
                List.of("RU"),
                List.of(),
                List.of(LocalDate.of(1970, 5, 1)),
                List.of(),
                "remark",
                List.of(new SanctionsProgram("RUSSIA-EO14024", "Russia", ListSource.OFAC_SDN)),
                Instant.parse("2022-04-06T00:00:00Z"),
                null,
                Set.of(RiskTopic.SANCTION),
                List.of());
    }

    private static NameInfo name(String fullName, NameType type) {
        return new NameInfo(
                fullName, null, null, null, null, type, NameStrength.STRONG, ScriptType.LATIN);
    }
}
