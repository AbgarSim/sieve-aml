package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenMatchEngineTest {

    private TokenMatchEngine engine;
    private InMemoryEntityIndex index;

    @BeforeEach
    void setUp() {
        engine = new TokenMatchEngine();
        index = new InMemoryEntityIndex();
    }

    @Test
    void shouldMatchReorderedNames() {
        index.addAll(List.of(createEntity("1", "SMITH, John", "John", "SMITH")));

        ScreeningRequest request = ScreeningRequest.of("John Smith", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().matchAlgorithm()).isEqualTo("TOKEN_SET");
        assertThat(results.getFirst().score()).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    void shouldMatchPartialTokenOverlap() {
        index.addAll(
                List.of(createEntity("1", "PUTIN, Vladimir Vladimirovich", "Vladimir", "PUTIN")));

        ScreeningRequest request = ScreeningRequest.of("Vladimir Putin", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().score()).isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void shouldSkipSingleTokenQueries() {
        index.addAll(List.of(createEntity("1", "PUTIN, Vladimir", "Vladimir", "PUTIN")));

        ScreeningRequest request = ScreeningRequest.of("Putin", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        // Single-token queries are handled by Exact/Fuzzy engines
        assertThat(results).isEmpty();
    }

    @Test
    void shouldNotMatchUnrelatedNames() {
        index.addAll(List.of(createEntity("1", "SMITH, John", "John", "SMITH")));

        ScreeningRequest request = ScreeningRequest.of("Vladimir Putin", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldHandleCommasInNames() {
        index.addAll(List.of(createEntity("1", "DOE, Jane Marie", "Jane", "DOE")));

        ScreeningRequest request = ScreeningRequest.of("Jane Doe", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isNotEmpty();
    }

    @Test
    void tokenSetScoreShouldHandleEmptyArrays() {
        assertThat(TokenMatchEngine.tokenSetScore(new String[0], new String[] {"a"}))
                .isEqualTo(0.0);
        assertThat(TokenMatchEngine.tokenSetScore(new String[] {"a"}, new String[0]))
                .isEqualTo(0.0);
    }

    private static SanctionedEntity createEntity(
            String id, String fullName, String givenName, String familyName) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                ListSource.OFAC_SDN,
                new NameInfo(
                        fullName,
                        givenName,
                        familyName,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN),
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
                null);
    }
}
