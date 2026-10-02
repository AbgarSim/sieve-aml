package dev.sieve.ingest.ca;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CanadaConsolidatedProviderTest {

    private List<SanctionedEntity> entities;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ca_sema_test_sample.xml")) {
            entities = new CanadaConsolidatedProvider().parseResponse(in.readAllBytes());
        }
    }

    @Test
    void shouldParseRecordsWhenElementNamesAreBilingual() {
        assertThat(entities).hasSize(4);
    }

    @Test
    void shouldBuildIndividualWhenRecordHasPersonNames() {
        SanctionedEntity person = byName("Aleksandr Grigoryevich Lukashenko");

        assertThat(person.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(person.primaryName().givenName()).isEqualTo("Aleksandr Grigoryevich");
        assertThat(person.primaryName().familyName()).isEqualTo("Lukashenko");
        assertThat(person.datesOfBirth()).containsExactly(LocalDate.of(1954, 8, 30));
        assertThat(person.nationalities()).containsExactly("Belarus");
        assertThat(person.listedDate()).isEqualTo(Instant.parse("2020-09-28T00:00:00Z"));
        assertThat(person.programs())
                .singleElement()
                .satisfies(p -> assertThat(p.code()).isEqualTo("Belarus Schedule 1, Part 1"));
        assertThat(person.id()).isEqualTo("ca-belarus-1-part-1-8");
    }

    @Test
    void shouldKeepYearAndAliasesWhenBirthDateIsPartial() {
        SanctionedEntity person = byName("Khazalbek Bakhtibekovich Atabekov");

        assertThat(person.datesOfBirth()).containsExactly(LocalDate.of(1972, 1, 1));
        assertThat(person.aliases())
                .extracting(a -> a.fullName())
                .containsExactly("Khazalbek Atabekov", "Hazalbek Atabekau");
        assertThat(person.remarks()).isEqualTo("Commander");
    }

    @Test
    void shouldBuildEntityWhenRecordNamesAnOrganisation() {
        SanctionedEntity entity = byName("Example Defence Plant JSC");

        assertThat(entity.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(entity.identifiers()).isEmpty();
    }

    @Test
    void shouldBuildVesselWithImoAndNoBirthDateWhenRecordIsAShip() {
        SanctionedEntity ship = byName("EXAMPLE STAR");

        assertThat(ship.entityType()).isEqualTo(EntityType.VESSEL);
        assertThat(ship.identifiers())
                .singleElement()
                .satisfies(
                        id -> {
                            assertThat(id.type()).isEqualTo(IdentifierType.IMO_NUMBER);
                            assertThat(id.value()).isEqualTo("9123456");
                        });
        assertThat(ship.datesOfBirth()).isEmpty();
        assertThat(ship.remarks()).isEqualTo("Crude oil tanker");
    }

    @Test
    void shouldReadEnglishPartWhenValueIsBilingual() {
        assertThat(CanadaConsolidatedProvider.englishPart("Russia / Russie")).isEqualTo("Russia");
        assertThat(CanadaConsolidatedProvider.englishName("Item-NumeroDarticle")).isEqualTo("item");
    }

    private SanctionedEntity byName(String name) {
        return entities.stream()
                .filter(e -> e.primaryName().fullName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
