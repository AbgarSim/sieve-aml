package dev.sieve.ingest.ofac;

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

class OfacSdnProviderTest {

    private OfacSdnProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OfacSdnProvider(URI.create("https://localhost/test"));
    }

    @Test
    void shouldReturnOfacSdnSource() {
        assertThat(provider.source()).isEqualTo(ListSource.OFAC_SDN);
    }

    @Test
    void shouldParseTestSampleXml() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        assertThat(entities).hasSize(5);
    }

    @Test
    void shouldParseIndividualEntry() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity john =
                entities.stream().filter(e -> "1001".equals(e.id())).findFirst().orElseThrow();

        assertThat(john.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(john.listSource()).isEqualTo(ListSource.OFAC_SDN);
        assertThat(john.primaryName().fullName()).isEqualTo("DOE, John");
        assertThat(john.primaryName().givenName()).isEqualTo("John");
        assertThat(john.primaryName().familyName()).isEqualTo("DOE");
        assertThat(john.primaryName().nameType()).isEqualTo(NameType.PRIMARY);
        assertThat(john.remarks()).contains("Test individual entry");
    }

    @Test
    void shouldParseAliases() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity john =
                entities.stream().filter(e -> "1001".equals(e.id())).findFirst().orElseThrow();

        assertThat(john.aliases()).hasSize(2);
        assertThat(john.aliases().get(0).fullName()).isEqualTo("DOE, Johnny");
        assertThat(john.aliases().get(0).nameType()).isEqualTo(NameType.AKA);
        assertThat(john.aliases().get(0).strength()).isEqualTo(NameStrength.STRONG);

        assertThat(john.aliases().get(1).fullName()).isEqualTo("AL-DOE");
        assertThat(john.aliases().get(1).strength()).isEqualTo(NameStrength.WEAK);
    }

    @Test
    void shouldParsePrograms() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity john =
                entities.stream().filter(e -> "1001".equals(e.id())).findFirst().orElseThrow();

        assertThat(john.programs()).hasSize(2);
        assertThat(john.programs().get(0).code()).isEqualTo("SDGT");
        assertThat(john.programs().get(1).code()).isEqualTo("IRAN");
    }

    @Test
    void shouldParseAddresses() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity john =
                entities.stream().filter(e -> "1001".equals(e.id())).findFirst().orElseThrow();

        assertThat(john.addresses()).hasSize(1);
        assertThat(john.addresses().get(0).street()).isEqualTo("123 Main Street");
        assertThat(john.addresses().get(0).city()).isEqualTo("New York");
        assertThat(john.addresses().get(0).country()).isEqualTo("US");
    }

    @Test
    void shouldParseIdentifiers() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity john =
                entities.stream().filter(e -> "1001".equals(e.id())).findFirst().orElseThrow();

        assertThat(john.identifiers()).hasSize(1);
        assertThat(john.identifiers().get(0).type()).isEqualTo(IdentifierType.PASSPORT);
        assertThat(john.identifiers().get(0).value()).isEqualTo("A12345678");
        assertThat(john.identifiers().get(0).issuingCountry()).isEqualTo("US");
    }

    @Test
    void shouldParseDateOfBirth() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity john =
                entities.stream().filter(e -> "1001".equals(e.id())).findFirst().orElseThrow();

        assertThat(john.datesOfBirth()).hasSize(1);
        assertThat(john.datesOfBirth().get(0).getYear()).isEqualTo(1980);
        assertThat(john.datesOfBirth().get(0).getMonthValue()).isEqualTo(1);
        assertThat(john.datesOfBirth().get(0).getDayOfMonth()).isEqualTo(15);
    }

    @Test
    void shouldParseNationalities() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity john =
                entities.stream().filter(e -> "1001".equals(e.id())).findFirst().orElseThrow();

        assertThat(john.nationalities()).containsExactly("US");
    }

    @Test
    void shouldParseEntityType() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity acme =
                entities.stream().filter(e -> "2001".equals(e.id())).findFirst().orElseThrow();
        assertThat(acme.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(acme.primaryName().fullName()).isEqualTo("ACME HOLDINGS LTD");

        SanctionedEntity vessel =
                entities.stream().filter(e -> "3001".equals(e.id())).findFirst().orElseThrow();
        assertThat(vessel.entityType()).isEqualTo(EntityType.VESSEL);
    }

    @Test
    void shouldParseVesselImoNumber() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity vessel =
                entities.stream().filter(e -> "3001".equals(e.id())).findFirst().orElseThrow();

        assertThat(vessel.identifiers()).hasSize(1);
        assertThat(vessel.identifiers().get(0).type()).isEqualTo(IdentifierType.IMO_NUMBER);
        assertThat(vessel.identifiers().get(0).value()).isEqualTo("9876543");
    }

    @Test
    void shouldParseTitle() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity maria =
                entities.stream().filter(e -> "4001".equals(e.id())).findFirst().orElseThrow();

        assertThat(maria.primaryName().title()).isEqualTo("Dr.");
    }

    @Test
    void shouldParseFkaAlias() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity ahmed =
                entities.stream().filter(e -> "5001".equals(e.id())).findFirst().orElseThrow();

        assertThat(ahmed.aliases()).hasSize(2);
        assertThat(ahmed.aliases().get(1).nameType()).isEqualTo(NameType.FKA);
    }

    @Test
    void shouldParseCitizenships() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity maria =
                entities.stream().filter(e -> "4001".equals(e.id())).findFirst().orElseThrow();

        assertThat(maria.citizenships()).containsExactly("MX");
    }

    @Test
    void shouldParsePlacesOfBirth() throws IOException {
        byte[] xmlContent = loadTestResource("sdn_test_sample.xml");
        List<SanctionedEntity> entities = provider.parseXml(xmlContent);

        SanctionedEntity maria =
                entities.stream().filter(e -> "4001".equals(e.id())).findFirst().orElseThrow();

        assertThat(maria.placesOfBirth()).containsExactly("Mexico City");
    }

    @Test
    void shouldHandleEmptyXml() {
        byte[] emptyXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sdnList></sdnList>".getBytes();
        List<SanctionedEntity> entities = provider.parseXml(emptyXml);
        assertThat(entities).isEmpty();
    }

    private static byte[] loadTestResource(String filename) throws IOException {
        try (InputStream is =
                OfacSdnProviderTest.class.getClassLoader().getResourceAsStream(filename)) {
            if (is == null) {
                throw new IOException("Test resource not found: " + filename);
            }
            return is.readAllBytes();
        }
    }
}
