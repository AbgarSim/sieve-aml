package dev.sieve.core.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalEntityTest {

    @Test
    void singletonShouldWrapSingleEntity() {
        SanctionedEntity entity = testEntity("ofac-1", "John DOE", ListSource.OFAC_SDN);

        CanonicalEntity canonical = CanonicalEntity.singleton(entity);

        assertThat(canonical.canonicalId()).isEqualTo("canonical-ofac-1");
        assertThat(canonical.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(canonical.primaryName().fullName()).isEqualTo("John DOE");
        assertThat(canonical.sourceCount()).isEqualTo(1);
        assertThat(canonical.listSources()).containsExactly(ListSource.OFAC_SDN);
        assertThat(canonical.sourceEntityIds()).containsExactly("ofac-1");
    }

    @Test
    void mergeShouldCombineEntitiesFromDifferentSources() {
        SanctionedEntity ofac = new SanctionedEntity(
                "ofac-1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN,
                name("DOE, John", "John", "DOE"),
                List.of(name("Johnny D", null, null)),
                List.of(),
                List.of(new Identifier(IdentifierType.PASSPORT, "AB123", null, null)),
                List.of("US"), List.of(),
                List.of(LocalDate.of(1970, 1, 15)), List.of(),
                null,
                List.of(new SanctionsProgram("SDGT", "Global Terrorism", ListSource.OFAC_SDN)),
                null, Instant.now());

        SanctionedEntity eu = new SanctionedEntity(
                "eu-1", EntityType.INDIVIDUAL, ListSource.EU_CONSOLIDATED,
                name("John DOE", "John", "DOE"),
                List.of(),
                List.of(),
                List.of(new Identifier(IdentifierType.NATIONAL_ID, "NID456", "GB", null)),
                List.of("GB"), List.of(),
                List.of(), List.of("London"),
                null,
                List.of(new SanctionsProgram("EU-TER", "EU Terrorism", ListSource.EU_CONSOLIDATED)),
                null, Instant.now());

        CanonicalEntity canonical = CanonicalEntity.merge("canonical-0", List.of(ofac, eu));

        assertThat(canonical.canonicalId()).isEqualTo("canonical-0");
        assertThat(canonical.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(canonical.sourceCount()).isEqualTo(2);
        assertThat(canonical.listSources()).containsExactlyInAnyOrder(
                ListSource.OFAC_SDN, ListSource.EU_CONSOLIDATED);

        // Merged metadata
        assertThat(canonical.nationalities()).containsExactlyInAnyOrder("US", "GB");
        assertThat(canonical.placesOfBirth()).contains("London");
        assertThat(canonical.datesOfBirth()).contains(LocalDate.of(1970, 1, 15));
        assertThat(canonical.identifiers()).hasSize(2);
        assertThat(canonical.programs()).hasSize(2);

        // All unique names collected
        assertThat(canonical.allNames()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void mergeShouldDeduplicateIdenticalNames() {
        SanctionedEntity a = testEntity("1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity b = testEntity("2", "John DOE", ListSource.EU_CONSOLIDATED);

        CanonicalEntity canonical = CanonicalEntity.merge("c-0", List.of(a, b));

        // "John DOE" appears once, not twice
        long johnDoeCount = canonical.allNames().stream()
                .filter(n -> n.fullName().equalsIgnoreCase("john doe"))
                .count();
        assertThat(johnDoeCount).isEqualTo(1);
    }

    @Test
    void mergeShouldChooseMostCompletePrimaryName() {
        // Entity A has structured name components
        SanctionedEntity a = new SanctionedEntity(
                "1", EntityType.INDIVIDUAL, ListSource.OFAC_SDN,
                name("DOE, John Michael", "John", "DOE"),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of(), null, Instant.now());

        // Entity B has only full name, no components
        SanctionedEntity b = new SanctionedEntity(
                "2", EntityType.INDIVIDUAL, ListSource.EU_CONSOLIDATED,
                new NameInfo("John Doe", null, null, null, null, NameType.PRIMARY, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of(), null, Instant.now());

        CanonicalEntity canonical = CanonicalEntity.merge("c-0", List.of(a, b));

        // Should pick the more complete name (with givenName + familyName)
        assertThat(canonical.primaryName().givenName()).isEqualTo("John");
        assertThat(canonical.primaryName().familyName()).isEqualTo("DOE");
    }

    @Test
    void mergeShouldRejectDifferentEntityTypes() {
        SanctionedEntity person = testEntity("1", "Acme", ListSource.OFAC_SDN,
                EntityType.INDIVIDUAL);
        SanctionedEntity org = testEntity("2", "Acme", ListSource.EU_CONSOLIDATED,
                EntityType.ENTITY);

        assertThatThrownBy(() -> CanonicalEntity.merge("c-0", List.of(person, org)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different types");
    }

    @Test
    void mergeShouldRejectEmptyList() {
        assertThatThrownBy(() -> CanonicalEntity.merge("c-0", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorShouldRejectEmptySourceEntities() {
        assertThatThrownBy(() -> new CanonicalEntity(
                "c-0", EntityType.INDIVIDUAL,
                name("Test", null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceEntities must not be empty");
    }

    // --- Helper methods ---

    private static SanctionedEntity testEntity(String id, String fullName, ListSource source) {
        return testEntity(id, fullName, source, EntityType.INDIVIDUAL);
    }

    private static SanctionedEntity testEntity(
            String id, String fullName, ListSource source, EntityType type) {
        return new SanctionedEntity(
                id, type, source,
                name(fullName, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of(), null, Instant.now());
    }

    private static NameInfo name(String fullName, String givenName, String familyName) {
        return new NameInfo(fullName, givenName, familyName, null, null,
                NameType.PRIMARY, null, null);
    }
}
