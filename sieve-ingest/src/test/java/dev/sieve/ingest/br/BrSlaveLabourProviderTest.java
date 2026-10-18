package dev.sieve.ingest.br;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrSlaveLabourProviderTest {

    @Test
    void shouldReadEveryEmployer() throws Exception {
        List<SanctionedEntity> entities = parse();

        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .containsExactly(
                        "br-41297068000161",
                        "br-07680247730",
                        "br-32264221000163",
                        "br-32264221000163-2");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.listSource()).isEqualTo(ListSource.BR_SLAVE_LABOUR);
                            assertThat(e.topics()).containsExactly(RiskTopic.CRIME);
                        });
    }

    @Test
    void shouldTellPeopleFromCompaniesByTheirTaxId() throws Exception {
        List<SanctionedEntity> entities = parse();

        SanctionedEntity person = entities.get(1);
        assertThat(person.entityType()).isEqualTo(EntityType.INDIVIDUAL);
        assertThat(person.primaryName().fullName()).isEqualTo("ABEL PIONA BERNABÉ");
        assertThat(person.identifiers())
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.type()).isEqualTo(IdentifierType.TAX_ID);
                            assertThat(i.value()).isEqualTo("076.802.477-30");
                            assertThat(i.remarks()).isEqualTo("CPF");
                        });
        SanctionedEntity company = entities.get(0);
        assertThat(company.entityType()).isEqualTo(EntityType.ENTITY);
        assertThat(company.identifiers().get(0).remarks()).isEqualTo("CNPJ");
        assertThat(company.aliases())
                .extracting(NameInfo::fullName)
                .containsExactly("GILBERTO ELENO BATISTA DOS SANTOS");
    }

    @Test
    void shouldMapAddressDatesAndRemarks() throws Exception {
        SanctionedEntity company = parse().get(0);

        assertThat(company.addresses())
                .singleElement()
                .satisfies(
                        a -> {
                            assertThat(a.stateOrProvince()).isEqualTo("SP");
                            assertThat(a.country()).isEqualTo("BR");
                            assertThat(a.fullAddress()).contains("IGARATÁ/SP");
                        });
        assertThat(company.listedDate()).isEqualTo(Instant.parse("2025-10-06T00:00:00Z"));
        assertThat(company.remarks())
                .contains("Workers found: 2", "Business activity (CNAE): 4399-1/03");
        assertThat(company.listingReasons())
                .singleElement()
                .asString()
                .contains("analogous to slavery");
        assertThat(parse().get(2).listedDate()).isEqualTo(Instant.parse("2025-10-06T00:00:00Z"));
    }

    private static List<SanctionedEntity> parse() throws Exception {
        try (InputStream in =
                BrSlaveLabourProviderTest.class.getResourceAsStream(
                        "/br_slave_labour_sample.csv")) {
            return new BrSlaveLabourProvider(
                            URI.create("http://localhost"), HttpClient.newHttpClient())
                    .parseResponse(in.readAllBytes());
        }
    }
}
