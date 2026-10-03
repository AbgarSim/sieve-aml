package dev.sieve.cli.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir Path out;

    @Test
    void shouldWriteAggregatesShardsAndIndexWhenListsLoad() throws IOException {
        write(fetch(Set.of()), NOW);

        JsonNode overview = read("overview.json");
        assertThat(overview.get("formatVersion").asInt()).isEqualTo(1);
        assertThat(overview.get("generatedAt").asText()).isEqualTo("2026-10-02T03:00:00Z");
        assertThat(overview.get("commit").asText()).isEqualTo("abc123");
        assertThat(overview.get("totalEntities").asInt()).isEqualTo(3);
        assertThat(overview.get("sourcesLoaded").asInt()).isEqualTo(2);
        assertThat(overview.get("sourcesTotal").asInt()).isEqualTo(ListSource.values().length);

        JsonNode countries = read("countries.json").get("countries");
        assertThat(countries.get("RU").get("entities").asInt()).isEqualTo(2);
        assertThat(countries.get("RU").get("name").asText()).isEqualTo("Russia");
        assertThat(countries.get("RU").get("bySource").get("UN_CONSOLIDATED").asInt()).isEqualTo(1);

        assertThat(Files.exists(out.resolve("entities/OFAC_SDN/0.json"))).isTrue();
        assertThat(Files.exists(out.resolve("entities/OFAC_SDN/1.json"))).isFalse();
        JsonNode shard = read("entities/OFAC_SDN/0.json");
        assertThat(shard).hasSize(2);
        assertThat(shard.get(0).get("primaryName").get("fullName").asText())
                .isEqualTo("Ivan Petrov");

        JsonNode entries = read("search-index.json").get("entries");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).get("k").asText()).isEqualTo("OFAC_SDN/1");
        assertThat(entries.get(0).get("t").asText()).isEqualTo("I");
        assertThat(entries.get(0).get("c").get(0).asText()).isEqualTo("RU");
        assertThat(entries.get(0).get("f").asInt()).isZero();
    }

    @Test
    void shouldKeepEntitiesApartWhenIdsRepeatAcrossLists() throws IOException {
        write(fetch(Set.of()), NOW);

        JsonNode entries = read("search-index.json").get("entries");
        assertThat(entries)
                .extracting(e -> e.get("k").asText())
                .containsExactlyInAnyOrder("OFAC_SDN/1", "OFAC_SDN/2", "UN_CONSOLIDATED/1");
    }

    @Test
    void shouldReportStatusOfEveryListWhenSomeFailOrNeedKeys() throws IOException {
        write(fetch(Set.of()), NOW);

        JsonNode sources = read("sources.json").get("sources");
        assertThat(sources).hasSize(ListSource.values().length);
        assertThat(status(sources, "OFAC_SDN")).isEqualTo("LOADED");
        assertThat(status(sources, "UK_HMT")).isEqualTo("FAILED");
        assertThat(row(sources, "UK_HMT").get("error").asText()).isEqualTo("HTTP 403");
        assertThat(status(sources, "UA_NSDC")).isEqualTo("NEEDS_KEY");
        assertThat(status(sources, "JP_MOF")).isEqualTo("SKIPPED");
        assertThat(row(sources, "OFAC_SDN").get("authority").asText())
                .contains("Foreign Assets Control");
        assertThat(row(sources, "OFAC_SDN").get("listUri").asText())
                .isEqualTo("https://example.test/OFAC_SDN");
        assertThat(row(sources, "OFAC_SDN").get("completeness").get("withNationality").asInt())
                .isEqualTo(1);
    }

    @Test
    void shouldCountPepsWithoutPublishingTheirRecords() throws IOException {
        SanctionedEntity pep =
                new SanctionedEntity(
                        "wd-Q1",
                        EntityType.INDIVIDUAL,
                        ListSource.WIKIDATA_PEP,
                        new NameInfo(
                                "Jane Minister",
                                null,
                                null,
                                null,
                                null,
                                NameType.PRIMARY,
                                null,
                                null),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("DE"),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of(
                                new SanctionsProgram(
                                        "Minister", "PEP tier 1", ListSource.WIKIDATA_PEP)),
                        null,
                        NOW,
                        Set.of(RiskTopic.PEP),
                        List.of());
        List<FetchedSource> fetched = new ArrayList<>(fetch(Set.of()));
        fetched.removeIf(f -> f.source() == ListSource.WIKIDATA_PEP);
        fetched.add(
                new FetchedSource(
                        ListSource.WIKIDATA_PEP,
                        FetchedSource.Status.LOADED,
                        List.of(pep),
                        Optional.empty(),
                        Duration.ZERO,
                        Optional.empty()));
        write(fetched, NOW);

        JsonNode overview = read("overview.json");
        assertThat(overview.get("totalEntities").asInt()).isEqualTo(3);
        assertThat(overview.get("pepEntities").asInt()).isEqualTo(1);
        assertThat(read("countries.json").get("countries").has("DE")).isFalse();
        assertThat(read("history.json").get(0).get("bySource").get("WIKIDATA_PEP").asInt())
                .isEqualTo(1);
        assertThat(row(read("sources.json").get("sources"), "WIKIDATA_PEP").get("entities").asInt())
                .isEqualTo(1);
        assertThat(Files.exists(out.resolve("entities/WIKIDATA_PEP"))).isFalse();
        assertThat(read("search-index.json").get("entries"))
                .extracting(e -> e.get("s").asText())
                .doesNotContain("WIKIDATA_PEP");
    }

    @Test
    void shouldSkipListsWhenFilterExcludesThem() {
        List<FetchedSource> fetched = fetch(Set.of(ListSource.UN_CONSOLIDATED));

        assertThat(fetched)
                .filteredOn(f -> f.source() == ListSource.OFAC_SDN)
                .extracting(FetchedSource::status)
                .containsExactly(FetchedSource.Status.SKIPPED);
    }

    @Test
    void shouldReplaceTodaysHistoryRowAndKeepEarlierDaysWhenRunAgain() throws IOException {
        write(fetch(Set.of()), Instant.parse("2026-10-01T03:00:00Z"));
        write(fetch(Set.of()), NOW);
        write(fetch(Set.of()), NOW.plusSeconds(3600));

        JsonNode history = read("history.json");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("date").asText()).isEqualTo("2026-10-01");
        assertThat(history.get(1).get("date").asText()).isEqualTo("2026-10-02");
        assertThat(history.get(1).get("bySource").get("OFAC_SDN").asInt()).isEqualTo(2);
    }

    private void write(List<FetchedSource> fetched, Instant at) throws IOException {
        new SnapshotWriter(CountryNormalizer.standard(), 2, Clock.fixed(at, ZoneOffset.UTC))
                .write(fetched, out, Optional.of("abc123"));
    }

    private static List<FetchedSource> fetch(Set<ListSource> only) {
        List<ListProvider> providers =
                List.of(
                        new FakeProvider(
                                ListSource.OFAC_SDN,
                                List.of(
                                        entity("1", ListSource.OFAC_SDN, "Ivan Petrov", "Russia"),
                                        entity("2", ListSource.OFAC_SDN, "Acme Ltd", null))),
                        new FakeProvider(
                                ListSource.UN_CONSOLIDATED,
                                List.of(
                                        entity(
                                                "1",
                                                ListSource.UN_CONSOLIDATED,
                                                "Petr Ivanov",
                                                "RU"))),
                        new FakeProvider(ListSource.UK_HMT, null),
                        new FakeProvider(ListSource.UA_NSDC, List.of()),
                        new FakeProvider(ListSource.JP_MOF, List.of()));
        Set<ListSource> filter =
                only.isEmpty()
                        ? Set.of(
                                ListSource.OFAC_SDN,
                                ListSource.UN_CONSOLIDATED,
                                ListSource.UK_HMT,
                                ListSource.UA_NSDC)
                        : only;
        return new SnapshotFetcher(providers, source -> source == ListSource.UA_NSDC).fetch(filter);
    }

    private static SanctionedEntity entity(
            String id, ListSource source, String name, String nationality) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                source,
                new NameInfo(name, null, null, null, null, NameType.PRIMARY, null, null),
                List.of(),
                List.of(new Address(null, "Moscow", null, null, nationality, null)),
                List.of(),
                nationality == null ? List.of() : List.of(nationality),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(new SanctionsProgram("P1", null, source)),
                null,
                NOW);
    }

    private JsonNode read(String file) throws IOException {
        return mapper.readTree(out.resolve(file).toFile());
    }

    private static JsonNode row(JsonNode sources, String source) {
        for (JsonNode row : sources) {
            if (source.equals(row.get("source").asText())) {
                return row;
            }
        }
        throw new AssertionError("No row for " + source);
    }

    private static String status(JsonNode sources, String source) {
        return row(sources, source).get("status").asText();
    }

    private record FakeProvider(ListSource source, List<SanctionedEntity> entities)
            implements ListProvider {

        @Override
        public ListMetadata metadata() {
            return new ListMetadata(
                    source, NOW, null, "hash", URI.create("https://example.test/" + source), 0);
        }

        @Override
        public List<SanctionedEntity> fetch() throws ListIngestionException {
            if (entities == null) {
                throw new ListIngestionException("HTTP 403", source);
            }
            return entities;
        }

        @Override
        public boolean hasUpdates(ListMetadata previousMetadata) {
            return true;
        }
    }
}
