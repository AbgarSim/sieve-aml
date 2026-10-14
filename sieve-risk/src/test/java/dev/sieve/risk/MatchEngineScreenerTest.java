package dev.sieve.risk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchEngineScreenerTest {

    @Test
    void shouldReturnBestHitWhenNameMatchesListedEntity() {
        InMemoryEntityIndex index = new InMemoryEntityIndex();
        index.add(entity("1", "BANCO NACIONAL DE CUBA"));
        index.add(entity("2", "AEROCARIBBEAN AIRLINES"));
        MatchEngineScreener screener =
                new MatchEngineScreener(
                        new CompositeMatchEngine(
                                List.of(new ExactMatchEngine(), new FuzzyMatchEngine())),
                        index,
                        0.85);

        assertThat(screener.screen("Banco Nacional de Cuba"))
                .hasValueSatisfying(
                        hit -> {
                            assertThat(hit.matchedName()).isEqualTo("BANCO NACIONAL DE CUBA");
                            assertThat(hit.source()).isEqualTo("OFAC_SDN");
                            assertThat(hit.score()).isGreaterThanOrEqualTo(0.85);
                        });
        assertThat(screener.screen("Corner Bakery")).isEmpty();
        assertThat(screener.screen(" ")).isEmpty();
    }

    private static SanctionedEntity entity(String id, String name) {
        return new SanctionedEntity(
                id,
                EntityType.ENTITY,
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
                Instant.parse("2026-10-01T00:00:00Z"));
    }
}
