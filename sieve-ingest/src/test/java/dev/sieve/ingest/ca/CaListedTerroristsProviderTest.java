package dev.sieve.ingest.ca;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.EntityType;
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

class CaListedTerroristsProviderTest {

    @Test
    void shouldReadEveryEntry() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities)
                .extracting(e -> e.primaryName().fullName())
                .containsExactly(
                        "Abdullah Azzam Brigades (AAB)",
                        "Abu Nidal Organization (ANO)",
                        "Proud Boys");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.listSource()).isEqualTo(ListSource.CA_LISTED_TERRORISTS);
                            assertThat(e.entityType()).isEqualTo(EntityType.ORGANIZATION);
                            assertThat(e.topics()).containsExactly(RiskTopic.SANCTION);
                            assertThat(e.id()).startsWith("ca-lte-");
                            assertThat(e.programs().get(0).code())
                                    .isEqualTo("Criminal Code s. 83.05");
                        });
    }

    @Test
    void shouldReadAliasesFromTheTitleAndSummary() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities.get(0).aliases())
                .extracting(NameInfo::fullName)
                .containsExactly(
                        "Abdullah Azzam Brigades",
                        "AAB",
                        "The Abdullah Azzam Brigades",
                        "the Ziyad al-Jarrah Battalions",
                        "the Marwan Hadid Brigades");
        assertThat(entities.get(1).aliases())
                .extracting(NameInfo::fullName)
                .containsExactly(
                        "Abu Nidal Organization",
                        "ANO",
                        "Fatah Revolutionary Council",
                        "Black June",
                        "Arab Revolutionary Brigades");
        assertThat(entities.get(2).aliases()).isEmpty();
    }

    @Test
    void shouldKeepDescriptionAndDates() throws Exception {
        SanctionedEntity aab = parseSample().get(0);

        assertThat(aab.remarks()).startsWith("The AAB is an Al Qaida affiliated");
        assertThat(aab.listedDate()).isEqualTo(Instant.parse("2015-06-29T00:00:00Z"));
        assertThat(aab.lastUpdated()).isEqualTo(Instant.parse("2024-06-07T00:00:00Z"));
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        try (InputStream in =
                CaListedTerroristsProviderTest.class.getResourceAsStream(
                        "/ca_listed_terrorists_sample.xml")) {
            return new CaListedTerroristsProvider(
                            URI.create("http://localhost"), HttpClient.newHttpClient())
                    .parseResponse(in.readAllBytes());
        }
    }
}
