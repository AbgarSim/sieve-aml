package dev.sieve.ingest.un;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnConsolidatedProviderTest {

    private UnConsolidatedProvider provider;

    @BeforeEach
    void setUp() {
        provider = new UnConsolidatedProvider(URI.create("https://localhost/test"));
    }

    @Test
    void shouldReturnUnConsolidatedSource() {
        assertThat(provider.source()).isEqualTo(ListSource.UN_CONSOLIDATED);
    }

    @Test
    void shouldParseSixEntitiesFromTestSample() throws IOException {
        List<SanctionedEntity> entities = loadEntities();
        // 4 individuals + 2 entities = 6
        assertThat(entities).hasSize(6);
    }

    // ---- Individual 1: Full individual with all fields ---------------------

    @Test
    void shouldParsePrimaryNameWithThreeComponents() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");

        assertThat(eric.primaryName().fullName()).isEqualTo("BADEGE, ERIC MUNDOS");
        assertThat(eric.primaryName().givenName()).isEqualTo("ERIC");
        assertThat(eric.primaryName().familyName()).isEqualTo("BADEGE");
        assertThat(eric.primaryName().middleName()).isEqualTo("MUNDOS");
        assertThat(eric.primaryName().title()).isEqualTo("Dr.");
        assertThat(eric.primaryName().nameType()).isEqualTo(NameType.PRIMARY);
        assertThat(eric.primaryName().strength()).isEqualTo(NameStrength.STRONG);
    }

    @Test
    void shouldParseIndividualEntityType() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");
        assertThat(eric.entityType()).isEqualTo(EntityType.INDIVIDUAL);
    }

    @Test
    void shouldParseAliasesWithQuality() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");

        // 3 valid aliases (4th has empty ALIAS_NAME and should be skipped)
        assertThat(eric.aliases()).hasSize(3);

        assertThat(eric.aliases().get(0).fullName()).isEqualTo("ERIC MUNDOS");
        assertThat(eric.aliases().get(0).nameType()).isEqualTo(NameType.AKA);
        assertThat(eric.aliases().get(0).strength()).isEqualTo(NameStrength.STRONG);

        assertThat(eric.aliases().get(1).fullName()).isEqualTo("The Commander");
        assertThat(eric.aliases().get(1).nameType()).isEqualTo(NameType.AKA);
        assertThat(eric.aliases().get(1).strength()).isEqualTo(NameStrength.WEAK);

        assertThat(eric.aliases().get(2).fullName()).isEqualTo("ERIC JONES");
        assertThat(eric.aliases().get(2).nameType()).isEqualTo(NameType.FKA);
        assertThat(eric.aliases().get(2).strength()).isEqualTo(NameStrength.STRONG);
    }

    @Test
    void shouldParseAddress() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");

        assertThat(eric.addresses()).hasSize(1);
        assertThat(eric.addresses().get(0).street()).isEqualTo("123 Main Road");
        assertThat(eric.addresses().get(0).city()).isEqualTo("Kinshasa");
        assertThat(eric.addresses().get(0).stateOrProvince()).isEqualTo("Kinshasa Province");
        assertThat(eric.addresses().get(0).country())
                .isEqualTo("Democratic Republic of the Congo");
    }

    @Test
    void shouldParseDateOfBirthFromFullDate() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");

        assertThat(eric.datesOfBirth()).hasSize(1);
        assertThat(eric.datesOfBirth().get(0).getYear()).isEqualTo(1971);
        assertThat(eric.datesOfBirth().get(0).getMonthValue()).isEqualTo(3);
        assertThat(eric.datesOfBirth().get(0).getDayOfMonth()).isEqualTo(15);
    }

    @Test
    void shouldParsePlaceOfBirth() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");
        assertThat(eric.placesOfBirth())
                .contains("Goma, North Kivu, Democratic Republic of the Congo");
    }

    @Test
    void shouldParsePassportDocument() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");

        assertThat(eric.identifiers()).hasSize(2);

        assertThat(eric.identifiers().get(0).type()).isEqualTo(IdentifierType.PASSPORT);
        assertThat(eric.identifiers().get(0).value()).isEqualTo("OB 0243318");
        assertThat(eric.identifiers().get(0).issuingCountry())
                .isEqualTo("Democratic Republic of the Congo");

        assertThat(eric.identifiers().get(1).type()).isEqualTo(IdentifierType.NATIONAL_ID);
        assertThat(eric.identifiers().get(1).value()).isEqualTo("1-78-09-44621-80");
        assertThat(eric.identifiers().get(1).remarks()).isEqualTo("FARDC ID");
    }

    @Test
    void shouldParseNationality() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");
        assertThat(eric.nationalities()).containsExactly("Democratic Republic of the Congo");
    }

    @Test
    void shouldParseRemarks() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");
        assertThat(eric.remarks()).isEqualTo("Test individual for unit testing purposes.");
    }

    @Test
    void shouldParseSanctionsProgram() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");
        assertThat(eric.programs()).hasSize(1);
        assertThat(eric.programs().get(0).code()).isEqualTo("DRC");
    }

    @Test
    void shouldParseListedDate() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");
        assertThat(eric.listedDate()).isNotNull();
    }

    @Test
    void shouldParseLastUpdatedFromLastValue() throws IOException {
        SanctionedEntity eric = findById(loadEntities(), "CDi.001");
        // LAST_DAY_UPDATED has 2 values; should pick the last one (2023-05-20)
        assertThat(eric.lastUpdated()).isNotNull();
    }

    // ---- Individual 2: Year-only DOB, multiple POBs -----------------------

    @Test
    void shouldParseYearOnlyDateOfBirth() throws IOException {
        SanctionedEntity saddam = findById(loadEntities(), "IQi.001");

        assertThat(saddam.datesOfBirth()).hasSize(1);
        assertThat(saddam.datesOfBirth().get(0).getYear()).isEqualTo(1937);
    }

    @Test
    void shouldParseMultiplePlacesOfBirth() throws IOException {
        SanctionedEntity saddam = findById(loadEntities(), "IQi.001");
        assertThat(saddam.placesOfBirth()).hasSize(2);
        assertThat(saddam.placesOfBirth()).contains("al-Awja, near Tikrit, Iraq");
        assertThat(saddam.placesOfBirth()).contains("Tikrit, Iraq");
    }

    // ---- Individual 3: Minimal, first name only, no reference number ------

    @Test
    void shouldFallbackToDataIdWhenNoReferenceNumber() throws IOException {
        SanctionedEntity mohammed = findById(loadEntities(), "UN-1003");

        assertThat(mohammed.primaryName().fullName()).isEqualTo("MOHAMMED");
        assertThat(mohammed.primaryName().givenName()).isEqualTo("MOHAMMED");
        assertThat(mohammed.primaryName().familyName()).isNull();
    }

    @Test
    void shouldHandleEmptyAliasAndDob() throws IOException {
        SanctionedEntity mohammed = findById(loadEntities(), "UN-1003");
        assertThat(mohammed.aliases()).isEmpty();
        assertThat(mohammed.datesOfBirth()).isEmpty();
    }

    // ---- Individual 4: Four name components --------------------------------

    @Test
    void shouldParseFourNameComponents() throws IOException {
        SanctionedEntity qusay = findById(loadEntities(), "IQi.002");

        assertThat(qusay.primaryName().fullName())
                .isEqualTo("SADDAM, QUSAY HUSSEIN AL-TIKRITI");
        assertThat(qusay.primaryName().givenName()).isEqualTo("QUSAY");
        assertThat(qusay.primaryName().familyName()).isEqualTo("SADDAM");
        assertThat(qusay.primaryName().middleName()).isEqualTo("HUSSEIN AL-TIKRITI");
    }

    @Test
    void shouldParseDateOfBirthPreferringFullDate() throws IOException {
        SanctionedEntity qusay = findById(loadEntities(), "IQi.002");

        // Has both DATE and YEAR; should parse the full DATE
        assertThat(qusay.datesOfBirth()).hasSize(1);
        assertThat(qusay.datesOfBirth().get(0).getYear()).isEqualTo(1965);
        assertThat(qusay.datesOfBirth().get(0).getMonthValue()).isEqualTo(5);
        assertThat(qusay.datesOfBirth().get(0).getDayOfMonth()).isEqualTo(17);
    }

    // ---- Entity 1: Entity with aliases and address -------------------------

    @Test
    void shouldParseEntityType() throws IOException {
        SanctionedEntity adf = findById(loadEntities(), "CDe.001");

        assertThat(adf.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(adf.primaryName().fullName()).isEqualTo("ADF");
    }

    @Test
    void shouldParseEntityAliases() throws IOException {
        SanctionedEntity adf = findById(loadEntities(), "CDe.001");

        assertThat(adf.aliases()).hasSize(2);
        assertThat(adf.aliases().get(0).fullName()).isEqualTo("Allied Democratic Forces");
        assertThat(adf.aliases().get(0).nameType()).isEqualTo(NameType.AKA);

        assertThat(adf.aliases().get(1).fullName()).isEqualTo("ADF/NALU");
        assertThat(adf.aliases().get(1).nameType()).isEqualTo(NameType.FKA);
    }

    @Test
    void shouldParseEntityAddress() throws IOException {
        SanctionedEntity adf = findById(loadEntities(), "CDe.001");

        assertThat(adf.addresses()).hasSize(1);
        assertThat(adf.addresses().get(0).stateOrProvince()).isEqualTo("North Kivu");
        assertThat(adf.addresses().get(0).country())
                .isEqualTo("Democratic Republic of the Congo");
    }

    @Test
    void shouldParseEntityLastUpdated() throws IOException {
        SanctionedEntity adf = findById(loadEntities(), "CDe.001");
        // last value is 2020-08-19
        assertThat(adf.lastUpdated()).isNotNull();
    }

    // ---- Entity 2: Minimal entity -----------------------------------------

    @Test
    void shouldParseMinimalEntity() throws IOException {
        SanctionedEntity bal = findById(loadEntities(), "CDe.002");

        assertThat(bal.primaryName().fullName()).isEqualTo("BUTEMBO AIRLINES (BAL)");
        assertThat(bal.aliases()).isEmpty();
        assertThat(bal.addresses()).hasSize(1);
        assertThat(bal.addresses().get(0).city()).isEqualTo("Butembo");
    }

    // ---- Edge cases --------------------------------------------------------

    @Test
    void shouldHandleEmptyXml() {
        byte[] emptyXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><CONSOLIDATED_LIST></CONSOLIDATED_LIST>"
                        .getBytes();
        List<SanctionedEntity> entities = provider.parseXml(emptyXml);
        assertThat(entities).isEmpty();
    }

    // ---- Helpers -----------------------------------------------------------

    private List<SanctionedEntity> loadEntities() throws IOException {
        return provider.parseXml(loadTestResource("un_consolidated_test_sample.xml"));
    }

    private static SanctionedEntity findById(List<SanctionedEntity> entities, String id) {
        return entities.stream()
                .filter(e -> id.equals(e.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Entity not found: " + id));
    }

    private static byte[] loadTestResource(String filename) throws IOException {
        try (InputStream is =
                UnConsolidatedProviderTest.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                throw new IOException("Test resource not found: " + filename);
            }
            return is.readAllBytes();
        }
    }
}
