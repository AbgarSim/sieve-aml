package dev.sieve.cli.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewsStepTest {

    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");

    @TempDir Path out;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<MediaArticle>> news = new HashMap<>();
    private final List<String> asked = new ArrayList<>();

    @Test
    void shouldWriteTheArticlesThatMentionAPublishedName() throws IOException {
        news.put("Ivan Petrov", List.of(article("https://news.example/a", NOW.minusSeconds(3600))));
        SanctionedEntity ivan = entity("1", EntityType.INDIVIDUAL, "Ivan Petrov");

        int records = step().write(List.of(loaded(ivan)), out);

        assertThat(records).isEqualTo(1);
        JsonNode file = mapper.readTree(out.resolve("news.json").toFile());
        assertThat(file.get("days").asInt()).isEqualTo(NewsStep.DAYS);
        JsonNode article = file.get("records").get("OFAC_SDN/1").get(0);
        assertThat(article.get("url").asText()).isEqualTo("https://news.example/a");
        assertThat(article.get("terms").get(0).asText()).isEqualTo("fraud");
        assertThat(article.get("mentionedAs").asText()).isEqualTo("Ivan Petrov");
    }

    @Test
    void shouldNotLookUpOneWordNamesVesselsOrUnpublishedRecords() throws IOException {
        SanctionedEntity oneWord = entity("2", EntityType.ENTITY, "Hamas");
        SanctionedEntity vessel = entity("3", EntityType.VESSEL, "Sea Star");
        SanctionedEntity pep =
                new SanctionedEntity(
                        "4",
                        EntityType.INDIVIDUAL,
                        ListSource.OFAC_SDN,
                        new NameInfo(
                                "Anna Ivanova",
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
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        NOW,
                        Set.of(RiskTopic.PEP),
                        List.of());

        step().write(List.of(loaded(oneWord, vessel, pep)), out);

        assertThat(asked).isEmpty();
        assertThat(NewsStep.lookedUp("Al-Qaida, A.")).isFalse();
        assertThat(NewsStep.lookedUp("Bank Melli Iran")).isTrue();
    }

    @Test
    void shouldAskOnceForANameSharedByTwoRecords() throws IOException {
        SanctionedEntity a = entity("5", EntityType.INDIVIDUAL, "Ivan Petrov");
        SanctionedEntity b = entity("6", EntityType.INDIVIDUAL, "Ivan Petrov");

        step().write(List.of(loaded(a, b)), out);

        assertThat(asked).containsExactly("Ivan Petrov");
    }

    @Test
    void shouldKeepEarlierArticlesUntilTheyAgeOutOrTheRecordGoes() throws IOException {
        Files.writeString(
                out.resolve("news.json"),
                """
                {"formatVersion":1,"records":{
                  "OFAC_SDN/1":[
                    {"url":"https://news.example/old","seenAt":"2026-08-01T00:00:00Z"},
                    {"url":"https://news.example/week","seenAt":"2026-09-28T00:00:00Z"}],
                  "OFAC_SDN/9":[{"url":"https://news.example/gone","seenAt":"2026-10-04T00:00:00Z"}]
                }}
                """);
        news.put("Ivan Petrov", List.of(article("https://news.example/new", NOW.minusSeconds(60))));
        SanctionedEntity ivan = entity("1", EntityType.INDIVIDUAL, "Ivan Petrov");

        step().write(List.of(loaded(ivan)), out);

        JsonNode records = mapper.readTree(out.resolve("news.json").toFile()).get("records");
        assertThat(records.has("OFAC_SDN/9")).isFalse();
        assertThat(records.get("OFAC_SDN/1"))
                .extracting(a -> a.get("url").asText())
                .containsExactly("https://news.example/new", "https://news.example/week");
    }

    @Test
    void shouldKeepTheNewestArticlesPerRecord() throws IOException {
        List<MediaArticle> many = new ArrayList<>();
        for (int i = 0; i < NewsStep.PER_RECORD + 3; i++) {
            many.add(article("https://news.example/" + i, NOW.minusSeconds(60L * i)));
        }
        news.put("Ivan Petrov", many);

        step().write(List.of(loaded(entity("1", EntityType.INDIVIDUAL, "Ivan Petrov"))), out);

        JsonNode kept =
                mapper.readTree(out.resolve("news.json").toFile()).get("records").get("OFAC_SDN/1");
        assertThat(kept).hasSize(NewsStep.PER_RECORD);
        assertThat(kept.get(0).get("url").asText()).isEqualTo("https://news.example/0");
    }

    @Test
    void shouldKeepOnlyArticlesThatNameTheListedNameItself() {
        assertThat(
                        NewsStep.namesWhole(
                                "Stockton Borough Council", "Borough Council", EntityType.ENTITY))
                .isFalse();
        assertThat(NewsStep.namesWhole("Acme Trading LLC", "Acme Trading", EntityType.COMPANY))
                .isTrue();
        assertThat(
                        NewsStep.namesWhole(
                                "Ivan Ivanovich Petrov", "Ivan Petrov", EntityType.INDIVIDUAL))
                .isFalse();
        assertThat(
                        NewsStep.namesWhole(
                                "Ivan Petrov", "Ivan Ivanovich Petrov", EntityType.INDIVIDUAL))
                .isFalse();
        assertThat(NewsStep.namesWhole("MP LIMITED LIABILITY COMPANY", "Mp Co", EntityType.ENTITY))
                .isFalse();
        assertThat(NewsStep.namesWhole("SHARIFI, Ali", "Ali Shariati", EntityType.INDIVIDUAL))
                .isFalse();
        assertThat(NewsStep.namesWhole("PETROV, Ivan", "Ivan Petrov", EntityType.INDIVIDUAL))
                .isTrue();
    }

    private NewsStep step() {
        AdverseMediaSearch search =
                (AdverseMediaQuery query) -> {
                    asked.add(query.name());
                    return new AdverseMediaReport(
                            query.name(),
                            "test",
                            query.name(),
                            AdverseMediaReport.Status.OK,
                            news.getOrDefault(query.name(), List.of()).stream()
                                    .limit(query.maxArticles())
                                    .toList(),
                            NOW,
                            Optional.empty());
                };
        return new NewsStep(search, Duration.ofDays(2), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MediaArticle article(String url, Instant seenAt) {
        return new MediaArticle(
                url,
                "Businessman charged with fraud",
                "news.example",
                "English",
                Optional.empty(),
                seenAt,
                List.of("fraud"),
                Optional.of("Ivan Petrov"));
    }

    private static SanctionedEntity entity(String id, EntityType type, String name) {
        return new SanctionedEntity(
                id,
                type,
                ListSource.OFAC_SDN,
                new NameInfo(name, null, null, null, null, NameType.PRIMARY, null, null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                NOW);
    }

    private static FetchedSource loaded(SanctionedEntity... entities) {
        return new FetchedSource(
                ListSource.OFAC_SDN,
                FetchedSource.Status.LOADED,
                List.of(entities),
                Optional.empty(),
                Duration.ZERO,
                Optional.empty());
    }
}
