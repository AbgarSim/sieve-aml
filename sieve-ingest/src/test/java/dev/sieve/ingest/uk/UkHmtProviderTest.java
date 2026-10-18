package dev.sieve.ingest.uk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import dev.sieve.core.model.VesselDetails;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UkHmtProviderTest {

    private UkHmtProvider provider;

    @BeforeEach
    void setUp() {
        provider = new UkHmtProvider(URI.create("https://localhost/test"));
    }

    @Test
    void shouldReturnUkHmtSource() {
        assertThat(provider.source()).isEqualTo(ListSource.UK_HMT);
    }

    @Test
    void shouldReadTheUkSanctionsListByDefault() {
        assertThat(new UkHmtProvider().metadata().sourceUri())
                .hasToString("https://sanctionslist.fcdo.gov.uk/docs/UK-Sanctions-List.xml");
    }

    @Test
    void shouldProduceOneEntityPerDesignation() throws IOException {
        assertThat(loadEntities())
                .extracting(SanctionedEntity::id)
                .containsExactly(
                        "uk-GHR0100", "uk-RUS0200", "uk-DPR0300", "uk-RUS0400", "uk-AQD0500");
    }

    // ---- Individual -------------------------------------------------------

    @Test
    void shouldReadThePrimaryNameWithItsTitle() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "uk-GHR0100");

        assertThat(smith.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(smith.primaryName().fullName()).isEqualTo("SMITH, John Edward");
        assertThat(smith.primaryName().givenName()).isEqualTo("John");
        assertThat(smith.primaryName().familyName()).isEqualTo("SMITH");
        assertThat(smith.primaryName().middleName()).isEqualTo("Edward");
        assertThat(smith.primaryName().title()).isEqualTo("Mr");
        assertThat(smith.primaryName().nameType()).isEqualTo(NameType.PRIMARY);
        assertThat(smith.primaryName().strength()).isEqualTo(NameStrength.STRONG);
        assertThat(smith.primaryName().script()).isEqualTo(ScriptType.LATIN);
    }

    @Test
    void shouldMarkLowQualityAliasesWeak() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "uk-GHR0100");

        assertThat(smith.aliases()).hasSize(2);
        assertThat(smith.aliases().get(0).fullName()).isEqualTo("SMYTH, Johnny");
        assertThat(smith.aliases().get(0).strength()).isEqualTo(NameStrength.STRONG);
        assertThat(smith.aliases().get(1).fullName()).isEqualTo("JONES, John");
        assertThat(smith.aliases().get(1).strength()).isEqualTo(NameStrength.WEAK);
        assertThat(smith.aliases()).allMatch(a -> a.nameType() == NameType.AKA);
    }

    @Test
    void shouldParseAddress() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "uk-GHR0100");

        assertThat(smith.addresses()).hasSize(1);
        assertThat(smith.addresses().get(0).street()).isEqualTo("10 Downing Street");
        assertThat(smith.addresses().get(0).city()).isEqualTo("London");
        assertThat(smith.addresses().get(0).postalCode()).isEqualTo("SW1A 2AA");
        assertThat(smith.addresses().get(0).country()).isEqualTo("United Kingdom");
        assertThat(smith.addresses().get(0).fullAddress())
                .isEqualTo("10 Downing Street, London, SW1A 2AA, United Kingdom");
    }

    @Test
    void shouldReadPassportsOnceAndNationalIds() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "uk-GHR0100");

        assertThat(smith.identifiers()).hasSize(2);
        assertThat(smith.identifiers().get(0).type()).isEqualTo(IdentifierType.PASSPORT);
        assertThat(smith.identifiers().get(0).value()).isEqualTo("GB123456");
        assertThat(smith.identifiers().get(0).remarks()).isEqualTo("Issued 2020");
        assertThat(smith.identifiers().get(1).type()).isEqualTo(IdentifierType.NATIONAL_ID);
        assertThat(smith.identifiers().get(1).value()).isEqualTo("AB123456C");
    }

    @Test
    void shouldReadFullAndYearOnlyDatesOfBirth() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "uk-GHR0100");

        assertThat(smith.datesOfBirth())
                .containsExactly(LocalDate.of(1985, 3, 1), LocalDate.of(1986, 1, 1));
    }

    @Test
    void shouldReadPartialDatesOfBirth() {
        assertThat(UkHmtProvider.parseDateOfBirth("dd/07/1960"))
                .isEqualTo(LocalDate.of(1960, 7, 1));
        assertThat(UkHmtProvider.parseDateOfBirth("dd/mm/1945"))
                .isEqualTo(LocalDate.of(1945, 1, 1));
        assertThat(UkHmtProvider.parseDateOfBirth("unknown")).isNull();
        assertThat(UkHmtProvider.parseDateOfBirth(null)).isNull();
    }

    @Test
    void shouldReadNationalityBirthplaceAndGender() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "uk-GHR0100");

        assertThat(smith.nationalities()).containsExactly("United Kingdom");
        assertThat(smith.citizenships()).containsExactly("United Kingdom");
        assertThat(smith.placesOfBirth()).containsExactly("Manchester, United Kingdom");
        assertThat(smith.gender()).isEqualTo(Gender.MALE);
        assertThat(smith.deceased()).isNull();
        assertThat(smith.vessel()).isNull();
    }

    @Test
    void shouldReadRegimeReasonsAndDates() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "uk-GHR0100");

        assertThat(smith.programs())
                .singleElement()
                .satisfies(
                        p ->
                                assertThat(p.code())
                                        .isEqualTo(
                                                "The Global Human Rights Sanctions Regulations 2020"));
        assertThat(smith.remarks()).isEqualTo("Test individual for unit testing purposes.");
        assertThat(smith.listingReasons())
                .containsExactly("Involved in serious human rights violations.");
        assertThat(smith.listedDate()).isEqualTo(Instant.parse("2023-06-15T00:00:00Z"));
        assertThat(smith.lastUpdated()).isEqualTo(Instant.parse("2024-01-10T00:00:00Z"));
    }

    // ---- Entity -----------------------------------------------------------

    @Test
    void shouldReadAnEntityWithItsNameVariationAndRegistrationNumber() throws IOException {
        SanctionedEntity acme = findById(loadEntities(), "uk-RUS0200");

        assertThat(acme.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(acme.primaryName().fullName()).isEqualTo("ACME HOLDINGS LTD");
        assertThat(acme.aliases()).extracting(n -> n.fullName()).containsExactly("ACME CORP");
        assertThat(acme.identifiers())
                .singleElement()
                .satisfies(
                        id -> {
                            assertThat(id.type()).isEqualTo(IdentifierType.BUSINESS_REGISTRATION);
                            assertThat(id.value()).isEqualTo("UK Company no. 06527449");
                        });
        assertThat(acme.remarks()).isEqualTo("Formerly known as Acme Corp.");
        assertThat(acme.listingReasons()).containsExactly("Test entity for unit testing.");
        assertThat(acme.gender()).isNull();
    }

    // ---- Ship -------------------------------------------------------------

    @Test
    void shouldReadAShipWithItsImoNumberWithoutThePrefix() throws IOException {
        SanctionedEntity vessel = findById(loadEntities(), "uk-DPR0300");

        assertThat(vessel.entityType()).isEqualTo(EntityType.VESSEL);
        assertThat(vessel.primaryName().fullName()).isEqualTo("MV OCEAN STAR");
        assertThat(vessel.identifiers())
                .singleElement()
                .satisfies(
                        id -> {
                            assertThat(id.type()).isEqualTo(IdentifierType.IMO_NUMBER);
                            assertThat(id.value()).isEqualTo("1234567");
                        });
        assertThat(vessel.vessel())
                .isEqualTo(new VesselDetails("Panama", "Cargo", null, 5_100, null));
        assertThat(vessel.listingReasons()).containsExactly("Test vessel entry.");
    }

    // ---- Non-Latin names and missing primary names --------------------------

    @Test
    void shouldCollectNonLatinNamesAsAliases() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "uk-RUS0400");

        assertThat(ivanov.primaryName().fullName()).isEqualTo("IVANOV, Sergei Petrovich");
        assertThat(ivanov.aliases()).hasSize(1);
        assertThat(ivanov.aliases().get(0).fullName()).isEqualTo("Сергей Петрович ИВАНОВ");
        assertThat(ivanov.aliases().get(0).script()).isEqualTo(ScriptType.CYRILLIC);
        assertThat(ivanov.aliases().get(0).nameType()).isEqualTo(NameType.AKA);
        assertThat(ivanov.listingReasons()).as("a blank statement of reasons").isEmpty();
        assertThat(ivanov.datesOfBirth()).containsExactly(LocalDate.of(1970, 5, 20));
    }

    @Test
    void shouldUseTheFirstNameWhenNoneIsPrimary() throws IOException {
        SanctionedEntity ahmed = findById(loadEntities(), "uk-AQD0500");

        assertThat(ahmed.primaryName().fullName()).isEqualTo("AL-HASSAN, Ahmed");
        assertThat(ahmed.primaryName().nameType()).isEqualTo(NameType.PRIMARY);
        assertThat(ahmed.aliases())
                .extracting(n -> n.fullName())
                .containsExactly("AL HASAN, Ahmad");
        assertThat(ahmed.listedDate()).isNull();
    }

    @Test
    void shouldHandleEmptyXml() {
        byte[] emptyXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Designations><DateGenerated>16/10/2026</DateGenerated></Designations>"
                        .getBytes();
        assertThat(provider.parseXml(emptyXml)).isEmpty();
    }

    @Test
    void shouldLinkTargetsNamedInOtherInformationGivenNamesFirst() {
        String xml =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <Designations>
                  <Designation>
                    <UniqueID>RUS0001</UniqueID>
                    <Names><Name>
                      <Name1>Ivan</Name1><Name2>Sergeyevich</Name2><Name6>PETROV</Name6>
                      <NameType>Primary Name</NameType>
                    </Name></Names>
                    <IndividualEntityShip>Individual</IndividualEntityShip>
                  </Designation>
                  <Designation>
                    <UniqueID>RUS0002</UniqueID>
                    <Names><Name>
                      <Name6>NORTHWIND TRADING LLC</Name6><NameType>Primary Name</NameType>
                    </Name></Names>
                    <IndividualEntityShip>Entity</IndividualEntityShip>
                    <OtherInformation>Owned by Ivan Sergeyevich Petrov.</OtherInformation>
                  </Designation>
                </Designations>
                """;

        List<SanctionedEntity> entities = provider.parseXml(xml.strip().getBytes());

        SanctionedEntity company = entities.get(1);
        assertThat(company.relations())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.targetId()).isEqualTo(entities.get(0).id());
                            assertThat(r.role()).isEqualTo("owned by");
                        });
        assertThat(entities.get(0).relations()).isEmpty();
    }

    // ---- Helpers -----------------------------------------------------------

    private List<SanctionedEntity> loadEntities() throws IOException {
        return provider.parseXml(loadTestResource("uk_hmt_test_sample.xml"));
    }

    private static SanctionedEntity findById(List<SanctionedEntity> entities, String id) {
        return entities.stream()
                .filter(e -> id.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entity not found: " + id));
    }

    private static byte[] loadTestResource(String filename) throws IOException {
        try (InputStream is =
                UkHmtProviderTest.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                throw new IOException("Test resource not found: " + filename);
            }
            return is.readAllBytes();
        }
    }
}
