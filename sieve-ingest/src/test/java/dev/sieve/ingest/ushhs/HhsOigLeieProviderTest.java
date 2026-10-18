package dev.sieve.ingest.ushhs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HhsOigLeieProviderTest {

    @Test
    void shouldReadBusinessesAndPeople() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities).hasSize(6);
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.listSource()).isEqualTo(ListSource.US_HHS_LEIE);
                            assertThat(e.topics()).containsExactly(RiskTopic.DEBARMENT);
                            assertThat(e.id()).matches("leie-[0-9a-f]{16}");
                        });
        assertThat(entities)
                .extracting(e -> e.primaryName().fullName())
                .containsExactly(
                        "1 BEST CARE, INC",
                        "BRIAN MICHAEL AALBERS",
                        "ANGELA JOHNSON",
                        "EDUARDO SIRIA MIRANDA",
                        "DUNN MEDICAL INC",
                        "DUNN MEDICAL INC");
        assertThat(entities.get(0).entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(entities.get(1).entityType()).isEqualTo(EntityType.INDIVIDUAL);
    }

    @Test
    void shouldMapPersonDetails() throws Exception {
        SanctionedEntity doctor = parseSample().get(1);

        assertThat(doctor.primaryName().givenName()).isEqualTo("BRIAN");
        assertThat(doctor.primaryName().middleName()).isEqualTo("MICHAEL");
        assertThat(doctor.primaryName().familyName()).isEqualTo("AALBERS");
        assertThat(doctor.datesOfBirth()).containsExactly(LocalDate.of(1974, 4, 29));
        assertThat(doctor.identifiers())
                .singleElement()
                .satisfies(
                        id -> {
                            assertThat(id.value()).isEqualTo("1740584747");
                            assertThat(id.remarks()).contains("NPI");
                        });
        assertThat(doctor.addresses())
                .singleElement()
                .satisfies(
                        a -> {
                            assertThat(a.city()).isEqualTo("LITTLETON");
                            assertThat(a.stateOrProvince()).isEqualTo("CO");
                            assertThat(a.postalCode()).isEqualTo("80123");
                            assertThat(a.country()).isEqualTo("US");
                        });
        assertThat(doctor.programs())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.code()).isEqualTo("1128(b)(4)");
                            assertThat(p.name()).contains("License revocation");
                        });
        assertThat(doctor.listingReasons())
                .containsExactly("License revocation, suspension or surrender");
        assertThat(doctor.listedDate()).isEqualTo(Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(doctor.remarks())
                .contains("Category: PHYSICIAN (MD, DO)", "Specialty: PEDIATRICS");
    }

    @Test
    void shouldTreatNullLastNameAsMissingAndReadWaivers() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities.get(2).primaryName().familyName()).isNull();
        SanctionedEntity waived = entities.get(3);
        assertThat(waived.identifiers()).hasSize(2);
        assertThat(waived.remarks()).contains("Waiver granted 2015-06-18 for TX");
        assertThat(entities.get(0).datesOfBirth()).isEmpty();
        assertThat(entities.get(0).identifiers()).isEmpty();
    }

    @Test
    void shouldKeepIdenticalRowsOnceAndSplitSameNameByAddress() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        SanctionedEntity first = entities.get(4);
        SanctionedEntity second = entities.get(5);
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.addresses().get(0).city()).isEqualTo("MIAMI");
        assertThat(second.addresses().get(0).city()).isEqualTo("TAMPA");
        assertThat(first.programs().get(0).code()).isEqualTo("1128(b)(8)(A)");
    }

    @Test
    void shouldGiveTheSameIdsOnEveryRead() throws Exception {
        assertThat(parseSample())
                .extracting(SanctionedEntity::id)
                .containsExactlyElementsOf(
                        parseSample().stream().map(SanctionedEntity::id).toList());
    }

    @Test
    void shouldWriteSections() {
        assertThat(HhsOigLeieProvider.section("1128a1")).isEqualTo("1128(a)(1)");
        assertThat(HhsOigLeieProvider.section("1128b14")).isEqualTo("1128(b)(14)");
        assertThat(HhsOigLeieProvider.section("BRCH CIA")).isEqualTo("BRCH CIA");
        assertThat(HhsOigLeieProvider.section(null)).isNull();
    }

    @Test
    void shouldRejectAnUnexpectedHeader() {
        HhsOigLeieProvider provider = new HhsOigLeieProvider(URI.create("http://localhost"));
        assertThatThrownBy(
                        () ->
                                provider.parseResponse(
                                        "NAME,CITY\nX,Y\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ListIngestionException.class);
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        try (InputStream in =
                HhsOigLeieProviderTest.class.getResourceAsStream("/leie_test_sample.csv")) {
            return new HhsOigLeieProvider(URI.create("http://localhost"))
                    .parseResponse(in.readAllBytes());
        }
    }
}
