package dev.sieve.match.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.dedup.CanonicalEntity;
import dev.sieve.core.dedup.DeduplicationConfig;
import dev.sieve.core.dedup.DeduplicationResult;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimilarityDeduplicatorTest {

    private final SimilarityDeduplicator deduplicator = new SimilarityDeduplicator();

    @Test
    void shouldMergeSamePersonAcrossTwoLists() {
        SanctionedEntity ofac = entity("ofac-1", "DOE, John", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John DOE", ListSource.EU_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalSourceEntities()).isEqualTo(2);
        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
        assertThat(result.mergedGroups()).isEqualTo(1);
        assertThat(result.duplicatesEliminated()).isEqualTo(1);

        CanonicalEntity canonical = result.canonicalEntityList().getFirst();
        assertThat(canonical.sourceCount()).isEqualTo(2);
        assertThat(canonical.listSources())
                .containsExactlyInAnyOrder(ListSource.OFAC_SDN, ListSource.EU_CONSOLIDATED);
    }

    @Test
    void shouldMergeSamePersonAcrossThreeLists() {
        SanctionedEntity ofac = entity("ofac-1", "DOE, John", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John DOE", ListSource.EU_CONSOLIDATED);
        SanctionedEntity un = entity("un-1", "John Doe", ListSource.UN_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu, un));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
        assertThat(result.mergedGroups()).isEqualTo(1);

        CanonicalEntity canonical = result.canonicalEntityList().getFirst();
        assertThat(canonical.sourceCount()).isEqualTo(3);
    }

    @Test
    void shouldNotMergeEntitiesFromSameSource() {
        SanctionedEntity a = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity b = entity("ofac-2", "John DOE", ListSource.OFAC_SDN);

        DeduplicationResult result = deduplicator.deduplicate(List.of(a, b));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
        assertThat(result.mergedGroups()).isEqualTo(0);
    }

    @Test
    void shouldNotMergeDifferentEntityTypes() {
        SanctionedEntity person =
                entity("ofac-1", "Acme Corp", ListSource.OFAC_SDN, EntityType.INDIVIDUAL);
        SanctionedEntity company =
                entity("eu-1", "Acme Corp", ListSource.EU_CONSOLIDATED, EntityType.ENTITY);

        DeduplicationResult result = deduplicator.deduplicate(List.of(person, company));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
        assertThat(result.mergedGroups()).isEqualTo(0);
    }

    @Test
    void shouldNotMergeDifferentPersons() {
        SanctionedEntity a = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity b = entity("eu-1", "Jane SMITH", ListSource.EU_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(a, b));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
        assertThat(result.mergedGroups()).isEqualTo(0);
    }

    @Test
    void shouldNotMergeDifferentFirstNamesSharingSurname() {
        // Whole-string Jaro-Winkler rates "doe john" vs "doe jane" at 0.90
        SanctionedEntity ofac = entity("ofac-1", "DOE, John", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "DOE, Jane", ListSource.EU_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
    }

    @Test
    void shouldNotMergeOnInitialAloneWithoutCorroboration() {
        SanctionedEntity ofac = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "J. Doe", ListSource.EU_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
    }

    @Test
    void tokenAlignedSimilarityShouldIgnoreOrderAndPenalizeMissingTokens() {
        String[] johnDoe = {"john", "doe"};

        assertThat(
                        SimilarityDeduplicator.tokenAlignedSimilarity(
                                johnDoe, new String[] {"doe", "john"}))
                .isEqualTo(1.0);
        assertThat(SimilarityDeduplicator.tokenAlignedSimilarity(johnDoe, new String[] {"doe"}))
                .isLessThan(0.75);
        assertThat(
                        SimilarityDeduplicator.tokenAlignedSimilarity(
                                johnDoe, new String[] {"jon", "doe"}))
                .isGreaterThan(0.95);
    }

    @Test
    void shouldMergeByIdentifierEvenWithWeakerNameMatch() {
        SanctionedEntity ofac =
                entityWithPassport("ofac-1", "John DOE", ListSource.OFAC_SDN, "AB123456");
        SanctionedEntity eu =
                entityWithPassport("eu-1", "J. Doe", ListSource.EU_CONSOLIDATED, "AB123456");

        // Name similarity of "john doe" vs "j. doe" may be below 0.90 but identifier match
        // should still trigger merge
        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
        assertThat(result.mergedGroups()).isEqualTo(1);
    }

    @Test
    void shouldMergeWithDobOverlap() {
        SanctionedEntity ofac =
                entityWithDob("ofac-1", "John Doe", ListSource.OFAC_SDN, LocalDate.of(1970, 1, 15));
        SanctionedEntity eu =
                entityWithDob(
                        "eu-1", "John Doe", ListSource.EU_CONSOLIDATED, LocalDate.of(1970, 1, 15));

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);

        CanonicalEntity canonical = result.canonicalEntityList().getFirst();
        assertThat(canonical.datesOfBirth()).contains(LocalDate.of(1970, 1, 15));
    }

    @Test
    void shouldHandleEmptyInput() {
        DeduplicationResult result = deduplicator.deduplicate(List.of());

        assertThat(result.totalSourceEntities()).isEqualTo(0);
        assertThat(result.totalCanonicalEntities()).isEqualTo(0);
    }

    @Test
    void shouldHandleSingleEntity() {
        SanctionedEntity sole = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);

        DeduplicationResult result = deduplicator.deduplicate(List.of(sole));

        assertThat(result.totalSourceEntities()).isEqualTo(1);
        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
        assertThat(result.mergedGroups()).isEqualTo(0);
    }

    @Test
    void shouldMergeCanonicalEntityMetadata() {
        SanctionedEntity ofac =
                new SanctionedEntity(
                        "ofac-1",
                        EntityType.INDIVIDUAL,
                        ListSource.OFAC_SDN,
                        name("DOE, John", "John", "DOE"),
                        List.of(name("Johnny DOE", null, null)),
                        List.of(),
                        List.of(),
                        List.of("US"),
                        List.of(),
                        List.of(LocalDate.of(1970, 1, 15)),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        Instant.now());

        SanctionedEntity eu =
                new SanctionedEntity(
                        "eu-1",
                        EntityType.INDIVIDUAL,
                        ListSource.EU_CONSOLIDATED,
                        name("John DOE", "John", "DOE"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("GB"),
                        List.of(),
                        List.of(),
                        List.of("London"),
                        null,
                        List.of(),
                        null,
                        Instant.now());

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);

        CanonicalEntity canonical = result.canonicalEntityList().getFirst();
        // Should have merged nationalities from both sources
        assertThat(canonical.nationalities()).containsExactlyInAnyOrder("US", "GB");
        // Should have merged places of birth
        assertThat(canonical.placesOfBirth()).contains("London");
        // Should have merged dates of birth
        assertThat(canonical.datesOfBirth()).contains(LocalDate.of(1970, 1, 15));
        // Should have all unique names
        assertThat(canonical.allNames()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldProvideCorrectEntityToCanonicalMapping() {
        SanctionedEntity ofac = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John Doe", ListSource.EU_CONSOLIDATED);
        SanctionedEntity unrelated = entity("eu-2", "Jane Smith", ListSource.EU_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu, unrelated));

        // ofac-1 and eu-1 should map to the same canonical
        String canonIdOfac = result.entityToCanonicalId().get("ofac-1");
        String canonIdEu = result.entityToCanonicalId().get("eu-1");
        String canonIdUnrelated = result.entityToCanonicalId().get("eu-2");

        assertThat(canonIdOfac).isEqualTo(canonIdEu);
        assertThat(canonIdOfac).isNotEqualTo(canonIdUnrelated);
    }

    @Test
    void compositeScoreShouldBeZeroForDifferentNames() {
        SanctionedEntity a = entity("1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity b = entity("2", "Jane SMITH", ListSource.EU_CONSOLIDATED);

        double score = deduplicator.compositeScore(a, b);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void compositeScoreShouldBeHighForSameNames() {
        SanctionedEntity a = entity("1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity b = entity("2", "John Doe", ListSource.EU_CONSOLIDATED);

        double score = deduplicator.compositeScore(a, b);
        assertThat(score).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void shouldRespectCustomConfig() {
        DeduplicationConfig strict = new DeduplicationConfig(0.99, 0.40, 0.20, 0.99, 3);
        SimilarityDeduplicator strictDedup = new SimilarityDeduplicator(strict);

        // "Jon" vs "John" is a minor spelling difference: merged by default, not when strict
        SanctionedEntity ofac = entity("ofac-1", "Jon DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John DOE", ListSource.EU_CONSOLIDATED);

        assertThat(deduplicator.deduplicate(List.of(ofac, eu)).totalCanonicalEntities())
                .isEqualTo(1);
        assertThat(strictDedup.deduplicate(List.of(ofac, eu)).totalCanonicalEntities())
                .isEqualTo(2);
    }

    @Test
    void shouldTreatReorderedNamesAsIdenticalEvenWithStrictConfig() {
        DeduplicationConfig strict = new DeduplicationConfig(0.99, 0.40, 0.20, 0.99, 3);
        SimilarityDeduplicator strictDedup = new SimilarityDeduplicator(strict);

        SanctionedEntity ofac = entity("ofac-1", "DOE, John", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John DOE", ListSource.EU_CONSOLIDATED);

        assertThat(strictDedup.deduplicate(List.of(ofac, eu)).totalCanonicalEntities())
                .isEqualTo(1);
    }

    @Test
    void shouldMergeReorderedNamesWithoutStructuredNameParts() {
        // Many providers only populate fullName, in "LAST, First" form
        SanctionedEntity ofac = unstructured("ofac-1", "DOE, John", ListSource.OFAC_SDN);
        SanctionedEntity un = unstructured("un-1", "John Doe", ListSource.UN_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, un));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
    }

    @Test
    void shouldMergeNamesDifferingOnlyInDiacriticsAndPunctuation() {
        SanctionedEntity eu =
                unstructured("eu-1", "José MÜLLER-García", ListSource.EU_CONSOLIDATED);
        SanctionedEntity uk = unstructured("uk-1", "Jose Muller Garcia", ListSource.UK_HMT);

        DeduplicationResult result = deduplicator.deduplicate(List.of(eu, uk));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
    }

    @Test
    void shouldNotMergeSameNameWithConflictingDatesOfBirth() {
        SanctionedEntity ofac =
                entityWithDob("ofac-1", "John Doe", ListSource.OFAC_SDN, LocalDate.of(1970, 1, 15));
        SanctionedEntity eu =
                entityWithDob(
                        "eu-1", "John Doe", ListSource.EU_CONSOLIDATED, LocalDate.of(1985, 6, 2));

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
        assertThat(deduplicator.compositeScore(ofac, eu)).isEqualTo(0.0);
    }

    @Test
    void shouldMergeWhenDatesOfBirthShareYearOnly() {
        // Year-only DOBs are commonly stored as 1 January
        SanctionedEntity un =
                entityWithDob(
                        "un-1", "John Doe", ListSource.UN_CONSOLIDATED, LocalDate.of(1970, 1, 1));
        SanctionedEntity eu =
                entityWithDob(
                        "eu-1", "John Doe", ListSource.EU_CONSOLIDATED, LocalDate.of(1970, 1, 15));

        DeduplicationResult result = deduplicator.deduplicate(List.of(un, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
    }

    @Test
    void shouldNeverMergeTwoEntitiesFromSameSourceTransitively() {
        // eu-1 matches both OFAC entries; transitive closure must not join ofac-1 and ofac-2,
        // which OFAC lists as distinct people
        SanctionedEntity ofac1 = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu = entity("eu-1", "John Doe", ListSource.EU_CONSOLIDATED);
        SanctionedEntity ofac2 = entity("ofac-2", "John DOE", ListSource.OFAC_SDN);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac1, eu, ofac2));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
        assertThat(result.entityToCanonicalId().get("ofac-1"))
                .isNotEqualTo(result.entityToCanonicalId().get("ofac-2"));
        for (CanonicalEntity canonical : result.canonicalEntityList()) {
            assertThat(canonical.sourceEntities().getOrDefault(ListSource.OFAC_SDN, List.of()))
                    .hasSizeLessThanOrEqualTo(1);
        }
    }

    @Test
    void shouldPreferStrongestMatchWhenOneEntityMatchesTwoCandidates() {
        // eu-1 shares a DOB with ofac-2 only, so it belongs with ofac-2 regardless of order
        SanctionedEntity ofac1 = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity eu =
                entityWithDob(
                        "eu-1", "John Doe", ListSource.EU_CONSOLIDATED, LocalDate.of(1970, 1, 15));
        SanctionedEntity ofac2 =
                entityWithDob("ofac-2", "John DOE", ListSource.OFAC_SDN, LocalDate.of(1970, 1, 15));

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac1, eu, ofac2));

        assertThat(result.entityToCanonicalId().get("eu-1"))
                .isEqualTo(result.entityToCanonicalId().get("ofac-2"))
                .isNotEqualTo(result.entityToCanonicalId().get("ofac-1"));
    }

    @Test
    void shouldNotTransitivelyMergeClustersWithConflictingDatesOfBirth() {
        // un-1 has no DOB and matches both, but ofac-1 and eu-1 are provably different people
        SanctionedEntity ofac =
                entityWithDob("ofac-1", "John Doe", ListSource.OFAC_SDN, LocalDate.of(1970, 1, 15));
        SanctionedEntity un = entity("un-1", "John Doe", ListSource.UN_CONSOLIDATED);
        SanctionedEntity eu =
                entityWithDob(
                        "eu-1", "John Doe", ListSource.EU_CONSOLIDATED, LocalDate.of(1985, 6, 2));

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, un, eu));

        assertThat(result.entityToCanonicalId().get("ofac-1"))
                .isNotEqualTo(result.entityToCanonicalId().get("eu-1"));
    }

    @Test
    void shouldMatchIdentifiersIgnoringFormatting() {
        SanctionedEntity ofac =
                entityWithPassport("ofac-1", "John DOE", ListSource.OFAC_SDN, "AB 123-456");
        SanctionedEntity eu =
                entityWithPassport("eu-1", "J. Doe", ListSource.EU_CONSOLIDATED, "ab123456");

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
    }

    @Test
    void shouldNotMatchIdentifiersIssuedByDifferentCountries() {
        SanctionedEntity ofac =
                entityWithPassport(
                        "ofac-1",
                        "John DOE",
                        ListSource.OFAC_SDN,
                        new Identifier(IdentifierType.PASSPORT, "123456", "US", null));
        SanctionedEntity eu =
                entityWithPassport(
                        "eu-1",
                        "J. Doe",
                        ListSource.EU_CONSOLIDATED,
                        new Identifier(IdentifierType.PASSPORT, "123456", "RU", null));

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
    }

    @Test
    void shouldNotMergeUnrelatedNamesOnSharedIdentifierAlone() {
        SanctionedEntity ofac =
                entityWithPassport("ofac-1", "John DOE", ListSource.OFAC_SDN, "AB123456");
        SanctionedEntity eu =
                entityWithPassport("eu-1", "Ahmad KARIMI", ListSource.EU_CONSOLIDATED, "AB123456");

        assertThat(deduplicator.compositeScore(ofac, eu)).isEqualTo(0.0);
    }

    @Test
    void shouldAssignCanonicalIdsDeterministicallyInInputOrder() {
        SanctionedEntity a = entity("ofac-1", "John DOE", ListSource.OFAC_SDN);
        SanctionedEntity b = entity("eu-9", "Jane SMITH", ListSource.EU_CONSOLIDATED);
        SanctionedEntity c = entity("un-5", "Ali HASSAN", ListSource.UN_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(a, b, c));

        assertThat(result.entityToCanonicalId())
                .containsEntry("ofac-1", "canonical-0")
                .containsEntry("eu-9", "canonical-1")
                .containsEntry("un-5", "canonical-2");
    }

    // --- Helper methods ---

    private static SanctionedEntity entity(String id, String fullName, ListSource source) {
        return entity(id, fullName, source, EntityType.INDIVIDUAL);
    }

    private static SanctionedEntity entity(
            String id, String fullName, ListSource source, EntityType type) {
        // Parse "LAST, First" format if present
        String given = null;
        String family = null;
        if (fullName.contains(",")) {
            String[] parts = fullName.split(",", 2);
            family = parts[0].strip();
            given = parts[1].strip();
        } else if (fullName.contains(" ")) {
            String[] parts = fullName.split("\\s+", 2);
            given = parts[0].strip();
            family = parts.length > 1 ? parts[1].strip() : null;
        }
        return new SanctionedEntity(
                id,
                type,
                source,
                name(fullName, given, family),
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
    }

    private static SanctionedEntity unstructured(String id, String fullName, ListSource source) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                source,
                name(fullName, null, null),
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
    }

    private static SanctionedEntity entityWithPassport(
            String id, String fullName, ListSource source, String passportNumber) {
        return entityWithPassport(
                id,
                fullName,
                source,
                new Identifier(IdentifierType.PASSPORT, passportNumber, null, null));
    }

    private static SanctionedEntity entityWithPassport(
            String id, String fullName, ListSource source, Identifier passport) {
        String given = null;
        String family = null;
        if (fullName.contains(",")) {
            String[] parts = fullName.split(",", 2);
            family = parts[0].strip();
            given = parts[1].strip();
        } else if (fullName.contains(" ")) {
            String[] parts = fullName.split("\\s+", 2);
            given = parts[0].strip();
            family = parts.length > 1 ? parts[1].strip() : null;
        }
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                source,
                name(fullName, given, family),
                List.of(),
                List.of(),
                List.of(passport),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                Instant.now());
    }

    private static SanctionedEntity entityWithDob(
            String id, String fullName, ListSource source, LocalDate dob) {
        String given = null;
        String family = null;
        if (fullName.contains(" ")) {
            String[] parts = fullName.split("\\s+", 2);
            given = parts[0].strip();
            family = parts.length > 1 ? parts[1].strip() : null;
        }
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                source,
                name(fullName, given, family),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(dob),
                List.of(),
                null,
                List.of(),
                null,
                Instant.now());
    }

    private static NameInfo name(String fullName, String givenName, String familyName) {
        return new NameInfo(
                fullName, givenName, familyName, null, null, NameType.PRIMARY, null, null);
    }
}
