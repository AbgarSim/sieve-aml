package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SanctionedEntityTest {

    private static final NameInfo PRIMARY_NAME =
            new NameInfo(
                    "DOE, John",
                    "John",
                    "DOE",
                    null,
                    null,
                    NameType.PRIMARY,
                    NameStrength.STRONG,
                    ScriptType.LATIN);

    @Test
    void shouldCreateValidEntity() {
        SanctionedEntity entity =
                new SanctionedEntity(
                        "12345",
                        EntityType.INDIVIDUAL,
                        ListSource.OFAC_SDN,
                        PRIMARY_NAME,
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
                        Instant.now());

        assertThat(entity.id()).isEqualTo("12345");
        assertThat(entity.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(entity.listSource()).isEqualTo(ListSource.OFAC_SDN);
        assertThat(entity.primaryName().fullName()).isEqualTo("DOE, John");
    }

    @Test
    void shouldThrowWhenIdIsNull() {
        assertThatThrownBy(
                        () ->
                                new SanctionedEntity(
                                        null,
                                        EntityType.INDIVIDUAL,
                                        ListSource.OFAC_SDN,
                                        PRIMARY_NAME,
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
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    void shouldThrowWhenEntityTypeIsNull() {
        assertThatThrownBy(
                        () ->
                                new SanctionedEntity(
                                        "1",
                                        null,
                                        ListSource.OFAC_SDN,
                                        PRIMARY_NAME,
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
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entityType");
    }

    @Test
    void shouldDefaultNullListsToEmpty() {
        SanctionedEntity entity =
                new SanctionedEntity(
                        "1",
                        EntityType.INDIVIDUAL,
                        ListSource.OFAC_SDN,
                        PRIMARY_NAME,
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
                        null);

        assertThat(entity.aliases()).isEmpty();
        assertThat(entity.addresses()).isEmpty();
        assertThat(entity.identifiers()).isEmpty();
        assertThat(entity.nationalities()).isEmpty();
        assertThat(entity.citizenships()).isEmpty();
        assertThat(entity.datesOfBirth()).isEmpty();
        assertThat(entity.placesOfBirth()).isEmpty();
        assertThat(entity.programs()).isEmpty();
    }

    @Test
    void shouldMakeDefensiveCopiesOfLists() {
        List<String> nationalities = new ArrayList<>(List.of("US", "UK"));

        SanctionedEntity entity =
                new SanctionedEntity(
                        "1",
                        EntityType.INDIVIDUAL,
                        ListSource.OFAC_SDN,
                        PRIMARY_NAME,
                        null,
                        null,
                        null,
                        nationalities,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        nationalities.add("FR");
        assertThat(entity.nationalities()).hasSize(2);
        assertThat(entity.nationalities()).containsExactly("US", "UK");
    }
}
