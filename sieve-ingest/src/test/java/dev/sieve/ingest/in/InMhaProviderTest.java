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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMhaProviderTest {

    private static final URI PAGE =
            URI.create(
                    "https://www.mha.gov.in/individuals-terrorists-fourth-schedule-unlawful-activities-prevention-act?page=0");

    @Test
    void shouldNumberThePeopleInListOrder() throws Exception {
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
                        "Notification: https://www.mha.gov.in/files/documents/"
                                + "UAPANewList_06072026_3%20TEST.pdf");
    }

    @Test
    void shouldFindNoRowsWhenThePageHasNone() {
        assertThat(MhaDocumentList.rows("<html><body><p>Maintenance</p></body></html>", PAGE))
                .isEmpty();
    }

    @Test
    void shouldFailWhenNoRowNamesAnyone() {
        assertThatThrownBy(
                        () ->
                                InMhaProvider.parse(
                                        List.of(
                                                new MhaDocumentList.Row(
                                                        "@ .", URI.create("https://x/y.pdf")))))
                .isInstanceOf(ListIngestionException.class)
                .hasMessageContaining("layout");
    }

    @Test
    void shouldAskForTheListOldestFirstTwentyToAPage() {
        assertThat(MhaDocumentList.page(PAGE, 3))
                .hasToString(
                        "https://www.mha.gov.in/individuals-terrorists-fourth-schedule-unlawful-activities-prevention-act"
                                + "?sort_order=ASC&items_per_page=20&page=3");
    }

    private static List<SanctionedEntity> parseSample() throws Exception {
        try (InputStream in =
                InMhaProviderTest.class.getResourceAsStream("/in_mha_test_sample.html")) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return InMhaProvider.parse(MhaDocumentList.rows(html, PAGE));
        }
    }
}
