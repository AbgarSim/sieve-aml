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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExactMatchEngineTest {

    private ExactMatchEngine engine;
    private EntityIndex index;

    @BeforeEach
    void setUp() {
        engine = new ExactMatchEngine();
        index = new InMemoryEntityIndex();
    }

    @Test
    void shouldReturnMatchesWhenExactNameExists() {
        index.add(createEntity("1", "DOE, John", List.of()));
        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.5);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).score()).isEqualTo(1.0);
        assertThat(results.get(0).matchedField()).isEqualTo("primaryName");
        assertThat(results.get(0).matchAlgorithm()).isEqualTo("EXACT");
    }

    @Test
    void shouldMatchCaseInsensitive() {
        index.add(createEntity("1", "DOE, John", List.of()));
        ScreeningRequest request = ScreeningRequest.of("doe, john", 0.5);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
    }

    @Test
    void shouldMatchWithExtraWhitespace() {
        index.add(createEntity("1", "DOE,  John", List.of()));
        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.5);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
    }

    @Test
    void shouldMatchOnAlias() {
        index.add(createEntity("1", "DOE, John", List.of("Johnny Doe", "J. Doe")));
        ScreeningRequest request = ScreeningRequest.of("Johnny Doe", 0.5);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).matchedField()).isEqualTo("alias[0]");
    }

    @Test
    void shouldReturnEmptyWhenNoMatch() {
        index.add(createEntity("1", "DOE, John", List.of()));
        ScreeningRequest request = ScreeningRequest.of("SMITH, Jane", 0.5);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldFilterByEntityType() {
        index.add(
                createEntity(
                        "1", "DOE, John", EntityType.INDIVIDUAL, ListSource.OFAC_SDN, List.of()));
        index.add(
                createEntity("2", "DOE, John", EntityType.ENTITY, ListSource.OFAC_SDN, List.of()));

        ScreeningRequest request =
                new ScreeningRequest(
                        "DOE, John", Optional.of(EntityType.INDIVIDUAL), Optional.empty(), 0.5);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).entity().entityType()).isEqualTo(EntityType.INDIVIDUAL);
    }

    @Test
    void shouldFilterBySource() {
        index.add(
                createEntity(
                        "1", "DOE, John", EntityType.INDIVIDUAL, ListSource.OFAC_SDN, List.of()));
        index.add(
                createEntity(
                        "2",
                        "DOE, John",
                        EntityType.INDIVIDUAL,
                        ListSource.EU_CONSOLIDATED,
                        List.of()));

        ScreeningRequest request =
                new ScreeningRequest(
                        "DOE, John",
                        Optional.empty(),
                        Optional.of(Set.of(ListSource.OFAC_SDN)),
                        0.5);

        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).entity().listSource()).isEqualTo(ListSource.OFAC_SDN);
    }

    @Test
    void shouldReturnEmptyForEmptyIndex() {
        ScreeningRequest request = ScreeningRequest.of("DOE, John", 0.5);
        List<MatchResult> results = engine.screen(request, index);
        assertThat(results).isEmpty();
    }

    private static SanctionedEntity createEntity(String id, String name, List<String> aliases) {
        return createEntity(id, name, EntityType.INDIVIDUAL, ListSource.OFAC_SDN, aliases);
    }

    private static SanctionedEntity createEntity(
            String id,
            String name,
            EntityType entityType,
            ListSource source,
            List<String> aliases) {
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

        List<NameInfo> aliasNames =
                aliases.stream()
                        .map(
                                a ->
                                        new NameInfo(
                                                a,
                                                null,
                                                null,
                                                null,
                                                null,
                                                NameType.AKA,
                                                NameStrength.STRONG,
                                                ScriptType.LATIN))
                        .toList();

        return new SanctionedEntity(
                id,
                entityType,
                source,
                primaryName,
                aliasNames,
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
