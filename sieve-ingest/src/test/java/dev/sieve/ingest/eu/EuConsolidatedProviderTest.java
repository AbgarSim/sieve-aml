package dev.sieve.ingest.eu;

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

class EuConsolidatedProviderTest {

    private EuConsolidatedProvider provider;

    @BeforeEach
    void setUp() {
        provider = new EuConsolidatedProvider(URI.create("https://localhost/test"));
    }

    @Test
    void shouldReturnEuConsolidatedSource() {
        assertThat(provider.source()).isEqualTo(ListSource.EU_CONSOLIDATED);
    }

    @Test
    void shouldParseFiveEntitiesFromTestSample() throws IOException {
        List<SanctionedEntity> entities = loadEntities();
        assertThat(entities).hasSize(5);
    }

    // ---- Entity 1: Individual with aliases, address, ID, citizenship, DOB --

    @Test
    void shouldParsePrimaryNameFromEnglishAlias() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");

        assertThat(ivanov.primaryName().fullName()).isEqualTo("Sergei Petrovich Ivanov");
        assertThat(ivanov.primaryName().givenName()).isEqualTo("Sergei");
        assertThat(ivanov.primaryName().familyName()).isEqualTo("Ivanov");
        assertThat(ivanov.primaryName().middleName()).isEqualTo("Petrovich");
        assertThat(ivanov.primaryName().title()).isEqualTo("Mr");
        assertThat(ivanov.primaryName().nameType()).isEqualTo(NameType.PRIMARY);
        assertThat(ivanov.primaryName().strength()).isEqualTo(NameStrength.STRONG);
        assertThat(ivanov.primaryName().script()).isEqualTo(ScriptType.LATIN);
    }

    @Test
    void shouldCollectAliasesIncludingNonLatin() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");

        // 2 aliases: Cyrillic + French
        assertThat(ivanov.aliases()).hasSize(2);

        assertThat(ivanov.aliases().get(0).fullName()).isEqualTo("Сергей Петрович Иванов");
        assertThat(ivanov.aliases().get(0).script()).isEqualTo(ScriptType.CYRILLIC);
        assertThat(ivanov.aliases().get(0).nameType()).isEqualTo(NameType.AKA);
        assertThat(ivanov.aliases().get(0).strength()).isEqualTo(NameStrength.STRONG);

        assertThat(ivanov.aliases().get(1).fullName()).isEqualTo("Serguei Ivanov");
        assertThat(ivanov.aliases().get(1).script()).isEqualTo(ScriptType.LATIN);
        assertThat(ivanov.aliases().get(1).strength()).isEqualTo(NameStrength.WEAK);
    }

    @Test
    void shouldParseEntityTypeIndividual() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");
        assertThat(ivanov.entityType()).isEqualTo(EntityType.INDIVIDUAL);
    }

    @Test
    void shouldParseAddress() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");

        assertThat(ivanov.addresses()).hasSize(1);
        assertThat(ivanov.addresses().get(0).street()).isEqualTo("Ulitsa Lenina 5");
        assertThat(ivanov.addresses().get(0).city()).isEqualTo("Moscow");
        assertThat(ivanov.addresses().get(0).postalCode()).isEqualTo("101000");
        assertThat(ivanov.addresses().get(0).country()).isEqualTo("RUSSIA");
    }

    @Test
    void shouldParsePassportIdentifier() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");

        assertThat(ivanov.identifiers()).hasSize(1);
        assertThat(ivanov.identifiers().get(0).type()).isEqualTo(IdentifierType.PASSPORT);
        assertThat(ivanov.identifiers().get(0).value()).isEqualTo("RU9876543");
        assertThat(ivanov.identifiers().get(0).issuingCountry()).isEqualTo("RU");
    }

    @Test
    void shouldParseCitizenship() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");
        assertThat(ivanov.citizenships()).containsExactly("RUSSIA");
    }

    @Test
    void shouldParseDateOfBirthFromFullDate() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");

        assertThat(ivanov.datesOfBirth()).hasSize(1);
        assertThat(ivanov.datesOfBirth().get(0).getYear()).isEqualTo(1970);
        assertThat(ivanov.datesOfBirth().get(0).getMonthValue()).isEqualTo(5);
        assertThat(ivanov.datesOfBirth().get(0).getDayOfMonth()).isEqualTo(20);
    }

    @Test
    void shouldParsePlaceOfBirth() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");
        assertThat(ivanov.placesOfBirth()).contains("Moscow");
    }

    @Test
    void shouldParseRemarks() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");
        assertThat(ivanov.remarks()).isEqualTo("Test individual entry for unit testing.");
    }

    @Test
    void shouldParseSanctionsProgram() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");
        assertThat(ivanov.programs()).hasSize(1);
        assertThat(ivanov.programs().get(0).code()).isEqualTo("RUS");
    }

    @Test
    void shouldParseListedDate() throws IOException {
        SanctionedEntity ivanov = findById(loadEntities(), "EU.10.42");
        assertThat(ivanov.listedDate()).isNotNull();
    }

    // ---- Entity 2: Enterprise with registration number + multiple programs -

    @Test
    void shouldParseEntityTypeEnterprise() throws IOException {
        SanctionedEntity acme = findById(loadEntities(), "EU.20.15");

        assertThat(acme.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(acme.primaryName().fullName()).isEqualTo("Acme Trading GmbH");
    }

    @Test
    void shouldParseBusinessRegistrationIdentifier() throws IOException {
        SanctionedEntity acme = findById(loadEntities(), "EU.20.15");

        assertThat(acme.identifiers()).hasSize(1);
        assertThat(acme.identifiers().get(0).type())
                .isEqualTo(IdentifierType.BUSINESS_REGISTRATION);
        assertThat(acme.identifiers().get(0).value()).isEqualTo("FN-123456a");
    }

    @Test
    void shouldParseMultiplePrograms() throws IOException {
        SanctionedEntity acme = findById(loadEntities(), "EU.20.15");
        assertThat(acme.programs()).hasSize(2);
        assertThat(acme.programs().stream().map(p -> p.code()).toList())
                .containsExactlyInAnyOrder("RUS", "CRIMEA");
    }

    @Test
    void shouldParseEnterpriseAlias() throws IOException {
        SanctionedEntity acme = findById(loadEntities(), "EU.20.15");
        assertThat(acme.aliases()).hasSize(1);
        assertThat(acme.aliases().get(0).fullName()).isEqualTo("Acme Handel GmbH");
    }

    // ---- Entity 3: Individual with year-only DOB and multiple citizenships -

    @Test
    void shouldParseYearOnlyDatesOfBirth() throws IOException {
        SanctionedEntity ahmed = findById(loadEntities(), "EU.30.77");

        assertThat(ahmed.datesOfBirth()).hasSize(2);
        assertThat(ahmed.datesOfBirth().get(0).getYear()).isEqualTo(1965);
        assertThat(ahmed.datesOfBirth().get(1).getYear()).isEqualTo(1966);
    }

    @Test
    void shouldParseMultipleCitizenships() throws IOException {
        SanctionedEntity ahmed = findById(loadEntities(), "EU.30.77");
        assertThat(ahmed.citizenships()).containsExactly("IRAQ", "JORDAN");
    }

    // ---- Entity 4: Individual with wholeName only + title + national ID ----

    @Test
    void shouldParseWholeNameOnly() throws IOException {
        SanctionedEntity mahmoud = findById(loadEntities(), "EU.40.10");

        assertThat(mahmoud.primaryName().fullName()).isEqualTo("Abdel Hamid Mahmoud");
        assertThat(mahmoud.primaryName().title()).isEqualTo("Col");
    }

    @Test
    void shouldParseNationalIdIdentifier() throws IOException {
        SanctionedEntity mahmoud = findById(loadEntities(), "EU.40.10");

        assertThat(mahmoud.identifiers()).hasSize(1);
        assertThat(mahmoud.identifiers().get(0).type()).isEqualTo(IdentifierType.NATIONAL_ID);
        assertThat(mahmoud.identifiers().get(0).value()).isEqualTo("SY-444555");
        assertThat(mahmoud.identifiers().get(0).issuingCountry()).isEqualTo("SY");
    }

    // ---- Entity 5: Fallback ID + PO Box address ----------------------------

    @Test
    void shouldFallbackToLogicalIdWhenNoEuRefNumber() throws IOException {
        SanctionedEntity korea = findById(loadEntities(), "EU-500");

        assertThat(korea.primaryName().fullName()).isEqualTo("Korea Mining Corp");
        assertThat(korea.entityType()).isEqualTo(EntityType.ENTITY);
    }

    @Test
    void shouldParsePoBoxAddress() throws IOException {
        SanctionedEntity korea = findById(loadEntities(), "EU-500");

        assertThat(korea.addresses()).hasSize(1);
        assertThat(korea.addresses().get(0).street()).isEqualTo("P.O. Box 999");
        assertThat(korea.addresses().get(0).city()).isEqualTo("Pyongyang");
        assertThat(korea.addresses().get(0).country()).isEqualTo("NORTH KOREA");
    }

    // ---- Edge cases --------------------------------------------------------

    @Test
    void shouldHandleEmptyXml() {
        byte[] emptyXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><export xmlns=\"http://eu.europa.ec/fpi/fsd/export\"></export>"
                        .getBytes();
        List<SanctionedEntity> entities = provider.parseXml(emptyXml);
        assertThat(entities).isEmpty();
    }

    // ---- Helpers -----------------------------------------------------------

    private List<SanctionedEntity> loadEntities() throws IOException {
        return provider.parseXml(loadTestResource("eu_consolidated_test_sample.xml"));
    }

    private static SanctionedEntity findById(List<SanctionedEntity> entities, String id) {
        return entities.stream()
                .filter(e -> id.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entity not found: " + id));
    }

    private static byte[] loadTestResource(String filename) throws IOException {
        try (InputStream is =
                EuConsolidatedProviderTest.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                throw new IOException("Test resource not found: " + filename);
            }
            return is.readAllBytes();
        }
    }
}
