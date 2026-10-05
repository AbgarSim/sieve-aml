package dev.sieve.ingest.pep;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PublicFunctionCatalogTest {

    private static final PublicFunction CHANCELLOR =
            new PublicFunction(
                    "DE",
                    PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                    "Head of Government",
                    "Federal Chancellor (Bundeskanzler)",
                    null);
    private static final PublicFunction MDB =
            new PublicFunction(
                    "DE",
                    PublicFunctionCategory.LEGISLATORS,
                    "Members of Parliament",
                    "Member of the German Bundestag",
                    null);
    private static final PublicFunction MAYOR =
            new PublicFunction("BG", null, null, "Mayor, municipality (1111 9016)", null);
    private static final PublicFunction EBRD =
            new PublicFunction(
                    "PL",
                    PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS,
                    "International organisations",
                    "Regional Director",
                    "European Bank for Reconstruction and Development Warsaw Resident Office");

    private static PublicFunctionCatalog catalog() {
        return new PublicFunctionCatalog(
                "Prominent public functions",
                "C/2023/724",
                LocalDate.of(2023, 11, 10),
                "http://data.europa.eu/eli/C/2023/724/oj",
                List.of(CHANCELLOR, MDB, MAYOR, EBRD));
    }

    @Test
    void shouldGroupFunctionsByJurisdictionInListOrder() {
        PublicFunctionCatalog catalog = catalog();

        assertThat(catalog.jurisdictions()).containsExactly("DE", "BG", "PL");
        assertThat(catalog.functions("de")).containsExactly(CHANCELLOR, MDB);
        assertThat(catalog.functions("FR")).isEmpty();
        assertThat(catalog.functions(null)).isEmpty();
        assertThat(catalog.lists("pl")).isTrue();
        assertThat(catalog.lists("FR")).isFalse();
        assertThat(catalog.lists(null)).isFalse();
        assertThat(catalog.all()).containsExactly(CHANCELLOR, MDB, MAYOR, EBRD);
        assertThat(catalog.size()).isEqualTo(4);
    }

    @Test
    void shouldFindAnEntryByTheWordsOfAnOfficesName() {
        PublicFunctionCatalog catalog = catalog();

        assertThat(
                        catalog.find(
                                "DE",
                                PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                                "Federal Chancellor of Germany"))
                .contains(CHANCELLOR);
        assertThat(catalog.find("DE", null, "member of the Bundestag")).contains(MDB);
        assertThat(
                        catalog.find(
                                "BG",
                                PublicFunctionCategory.LEGISLATORS,
                                "Mayor of a municipality"))
                .contains(MAYOR);
    }

    @Test
    void shouldNotFindAnEntryOfAnotherCategoryOrFromOneWord() {
        PublicFunctionCatalog catalog = catalog();

        assertThat(
                        catalog.find(
                                "DE",
                                PublicFunctionCategory.LEGISLATORS,
                                "Federal Chancellor of Germany"))
                .isEmpty();
        assertThat(catalog.find("DE", null, "Chancellor")).isEmpty();
        assertThat(catalog.find("FR", null, "Federal Chancellor of Germany")).isEmpty();
    }

    @Test
    void shouldWordAReasonWithTheCategoryAndTheStatesOwnEntry() {
        PublicFunctionCatalog catalog = catalog();

        assertThat(
                        catalog.reason(
                                PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                                "DE",
                                "Federal Chancellor of Germany"))
                .isEqualTo(
                        "Prominent public function under Directive (EU) 2015/849 Art. 3(9)(a) (heads"
                                + " of State, heads of government, ministers and deputy or assistant"
                                + " ministers); on the DE list in OJ C/2023/724: Federal Chancellor"
                                + " (Bundeskanzler)");
        assertThat(
                        catalog.reason(
                                PublicFunctionCategory.LEGISLATORS,
                                "DE",
                                "Prime Minister of Bavaria"))
                .isEqualTo(
                        "Prominent public function under Directive (EU) 2015/849 Art. 3(9)(b)"
                                + " (members of parliament or of similar legislative bodies); on the"
                                + " DE list in OJ C/2023/724");
        assertThat(
                        catalog.reason(
                                PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                                "US",
                                "President of the United States"))
                .isEqualTo(
                        "Prominent public function under Directive (EU) 2015/849 Art. 3(9)(a) (heads"
                                + " of State, heads of government, ministers and deputy or assistant"
                                + " ministers)");
    }

    @Test
    void shouldRoundTripThroughJson() throws Exception {
        PublicFunctionCatalog catalog = catalog();
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        catalog.write(json);

        PublicFunctionCatalog read =
                PublicFunctionCatalog.read(new ByteArrayInputStream(json.toByteArray()));

        assertThat(read.title()).isEqualTo("Prominent public functions");
        assertThat(read.reference()).isEqualTo("C/2023/724");
        assertThat(read.published()).isEqualTo(LocalDate.of(2023, 11, 10));
        assertThat(read.eli()).isEqualTo("http://data.europa.eu/eli/C/2023/724/oj");
        assertThat(read.all()).isEqualTo(catalog.all());
        assertThat(json.toString("UTF-8")).contains("\"category\" : \"h\"").doesNotContain("null");
    }

    @Test
    void shouldReduceNamesToTheirSignificantWords() {
        assertThat(PublicFunctionCatalog.words("Member of the German Bundestag"))
                .containsExactly("member", "german", "bundestag");
        assertThat(PublicFunctionCatalog.words("Judges of the Cour des comptes (France)"))
                .containsExactly("judge", "cour", "compte");
        assertThat(PublicFunctionCatalog.words("Président de la République"))
                .containsExactly("president", "republique");
        assertThat(PublicFunctionCatalog.words(null)).isEmpty();
    }

    @Test
    void shouldShipTheOfficialJournalsCatalogue() {
        PublicFunctionCatalog bundled = PublicFunctionCatalog.bundled();

        assertThat(bundled.reference()).isEqualTo("C/2023/724");
        assertThat(bundled.jurisdictions()).hasSize(28).contains("DE", "FR", "EU");
        assertThat(bundled.size()).isGreaterThan(2000);
        assertThat(
                        bundled.find(
                                        "DE",
                                        PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                                        "Federal Chancellor of Germany")
                                .map(PublicFunction::function))
                .isEqualTo(Optional.of("Federal Chancellor (Bundeskanzler)"));
    }
}
