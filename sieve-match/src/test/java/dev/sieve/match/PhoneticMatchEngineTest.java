package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.*;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PhoneticMatchEngineTest {

    private PhoneticMatchEngine engine;
    private InMemoryEntityIndex index;

    @BeforeEach
    void setUp() {
        engine = new PhoneticMatchEngine();
        index = new InMemoryEntityIndex();
    }

    @Test
    void shouldMatchPhoneticVariants() {
        index.addAll(List.of(createEntity("1", "GADDAFI, Muammar", "Muammar", "GADDAFI")));

        ScreeningRequest request = ScreeningRequest.of("Qadhafi Moammar", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().matchAlgorithm()).isEqualTo("DOUBLE_METAPHONE");
    }

    @Test
    void shouldNotMatchUnrelatedNames() {
        index.addAll(List.of(createEntity("1", "PUTIN, Vladimir", "Vladimir", "PUTIN")));

        ScreeningRequest request = ScreeningRequest.of("Biden Joseph", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldScoreSingleTokenMatchAsPartialWhenNameHasMoreTokens() {
        index.addAll(List.of(createEntity("1", "SCHMIDT, Hans", "Hans", "SCHMIDT")));

        ScreeningRequest request = ScreeningRequest.of("Smith", 0.50);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isLessThan(0.80);
    }

    @Test
    void shouldNotReturnLoneFirstNameAtDefaultThreshold() {
        index.addAll(List.of(createEntity("1", "PUTIN, Vladimir", "Vladimir", "PUTIN")));

        ScreeningRequest request = ScreeningRequest.of("Wladimir", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isEmpty();
    }

    @Test
    void shouldKeepFullScoreWhenSingleTokenNameMatches() {
        index.addAll(List.of(createEntity("1", "Usama", null, null)));

        ScreeningRequest request = ScreeningRequest.of("Osama", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().score()).isEqualTo(0.95);
    }

    @Test
    void shouldFindEntitiesAddedAfterTheFirstScreening() {
        index.addAll(List.of(createEntity("1", "PUTIN, Vladimir", "Vladimir", "PUTIN")));
        engine.screen(ScreeningRequest.of("Putin Vladimir", 0.80), index);

        index.addAll(List.of(createEntity("2", "GADDAFI, Muammar", "Muammar", "GADDAFI")));
        List<MatchResult> results =
                engine.screen(ScreeningRequest.of("Qadhafi Moammar", 0.80), index);

        assertThat(results).extracting(r -> r.entity().id()).containsExactly("2");
    }

    @Test
    void shouldOnlyReturnEntitiesOfRequestedSources() {
        index.addAll(
                List.of(
                        createEntity("1", "GADDAFI, Muammar", "Muammar", "GADDAFI"),
                        createEntity(
                                "2",
                                "GADDAFI, Muammar",
                                "Muammar",
                                "GADDAFI",
                                ListSource.UN_CONSOLIDATED)));

        ScreeningRequest request =
                new ScreeningRequest(
                        "Qadhafi Moammar",
                        Optional.empty(),
                        Optional.of(Set.of(ListSource.UN_CONSOLIDATED)),
                        0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).extracting(r -> r.entity().id()).containsExactly("2");
    }

    @Test
    void shouldMatchAWordOnlyItsAlternateCodeShares() {
        // "Schmidt" and "Smith" share only an alternate Double Metaphone code
        index.addAll(List.of(createEntity("1", "Hans Smith", "Hans", "Smith")));

        List<MatchResult> results = engine.screen(ScreeningRequest.of("Hans Schmidt", 0.80), index);

        assertThat(results).hasSize(1);
    }

    private static SanctionedEntity createEntity(
            String id, String fullName, String givenName, String familyName) {
        return createEntity(id, fullName, givenName, familyName, ListSource.OFAC_SDN);
    }

    private static SanctionedEntity createEntity(
            String id, String fullName, String givenName, String familyName, ListSource source) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                source,
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
