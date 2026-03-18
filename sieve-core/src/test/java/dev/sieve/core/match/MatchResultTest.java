package dev.sieve.core.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchResultTest {

    private static final SanctionedEntity ENTITY = createEntity("1");

    @Test
    void shouldCreateValidResult() {
        MatchResult result = new MatchResult(ENTITY, 0.95, "primaryName", "EXACT");

        assertThat(result.entity()).isEqualTo(ENTITY);
        assertThat(result.score()).isEqualTo(0.95);
        assertThat(result.matchedField()).isEqualTo("primaryName");
        assertThat(result.matchAlgorithm()).isEqualTo("EXACT");
    }

    @Test
    void shouldThrowWhenScoreIsNegative() {
        assertThatThrownBy(() -> new MatchResult(ENTITY, -0.1, "primaryName", "EXACT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Score");
    }

    @Test
    void shouldThrowWhenScoreExceedsOne() {
        assertThatThrownBy(() -> new MatchResult(ENTITY, 1.1, "primaryName", "EXACT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Score");
    }

    @Test
    void shouldThrowWhenEntityIsNull() {
        assertThatThrownBy(() -> new MatchResult(null, 0.5, "primaryName", "EXACT"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSortByScoreDescending() {
        MatchResult high = new MatchResult(ENTITY, 0.95, "primaryName", "EXACT");
        MatchResult mid = new MatchResult(ENTITY, 0.80, "alias[0]", "FUZZY");
        MatchResult low = new MatchResult(ENTITY, 0.60, "alias[1]", "FUZZY");

        List<MatchResult> results = new ArrayList<>(List.of(low, high, mid));
        Collections.sort(results);

        assertThat(results).containsExactly(high, mid, low);
    }

    private static SanctionedEntity createEntity(String id) {
        NameInfo name =
                new NameInfo(
                        "Test", null, null, null, null, NameType.PRIMARY, NameStrength.STRONG,
                        ScriptType.LATIN);
        return new SanctionedEntity(
                id, EntityType.INDIVIDUAL, ListSource.OFAC_SDN, name, null, null, null, null, null,
                null, null, null, null, null, Instant.now());
    }
}
