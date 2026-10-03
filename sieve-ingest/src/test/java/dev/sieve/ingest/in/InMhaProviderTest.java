package dev.sieve.ingest.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMhaProviderTest {

    @Test
    void shouldParseOnePersonPerNumberedParagraph() throws Exception {
        List<SanctionedEntity> entities = parseSample();

        assertThat(entities)
                .extracting(SanctionedEntity::id)
                .containsExactly("in-mha-1", "in-mha-2", "in-mha-3");
        assertThat(entities)
                .allSatisfy(
                        e -> {
                            assertThat(e.entityType()).isEqualTo(EntityType.INDIVIDUAL);
                            assertThat(e.listSource()).isEqualTo(ListSource.IN_MHA);
                            assertThat(e.topics()).containsExactly(RiskTopic.SANCTION);
                            assertThat(e.programs())
                                    .singleElement()
                                    .satisfies(p -> assertThat(p.code()).isEqualTo("UAPA-4"));
                        });
    }

    @Test
    void shouldSplitAliasesOnAtSignWhenNameHasSeveral() throws Exception {
        SanctionedEntity person = parseSample().get(0);

        assertThat(person.primaryName().fullName()).isEqualTo("Ravi Example Kumar");
        assertThat(person.aliases())
                .extracting(a -> a.fullName())
                .containsExactly("Ravi Exampel", "R. K. Example");
    }

    @Test
    void shouldMarkOneWordAliasesWeakWhenTheyAreNicknames() throws Exception {
        SanctionedEntity person = parseSample().get(1);

        assertThat(person.aliases())
                .extracting(a -> a.fullName(), a -> a.strength())
                .containsExactly(tuple("Doctor", NameStrength.WEAK));
    }

    @Test
    void shouldStripClosingQuotesWhenNumberHasNoSpace() throws Exception {
        SanctionedEntity person = parseSample().get(2);

        assertThat(person.primaryName().fullName()).isEqualTo("Imran Test Khan");
        assertThat(person.aliases())
                .extracting(a -> a.fullName())
                .containsExactly("Test Bhai", "M. Imran");
    }

    @Test
    void shouldLinkTheNotificationInRemarks() throws Exception {
        SanctionedEntity person = parseSample().get(2);

        assertThat(person.remarks())
                .isEqualTo(
                        "Notification: https://www.mha.gov.in/sites/default/files/"
                                + "Individual_Terrorists/SL%20NO%2003_TEST.pdf");
    }

    @Test
    void shouldFailWhenPageHasNoEntries() {
        byte[] html = "<html><body><p>Maintenance</p></body></html>".getBytes();

        assertThatThrownBy(() -> new InMhaProvider().parseResponse(html))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("layout");
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        try (InputStream in =
                InMhaProviderTest.class.getResourceAsStream("/in_mha_test_sample.html")) {
            return new InMhaProvider().parseResponse(in.readAllBytes());
        }
    }
}
