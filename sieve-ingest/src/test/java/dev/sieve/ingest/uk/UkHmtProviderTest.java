package dev.sieve.ingest.uk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.ScriptType;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
    void shouldGroupByGroupIdAndProduceFiveEntities() throws IOException {
        byte[] xml = loadTestResource("uk_hmt_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xml);

        // 6 rows in XML → 5 unique GroupIDs (100, 200, 300, 400, 500)
        assertThat(entities).hasSize(5);
    }

    // ---- Group 100: Individual with aliases --------------------------------

    @Test
    void shouldSelectPrimaryNameRow() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");

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
    void shouldCollectAliases() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");

        // 2 alias rows: AKA "SMYTH, Johnny" + FKA "JONES, John"
        assertThat(smith.aliases()).hasSize(2);

        assertThat(smith.aliases().get(0).fullName()).isEqualTo("SMYTH, Johnny");
        assertThat(smith.aliases().get(0).nameType()).isEqualTo(NameType.AKA);

        assertThat(smith.aliases().get(1).fullName()).isEqualTo("JONES, John");
        assertThat(smith.aliases().get(1).nameType()).isEqualTo(NameType.FKA);
    }

    @Test
    void shouldParseEntityTypeIndividual() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");
        assertThat(smith.entityType()).isEqualTo(EntityType.INDIVIDUAL);
    }

    @Test
    void shouldParseAddress() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");

        // Addresses are deduplicated — the AKA alias has the same address, so only 1
        assertThat(smith.addresses()).hasSize(1);
        assertThat(smith.addresses().get(0).street()).isEqualTo("10 Downing Street");
        assertThat(smith.addresses().get(0).city()).isEqualTo("London");
        assertThat(smith.addresses().get(0).postalCode()).isEqualTo("SW1A 2AA");
        assertThat(smith.addresses().get(0).country()).isEqualTo("United Kingdom");
        assertThat(smith.addresses().get(0).fullAddress())
                .isEqualTo("10 Downing Street, London, SW1A 2AA, United Kingdom");
    }

    @Test
    void shouldParseIdentifiers() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");

        // Passport + NI from primary row
        assertThat(smith.identifiers()).hasSizeGreaterThanOrEqualTo(2);

        assertThat(smith.identifiers())
                .anyMatch(
                        id ->
                                id.type() == IdentifierType.PASSPORT
                                        && "GB123456".equals(id.value())
                                        && "Issued 2020".equals(id.remarks()));

        assertThat(smith.identifiers())
                .anyMatch(
                        id ->
                                id.type() == IdentifierType.NATIONAL_ID
                                        && "AB123456C".equals(id.value()));
    }

    @Test
    void shouldParseDateOfBirth() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");

        assertThat(smith.datesOfBirth()).hasSize(1);
        assertThat(smith.datesOfBirth().get(0).getYear()).isEqualTo(1985);
        assertThat(smith.datesOfBirth().get(0).getMonthValue()).isEqualTo(3);
        assertThat(smith.datesOfBirth().get(0).getDayOfMonth()).isEqualTo(1);
    }

    @Test
    void shouldParseNationalities() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");
        assertThat(smith.nationalities()).contains("British");
    }

    @Test
    void shouldParsePlacesOfBirth() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");
        assertThat(smith.placesOfBirth()).contains("Manchester");
    }

    @Test
    void shouldParseRemarks() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");
        assertThat(smith.remarks()).isEqualTo("Test individual for unit testing purposes.");
    }

    @Test
    void shouldParsePrograms() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");
        assertThat(smith.programs()).hasSize(1);
        assertThat(smith.programs().get(0).code()).isEqualTo("Global Human Rights");
    }

    @Test
    void shouldParseListedDate() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");
        assertThat(smith.listedDate()).isNotNull();
    }

    @Test
    void shouldParseLastUpdated() throws IOException {
        SanctionedEntity smith = findById(loadEntities(), "GHR0100");
        assertThat(smith.lastUpdated()).isNotNull();
    }

    // ---- Group 200: Entity -------------------------------------------------

    @Test
    void shouldParseEntityType() throws IOException {
        SanctionedEntity acme = findById(loadEntities(), "RUS0200");

        assertThat(acme.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(acme.primaryName().fullName()).isEqualTo("ACME HOLDINGS LTD");
    }

    @Test
    void shouldParseBusinessRegistrationNumber() throws IOException {
        SanctionedEntity acme = findById(loadEntities(), "RUS0200");

        assertThat(acme.identifiers())
                .anyMatch(
                        id ->
                                id.type() == IdentifierType.BUSINESS_REGISTRATION
                                        && "REG-98765".equals(id.value()));
    }

    // ---- Group 300: Ship ---------------------------------------------------

    @Test
    void shouldParseVessel() throws IOException {
        SanctionedEntity vessel = findById(loadEntities(), "RUS0300");

        assertThat(vessel.entityType()).isEqualTo(EntityType.VESSEL);
        assertThat(vessel.primaryName().fullName()).isEqualTo("MV OCEAN STAR");
    }

    @Test
    void shouldParseShipImoNumber() throws IOException {
        SanctionedEntity vessel = findById(loadEntities(), "RUS0300");

        assertThat(vessel.identifiers()).hasSize(1);
        assertThat(vessel.identifiers().get(0).type()).isEqualTo(IdentifierType.IMO_NUMBER);
        assertThat(vessel.identifiers().get(0).value()).isEqualTo("1234567");
    }

    // ---- Group 400: Non-Latin script alias ---------------------------------

    @Test
    void shouldCollectNonLatinScriptAlias() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "RUS0400");

        assertThat(ivanov.primaryName().fullName()).isEqualTo("IVANOV, Sergei Petrovich");

        assertThat(ivanov.aliases()).hasSize(1);
        assertThat(ivanov.aliases().get(0).fullName()).isEqualTo("Сергей Петрович ИВАНОВ");
        assertThat(ivanov.aliases().get(0).script()).isEqualTo(ScriptType.CYRILLIC);
        assertThat(ivanov.aliases().get(0).nameType()).isEqualTo(NameType.AKA);
    }

    @Test
    void shouldParseDateOfBirthFromIsoDatetime() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "RUS0400");

        assertThat(ivanov.datesOfBirth()).hasSize(1);
        assertThat(ivanov.datesOfBirth().get(0).getYear()).isEqualTo(1970);
        assertThat(ivanov.datesOfBirth().get(0).getMonthValue()).isEqualTo(5);
        assertThat(ivanov.datesOfBirth().get(0).getDayOfMonth()).isEqualTo(20);
    }

    // ---- Group 500: No "Primary name" row ----------------------------------

    @Test
    void shouldFallbackToFirstRowWhenNoPrimaryName() throws IOException {
        SanctionedEntity ahmed = findById(loadEntities(), "SYR0500");

        assertThat(ahmed.primaryName().fullName()).isEqualTo("AL-HASSAN, Ahmed");
        assertThat(ahmed.primaryName().nameType()).isEqualTo(NameType.PRIMARY);
    }

    // ---- Edge cases --------------------------------------------------------

    @Test
    void shouldHandleEmptyXml() {
        byte[] emptyXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ArrayOfFinancialSanctionsTarget xmlns=\"http://schemas.hmtreasury.gov.uk/ofsi/consolidatedlist\"></ArrayOfFinancialSanctionsTarget>"
                        .getBytes();
        List<SanctionedEntity> entities = provider.parseXml(emptyXml);
        assertThat(entities).isEmpty();
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
