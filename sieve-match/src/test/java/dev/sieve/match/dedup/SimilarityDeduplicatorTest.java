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
import java.util.ArrayList;
import java.util.Arrays;
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
    void shouldMergeOnePersonListedInDifferentScriptsAndTransliterations() {
        // One head of state as eleven lists write him: Cyrillic with a Latin alias, a Swedish and
        // a French transliteration, and surname first with no structured name parts
        LocalDate born = LocalDate.of(1952, 10, 7);
        SanctionedEntity eu =
                person(
                        "eu-1",
                        "Влади́мир Влади́мирович ПУТИН",
                        ListSource.EU_CONSOLIDATED,
                        born,
                        "Vladimir Vladimirovich PUTIN");
        SanctionedEntity travelBans =
                person(
                        "eu-tb-1",
                        "Vladimir Vladimirovitj PUTIN",
                        ListSource.EU_TRAVEL_BANS,
                        null,
                        "Vladimir Vladimirovich PUTIN");
        SanctionedEntity canada =
                person(
                        "ca-1",
                        "Vladimir Vladimirovich PUTIN",
                        ListSource.CA_CONSOLIDATED,
                        born,
                        "Владимир Владимирович Путин");
        SanctionedEntity france =
                person("fr-1", "Vladimir Vladimirovich POUTINE", ListSource.FR_TRESOR, null);
        SanctionedEntity ofac =
                person(
                        "ofac-1",
                        "PUTIN, Vladimir Vladimirovich",
                        ListSource.OFAC_SDN,
                        born,
                        "PUTIN, Vladimir");

        DeduplicationResult result =
                deduplicator.deduplicate(List.of(eu, travelBans, canada, france, ofac));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
        assertThat(result.canonicalEntityList().getFirst().listSources())
                .containsExactlyInAnyOrder(
                        ListSource.EU_CONSOLIDATED,
                        ListSource.EU_TRAVEL_BANS,
                        ListSource.CA_CONSOLIDATED,
                        ListSource.FR_TRESOR,
                        ListSource.OFAC_SDN);
    }

    @Test
    void shouldNotMergeSimilarNamesWithoutCorroboration() {
        // Token for token these score 0.93, but with no date of birth to compare, nothing says
        // they are one person rather than two
        SanctionedEntity ofac = entity("ofac-1", "Ivan Petrov", ListSource.OFAC_SDN);
        SanctionedEntity un = entity("un-1", "Petr Ivanov", ListSource.UN_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, un));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
        assertThat(deduplicator.compositeScore(ofac, un)).isEqualTo(0.0);
    }

    @Test
    void shouldMergeSimilarNamesWhenDatesOfBirthAgree() {
        // A French transliteration scores 0.93 against the English one: enough once the date of
        // birth corroborates it, not on its own
        LocalDate born = LocalDate.of(1961, 6, 1);
        SanctionedEntity ofac = entity("ofac-1", "Yevgeny Prigozhin", ListSource.OFAC_SDN);
        SanctionedEntity fr = entity("fr-1", "Evgeny Prigojine", ListSource.FR_TRESOR);

        assertThat(deduplicator.deduplicate(List.of(ofac, fr)).totalCanonicalEntities())
                .isEqualTo(2);
        assertThat(
                        deduplicator
                                .deduplicate(
                                        List.of(
                                                person(
                                                        "ofac-1",
                                                        "Yevgeny Prigozhin",
                                                        ListSource.OFAC_SDN,
                                                        born),
                                                person(
                                                        "fr-1",
                                                        "Evgeny Prigojine",
                                                        ListSource.FR_TRESOR,
                                                        born)))
                                .totalCanonicalEntities())
                .isEqualTo(1);
    }

    @Test
    void shouldStillMatchInsideBlocksTooLargeToCompareInFull() {
        // More "Mohammed ..." entries than one block may hold: the block is split by finer keys,
        // and the one true pair still meets in a block of its own
        List<SanctionedEntity> entities = new ArrayList<>();
        for (int i = 0; i < SimilarityDeduplicator.MAX_BLOCK_SIZE + 5; i++) {
            entities.add(unstructured("ofac-" + i, "Mohammed Person" + i, ListSource.OFAC_SDN));
        }
        entities.add(unstructured("eu-1", "Mohammed Person7", ListSource.EU_CONSOLIDATED));

        DeduplicationResult result = deduplicator.deduplicate(entities);

        assertThat(result.totalCanonicalEntities()).isEqualTo(entities.size() - 1);
        assertThat(result.entityToCanonicalId().get("eu-1"))
                .isEqualTo(result.entityToCanonicalId().get("ofac-7"));
    }

    @Test
    void shouldMeetSpellingVariantsOfACommonNameWithinTheirBirthYear() {
        // A block of "Mohammed ..." too large to compare in full falls back to exact spellings,
        // which "Mohamed" is not; the birth year narrows it to a block the variants can meet in
        List<SanctionedEntity> entities = new ArrayList<>();
        for (int i = 0; i < SimilarityDeduplicator.MAX_BLOCK_SIZE + 5; i++) {
            entities.add(
                    person(
                            "ofac-" + i,
                            "Mohammed Person" + i,
                            ListSource.OFAC_SDN,
                            LocalDate.of(1950 + i % 50, 1, 1)));
        }
        entities.add(
                person(
                        "eu-1",
                        "Mohamed Person7",
                        ListSource.EU_CONSOLIDATED,
                        LocalDate.of(1957, 1, 1)));

        DeduplicationResult result = deduplicator.deduplicate(entities);

        assertThat(result.totalCanonicalEntities()).isEqualTo(entities.size() - 1);
        assertThat(result.entityToCanonicalId().get("eu-1"))
                .isEqualTo(result.entityToCanonicalId().get("ofac-7"));
    }

    @Test
    void shouldMeetOnASharedIdentifierWhateverTheNames() {
        // No name part of these two starts alike, so only the passport brings them together
        SanctionedEntity ofac =
                entityWithPassport("ofac-1", "Qasem Soleimani", ListSource.OFAC_SDN, "K 1234567");
        SanctionedEntity eu =
                entityWithPassport(
                        "eu-1", "Ghasem Suleymani", ListSource.EU_CONSOLIDATED, "K1234567");

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(1);
    }

    @Test
    void shouldNotTreatAValueSharedByManyEntitiesAsAnIdentifier() {
        // Some lists store a gender flag as an identifier; shared by every man on them, it must not
        // vouch for two different men with vaguely similar names ("ali hassan" vs "ali hussein"
        // score about 0.84, enough with an identifier, not without)
        Identifier male = new Identifier(IdentifierType.OTHER, "Male", null, null);
        List<SanctionedEntity> entities = new ArrayList<>();
        entities.add(entityWithPassport("ofac-1", "Ali Hassan", ListSource.OFAC_SDN, male));
        entities.add(entityWithPassport("eu-1", "Ali Hussein", ListSource.EU_CONSOLIDATED, male));
        String[] given = {"Anna", "Boris", "Carla", "Dmitri", "Elena", "Farid", "Greta"};
        String[] family = {
            "Holt", "Ivarsen", "Jurek", "Kowal", "Lindqvist", "Marchetti", "Nowak", "Oyelaran"
        };
        for (int i = 0; i < SimilarityDeduplicator.MAX_IDENTIFIER_HOLDERS; i++) {
            ListSource source = i % 2 == 0 ? ListSource.OFAC_SDN : ListSource.EU_CONSOLIDATED;
            String name = given[i % given.length] + " " + family[i / given.length];
            entities.add(entityWithPassport(source + "-" + i, name, source, male));
        }

        DeduplicationResult result = deduplicator.deduplicate(entities);

        assertThat(result.entityToCanonicalId().get("ofac-1"))
                .isNotEqualTo(result.entityToCanonicalId().get("eu-1"));
        assertThat(result.totalCanonicalEntities()).isEqualTo(entities.size());
    }

    @Test
    void shouldNotMatchOnNamesWithoutLetters() {
        // A stray row number parsed as a name is the same "18" on every list
        SanctionedEntity ofac = unstructured("ofac-1", "18", ListSource.OFAC_SDN);
        SanctionedEntity eu = unstructured("eu-1", "18", ListSource.EU_CONSOLIDATED);

        DeduplicationResult result = deduplicator.deduplicate(List.of(ofac, eu));

        assertThat(result.totalCanonicalEntities()).isEqualTo(2);
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
    void tokenSimilarityBoundShouldNeverUnderstateTheSimilarity() {
        String[][] pairs = {
            {"mohammed", "mohammad"},
            {"reza", "jafari"},
            {"putin", "poutine"},
            {"aaa", "aaa"},
            {"abc", "cba"},
            {"yevgeny", "evgeny"},
            {"j", "john"},
            {"j", "jane"},
            {"x", "y"},
            {"soleimani", "suleymani"},
            {"naqdi", "naghdi"},
            {"ali", "aly"},
            {"ab", "ba"},
            {"владимир", "владимирович"},
            {"محمد", "محمود"},
            {"company", "co"},
            {"12", "21"},
        };
        for (String[] pair : pairs) {
            double bound =
                    SimilarityDeduplicator.tokenSimilarityBound(
                            pair[0],
                            SimilarityDeduplicator.letterMask(pair[0]),
                            pair[1],
                            SimilarityDeduplicator.letterMask(pair[1]));
            double exact =
                    SimilarityDeduplicator.tokenAlignedSimilarity(
                            new String[] {pair[0]}, new String[] {pair[1]});
            // The aligned score weighs and divides by the token lengths, which may cost an ulp
            assertThat(bound)
                    .as("%s vs %s", pair[0], pair[1])
                    .isGreaterThanOrEqualTo(exact - 1e-12);
        }
        assertThat(
                        SimilarityDeduplicator.tokenSimilarityBound(
                                "reza",
                                SimilarityDeduplicator.letterMask("reza"),
                                "jafari",
                                SimilarityDeduplicator.letterMask("jafari")))
                .isLessThan(0.7);
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

    /** A person as most providers deliver one: a full name, aliases and perhaps a date of birth. */
    private static SanctionedEntity person(
            String id, String fullName, ListSource source, LocalDate dob, String... aliases) {
        return new SanctionedEntity(
                id,
                EntityType.INDIVIDUAL,
                source,
                name(fullName, null, null),
                Arrays.stream(aliases)
                        .map(
                                alias ->
                                        new NameInfo(
                                                alias,
                                                null,
                                                null,
                                                null,
                                                null,
                                                NameType.AKA,
                                                null,
                                                null))
                        .toList(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                dob == null ? List.of() : List.of(dob),
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
