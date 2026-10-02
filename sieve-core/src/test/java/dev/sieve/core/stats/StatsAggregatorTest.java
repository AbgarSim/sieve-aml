package dev.sieve.core.stats;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsAggregatorTest {

    private final StatsAggregator aggregator = new StatsAggregator(CountryNormalizer.standard());

    @Test
    void shouldReturnZerosWhenNoEntities() {
        DatasetStats stats = aggregator.aggregate(List.of());

        assertThat(stats.totalEntities()).isZero();
        assertThat(stats.totalNames()).isZero();
        assertThat(stats.bySource()).isEmpty();
        assertThat(stats.byCountry()).isEmpty();
        assertThat(stats.unresolvedCountries().occurrences()).isZero();
    }

    @Test
    void shouldCountEntitiesNamesAndTypesWhenAggregating() {
        DatasetStats stats = aggregator.aggregate(sample());

        assertThat(stats.totalEntities()).isEqualTo(3);
        assertThat(stats.totalNames()).isEqualTo(5);
        assertThat(stats.byType())
                .containsEntry(EntityType.INDIVIDUAL, 2)
                .containsEntry(EntityType.VESSEL, 1);
        assertThat(stats.namesByScript())
                .containsEntry(ScriptType.LATIN, 2)
                .containsEntry(ScriptType.CYRILLIC, 1);
        assertThat(stats.identifiersByType()).containsEntry(IdentifierType.IMO_NUMBER, 1);
    }

    @Test
    void shouldCountEntityOncePerCountryWhenLinkedByNationalityAndAddress() {
        DatasetStats stats = aggregator.aggregate(sample());

        CountryStats russia = stats.byCountry().get("RU");
        assertThat(russia.entities()).isEqualTo(2);
        assertThat(russia.byNationality()).isEqualTo(2);
        assertThat(russia.byAddress()).isEqualTo(1);
        assertThat(russia.bySource())
                .containsEntry(ListSource.OFAC_SDN, 1)
                .containsEntry(ListSource.EU_CONSOLIDATED, 1);

        CountryStats panama = stats.byCountry().get("PA");
        assertThat(panama.entities()).isEqualTo(1);
        assertThat(panama.byAddress()).isEqualTo(1);
        assertThat(panama.byType()).containsEntry(EntityType.VESSEL, 1);
    }

    @Test
    void shouldReportUnresolvedCountryValuesWhenNormalizerCannotMapThem() {
        DatasetStats stats = aggregator.aggregate(sample());

        assertThat(stats.unresolvedCountries().occurrences()).isEqualTo(1);
        assertThat(stats.unresolvedCountries().topValues()).containsEntry("Atlantis", 1);
    }

    @Test
    void shouldComputePerSourceCompletenessAndProgramsWhenAggregating() {
        DatasetStats stats = aggregator.aggregate(sample());

        SourceStats ofac = stats.bySource().get(ListSource.OFAC_SDN);
        assertThat(ofac.entities()).isEqualTo(2);
        assertThat(ofac.names()).isEqualTo(3);
        assertThat(ofac.countries()).isEqualTo(3);
        assertThat(ofac.completeness()).isEqualTo(new Completeness(2, 1, 1, 2, 1, 1, 2, 0));
        assertThat(ofac.topPrograms())
                .first()
                .isEqualTo(new ProgramCount(ListSource.OFAC_SDN, "RUSSIA-EO14024", null, 2));

        assertThat(stats.distinctPrograms()).isEqualTo(2);
        assertThat(stats.topPrograms()).hasSize(2);
    }

    @Test
    void shouldReturnSortedCountryCodesWhenAskedForOneEntity() {
        SanctionedEntity entity = sample().get(0);

        assertThat(aggregator.countryCodes(entity)).containsExactly("CY", "RU");
    }

    private static List<SanctionedEntity> sample() {
        SanctionedEntity person =
                entity(
                        "1",
                        EntityType.INDIVIDUAL,
                        ListSource.OFAC_SDN,
                        name("Ivan Petrov", ScriptType.LATIN),
                        List.of(name("Иван Петров", ScriptType.CYRILLIC)),
                        List.of("Russian Federation"),
                        List.of(new Address(null, "Limassol", null, null, "Cyprus", null)),
                        List.of(),
                        List.of(LocalDate.of(1970, 1, 1)),
                        List.of(new SanctionsProgram("RUSSIA-EO14024", null, ListSource.OFAC_SDN)));
        SanctionedEntity vessel =
                entity(
                        "2",
                        EntityType.VESSEL,
                        ListSource.OFAC_SDN,
                        name("SEA STAR", ScriptType.LATIN),
                        List.of(),
                        List.of(),
                        List.of(new Address(null, null, null, null, "Panama", null)),
                        List.of(new Identifier(IdentifierType.IMO_NUMBER, "1234567", null, null)),
                        List.of(),
                        List.of(new SanctionsProgram("RUSSIA-EO14024", null, ListSource.OFAC_SDN)));
        SanctionedEntity euPerson =
                entity(
                        "3",
                        EntityType.INDIVIDUAL,
                        ListSource.EU_CONSOLIDATED,
                        name("Petr Ivanov", null),
                        List.of(name("P. Ivanov", null)),
                        List.of("RUSSIA", "Atlantis"),
                        List.of(new Address(null, "Moscow", null, null, "RU", null)),
                        List.of(),
                        List.of(),
                        List.of(new SanctionsProgram("RUS", "Russia", ListSource.EU_CONSOLIDATED)));
        return List.of(person, vessel, euPerson);
    }

    private static NameInfo name(String fullName, ScriptType script) {
        return new NameInfo(fullName, null, null, null, null, NameType.PRIMARY, null, script);
    }

    private static SanctionedEntity entity(
            String id,
            EntityType type,
            ListSource source,
            NameInfo primary,
            List<NameInfo> aliases,
            List<String> nationalities,
            List<Address> addresses,
            List<Identifier> identifiers,
            List<LocalDate> datesOfBirth,
            List<SanctionsProgram> programs) {
        return new SanctionedEntity(
                id,
                type,
                source,
                primary,
                aliases,
                addresses,
                identifiers,
                nationalities,
                List.of(),
                datesOfBirth,
                List.of(),
                null,
                programs,
                null,
                Instant.EPOCH);
    }
}
