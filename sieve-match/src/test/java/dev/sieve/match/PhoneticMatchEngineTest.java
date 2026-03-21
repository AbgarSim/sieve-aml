package dev.sieve.match;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.*;
import java.util.List;
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
    void shouldMatchSingleTokenAgainstComponents() {
        index.addAll(List.of(createEntity("1", "SCHMIDT, Hans", "Hans", "SCHMIDT")));

        ScreeningRequest request = ScreeningRequest.of("Smith", 0.80);
        List<MatchResult> results = engine.screen(request, index);

        assertThat(results).isNotEmpty();
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
