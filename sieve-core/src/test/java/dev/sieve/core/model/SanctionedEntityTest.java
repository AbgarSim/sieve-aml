package dev.sieve.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    @Test
    void shouldDefaultToSanctionTopicWhenCreatedFromSanctionsList() {
        SanctionedEntity entity = minimalEntity();

        assertThat(entity.topics()).containsExactly(RiskTopic.SANCTION);
        assertThat(entity.hasTopic(RiskTopic.SANCTION)).isTrue();
        assertThat(entity.hasTopic(RiskTopic.PEP)).isFalse();
        assertThat(entity.relations()).isEmpty();
    }

    @Test
    void shouldKeepTopicsAndRelationsWhenGivenExplicitly() {
        Relation spouse = new Relation(RelationType.FAMILY, "un-2", "spouse", null, null, null);

        SanctionedEntity entity =
                withTopicsAndRelations(Set.of(RiskTopic.PEP, RiskTopic.SANCTION), List.of(spouse));

        assertThat(entity.topics()).containsExactly(RiskTopic.SANCTION, RiskTopic.PEP);
        assertThat(entity.relations()).containsExactly(spouse);
    }

    @Test
    void shouldUseEmptyTopicsAndRelationsWhenNull() {
        SanctionedEntity entity = withTopicsAndRelations(null, null);

        assertThat(entity.topics()).isEmpty();
        assertThat(entity.relations()).isEmpty();
    }

    @Test
    void shouldMakeTopicsAndRelationsUnmodifiable() {
        SanctionedEntity entity =
                withTopicsAndRelations(
                        Set.of(RiskTopic.PEP),
                        List.of(Relation.of(RelationType.ASSOCIATE, "uk-1")));

        assertThatThrownBy(() -> entity.topics().add(RiskTopic.CRIME))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> entity.relations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static SanctionedEntity minimalEntity() {
        return new SanctionedEntity(
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
    }

    private static SanctionedEntity withTopicsAndRelations(
            Set<RiskTopic> topics, List<Relation> relations) {
        return new SanctionedEntity(
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
                null,
                topics,
                relations);
    }

    @Test
    void shouldHaveNoGenderDeathReasonsOrVesselUnlessGiven() {
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

        assertThat(entity.gender()).isNull();
        assertThat(entity.deceased()).isNull();
        assertThat(entity.listingReasons()).isEmpty();
        assertThat(entity.vessel()).isNull();
    }

    @Test
    void shouldKeepGenderDeathReasonsAndVesselAcrossCopies() {
        VesselDetails vessel = new VesselDetails("Panama", "Bulk Carrier", "3EXY9", null, 52_000);
        SanctionedEntity entity =
                new SanctionedEntity(
                                "3001",
                                EntityType.VESSEL,
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
                                Instant.now())
                        .withGender(Gender.FEMALE)
                        .withDeceased(Boolean.TRUE)
                        .withListingReasons(List.of("Statement of reasons"))
                        .withVessel(vessel);

        SanctionedEntity copy = entity.withRelations(List.of()).withProvenance(List.of());

        assertThat(copy.gender()).isEqualTo(Gender.FEMALE);
        assertThat(copy.deceased()).isTrue();
        assertThat(copy.listingReasons()).containsExactly("Statement of reasons");
        assertThat(copy.vessel()).isEqualTo(vessel);
        assertThat(copy.withListingReasons(null).listingReasons()).isEmpty();
    }
}
