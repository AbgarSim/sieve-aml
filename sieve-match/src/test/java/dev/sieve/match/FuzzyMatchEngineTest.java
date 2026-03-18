package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FuzzyMatchEngineTest {

    private FuzzyMatchEngine engine;
    private EntityIndex index;

    @BeforeEach
    void setUp() {
        engine = new FuzzyMatchEngine();
        index = new InMemoryEntityIndex();
    }

    @Test
    void shouldReturnHighScoreForSimilarNames() {
        index.add(createEntity("1", "GONZALEZ, Maria", List.of()));
        ScreeningRequest request = ScreeningRequest.of("GONZALES, Maria", 0.70);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isGreaterThan(0.90);
        assertThat(results.get(0).matchAlgorithm()).isEqualTo("JARO_WINKLER");
    }

    @Test
    void shouldReturnPerfectScoreForExactMatch() {
        index.add(createEntity("1", "DOE, John", List.of()));
        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.50);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void shouldFilterBelowThreshold() {
        index.add(createEntity("1", "DOE, John", List.of()));
        ScreeningRequest request = ScreeningRequest.of("COMPLETELY DIFFERENT NAME", 0.90);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldMatchOnBestAlias() {
        index.add(createEntity("1", "AL-RASHID, Ahmed", List.of("AL RASHID, Ahmad", "RASHID, Ahmed")));
        ScreeningRequest request = ScreeningRequest.of("AL RASHID, Ahmad", 0.80);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isGreaterThan(0.90);
    }

    @Test
    void shouldReturnResultsSortedByScoreDescending() {
        index.add(createEntity("1", "SMITH, John", List.of()));
        index.add(createEntity("2", "SMITH, Jonathan", List.of()));
        index.add(createEntity("3", "SMITH, Jane", List.of()));

        ScreeningRequest request = ScreeningRequest.of("SMITH, John", 0.50);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isNotEmpty();
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).score())
                    .isGreaterThanOrEqualTo(results.get(i).score());
        }
    }

    @Test
    void shouldReturnEmptyForEmptyIndex() {
        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.50);
        List<MatchResult> results = engine.screen(request, index);
        assertThat(results).isEmpty();
    }

    @Test
    void shouldKeepBestScorePerEntity() {
        index.add(createEntity("1", "DOE, John", List.of("DOE, Johnny", "John Doe")));
        ScreeningRequest request = ScreeningRequest.of("John Doe", 0.50);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
    }

    private static SanctionedEntity createEntity(
            String id, String name, List<String> aliases) {
        NameInfo primaryName =
                new NameInfo(
                        name, null, null, null, null, NameType.PRIMARY, NameStrength.STRONG,
                        ScriptType.LATIN);

        List<NameInfo> aliasNames =
                aliases.stream()
                        .map(
                                a ->
                                        new NameInfo(
                                                a, null, null, null, null, NameType.AKA,
                                                NameStrength.STRONG, ScriptType.LATIN))
                        .toList();

        return new SanctionedEntity(
                id, EntityType.INDIVIDUAL, ListSource.OFAC_SDN, primaryName, aliasNames, null, null,
                null, null, null, null, null, null, null, Instant.now());
    }
}
