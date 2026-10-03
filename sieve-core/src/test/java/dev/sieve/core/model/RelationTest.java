package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class RelationTest {

    @Test
    void shouldCreateOwnershipWithShareAndDates() {
        Relation relation =
                new Relation(
                        RelationType.OWNERSHIP,
                        "eu-EU.10.42",
                        "shareholder",
                        51.0,
                        LocalDate.of(2019, 1, 1),
                        null);

        assertThat(relation.type()).isEqualTo(RelationType.OWNERSHIP);
        assertThat(relation.sharePercentage()).isEqualTo(51.0);
    }

    @Test
    void shouldCreateMinimalRelation() {
        Relation relation = Relation.of(RelationType.LINKED, "ofac-sdn-1001");

        assertThat(relation.role()).isNull();
        assertThat(relation.sharePercentage()).isNull();
    }

    @Test
    void shouldThrowWhenTargetIsBlank() {
        assertThatThrownBy(() -> Relation.of(RelationType.FAMILY, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowWhenShareIsOutOfRange() {
        assertThatThrownBy(() -> new Relation(RelationType.OWNERSHIP, "x", null, 120.0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowWhenEndDateIsBeforeStartDate() {
        assertThatThrownBy(
                        () ->
                                new Relation(
                                        RelationType.DIRECTORSHIP,
                                        "x",
                                        null,
                                        null,
                                        LocalDate.of(2020, 1, 1),
                                        LocalDate.of(2019, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldParseRelationTypeFromDisplayName() {
        assertThat(RelationType.fromString("directorship")).isEqualTo(RelationType.DIRECTORSHIP);
    }
}
