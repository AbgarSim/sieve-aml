package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;

class CompositeMatchEngineTest {

    @Test
    void shouldCombineResultsFromMultipleEngines() {
        EntityIndex index = new InMemoryEntityIndex();
        index.add(createEntity("1", "DOE, John"));

        CompositeMatchEngine composite =
                new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));

        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.50);
        List<MatchResult> results = composite.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
    }

    @Test
    void shouldDeduplicateByEntityIdKeepingHighestScore() {
        EntityIndex index = new InMemoryEntityIndex();
        index.add(createEntity("1", "DOE, John"));

        CompositeMatchEngine composite =
                new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));

        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.50);
        List<MatchResult> results = composite.screen(request, index);

        long uniqueEntityIds = results.stream().map(r -> r.entity().id()).distinct().count();
        assertThat(uniqueEntityIds).isEqualTo(results.size());
    }

    @Test
    void shouldReturnResultsSortedByScoreDescending() {
        EntityIndex index = new InMemoryEntityIndex();
        index.add(createEntity("1", "DOE, John"));
        index.add(createEntity("2", "DOE, Jane"));
        index.add(createEntity("3", "SMITH, Robert"));

        CompositeMatchEngine composite =
                new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));

        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.50);
        List<MatchResult> results = composite.screen(request, index);

        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i - 1).score()).isGreaterThanOrEqualTo(results.get(i).score());
        }
    }

    @Test
    void shouldThrowForEmptyEngineList() {
        assertThatThrownBy(() -> new CompositeMatchEngine(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one");
    }

    @Test
    void shouldThrowForNullEngineList() {
        assertThatThrownBy(() -> new CompositeMatchEngine(null))
                .isInstanceOf(NullPointerException.class);
    }

    private static SanctionedEntity createEntity(String id, String name) {
        NameInfo primaryName =
                new NameInfo(
                        name,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                primaryName,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.now());
    }
}
