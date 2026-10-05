package dev.sieve.ingest.pep;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EuPublicFunctionsParserTest {

    /** OJ C/2023/724 as the Publications Office renders it, gzipped. */
    private static final String FIXTURE = "/eu_public_functions_C_2023_724.xhtml.gz";

    private static PublicFunctionCatalog catalog;

    @BeforeAll
    static void parseTheOfficialJournal() throws IOException {
        try (InputStream in =
                new GZIPInputStream(
                        EuPublicFunctionsParserTest.class.getResourceAsStream(FIXTURE))) {
            catalog = EuPublicFunctionsParser.parse(in);
        }
    }

    @Test
    void shouldReadTheDocumentsTitleNumberDateAndIdentifier() {
        assertThat(catalog.title())
                .startsWith("Prominent public functions at national level")
                .endsWith("European Union Institutions and Bodies");
        assertThat(catalog.reference()).isEqualTo("C/2023/724");
        assertThat(catalog.published()).isEqualTo(LocalDate.of(2023, 11, 10));
        assertThat(catalog.eli()).isEqualTo("http://data.europa.eu/eli/C/2023/724/oj");
    }

    @Test
    void shouldReadEveryMemberStatesListAndTheUnions() {
        assertThat(catalog.jurisdictions())
                .containsExactly(
                        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR",
                        "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK",
                        "SI", "ES", "SE", "EU");
        assertThat(catalog.size()).isEqualTo(2161);
        assertThat(catalog.functions("DE")).hasSize(27);
        assertThat(catalog.functions("DK")).hasSize(220);
        assertThat(catalog.functions("LT")).hasSize(308);
        assertThat(catalog.functions("MT")).hasSize(152);
        assertThat(catalog.functions("EU")).hasSize(10);
    }

    @Test
    void shouldFileAHeadedListUnderItsHeadingsAndCategories() {
        assertThat(catalog.functions("DE"))
                .contains(
                        new PublicFunction(
                                "DE",
                                PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                                "Head of Government",
                                "Federal Chancellor (Bundeskanzler)",
                                null),
                        new PublicFunction(
                                "DE",
                                PublicFunctionCategory.LEGISLATORS,
                                "Members of Parliament",
                                "Member of the German Bundestag",
                                null));
    }

    @Test
    void shouldDropSectionReferencesFromHeadings() {
        List<PublicFunction> denmark = catalog.functions("DK");

        assertThat(denmark)
                .extracting(PublicFunction::heading, PublicFunction::category)
                .contains(tuple("Supreme Court judges", PublicFunctionCategory.HIGH_COURTS));
        assertThat(denmark)
                .extracting(PublicFunction::heading)
                .filteredOn(heading -> heading != null)
                .allSatisfy(heading -> assertThat(heading).doesNotContain("(Section"));
    }

    @Test
    void shouldPairInternationalOrganisationsWithTheirPosts() {
        assertThat(catalog.functions("PL"))
                .extracting(
                        PublicFunction::category,
                        PublicFunction::organisation,
                        PublicFunction::function)
                .contains(
                        tuple(
                                PublicFunctionCategory.INTERNATIONAL_ORGANISATIONS,
                                "European Bank for Reconstruction and Development Warsaw Resident"
                                        + " Office",
                                "Regional Director"));
        assertThat(catalog.functions("SI"))
                .extracting(PublicFunction::organisation, PublicFunction::function)
                .contains(
                        tuple("CEF Centre of Excellence in Finance", "Director"),
                        tuple("CEF Centre of Excellence in Finance", "Deputy Director"));
    }

    @Test
    void shouldKeepListsIntroducedByALongLeadIn() {
        assertThat(catalog.functions("LT"))
                .extracting(PublicFunction::category, PublicFunction::function)
                .contains(
                        tuple(
                                PublicFunctionCategory.STATE_OWNED_ENTERPRISES,
                                "Members of the management or supervisory body of AB Kaunas Energy"
                                        + " (AB Kauno energija)"));
        assertThat(catalog.functions("MT"))
                .extracting(
                        PublicFunction::category, PublicFunction::heading, PublicFunction::function)
                .contains(
                        tuple(
                                PublicFunctionCategory.STATE_OWNED_ENTERPRISES,
                                "Members of the administrative, Management or Supervisory boards"
                                        + " including Chairpersons, Chief Executive Officers and"
                                        + " Board of Directors of State-Owned Enterprises",
                                "Airmalta Aviation Services Ltd"));
    }

    @Test
    void shouldLeaveNationalAdditionsWithoutADirectiveCategory() {
        assertThat(catalog.functions("BG"))
                .extracting(PublicFunction::category, PublicFunction::function)
                .contains(tuple(null, "Mayor, municipality (1111 9016)"));
        assertThat(catalog.functions("SK"))
                .extracting(PublicFunction::category, PublicFunction::function)
                .contains(
                        tuple(
                                PublicFunctionCategory.PARTY_GOVERNING_BODIES,
                                "members of the statutory body of a political party or political"
                                        + " movement"));
    }

    @Test
    void shouldReadTheUnionsOwnList() {
        assertThat(catalog.functions("EU"))
                .extracting(PublicFunction::category, PublicFunction::function)
                .contains(
                        tuple(
                                PublicFunctionCategory.HEADS_OF_STATE_AND_GOVERNMENT,
                                "the President of the European Council and the Secretary-General of"
                                        + " the Council of the European Union"),
                        tuple(
                                PublicFunctionCategory.HIGH_COURTS,
                                "the Judges, Advocates-General and Registrar of the Court of"
                                        + " Justice"),
                        tuple(
                                PublicFunctionCategory.AUDITORS_AND_CENTRAL_BANKS,
                                "the Members of the European Court of Auditors"));
    }

    @Test
    void shouldLeaveNoListPunctuationOrLinksInFunctions() {
        assertThat(catalog.all())
                .allSatisfy(
                        function -> {
                            assertThat(function.function()).isNotBlank();
                            assertThat(function.function()).doesNotStartWith("http");
                            assertThat(function.function()).doesNotStartWith("—");
                            assertThat(function.function()).doesNotStartWith("-");
                            assertThat(function.function()).doesNotEndWith(";");
                            assertThat(function.function()).doesNotEndWith(":");
                            assertThat(function.function()).doesNotEndWith(",");
                            assertThat(function.jurisdiction()).matches("[A-Z]{2}");
                        });
    }

    @Test
    void shouldShipExactlyWhatTheOfficialJournalParsesTo() {
        PublicFunctionCatalog bundled = PublicFunctionCatalog.bundled();

        assertThat(bundled.title()).isEqualTo(catalog.title());
        assertThat(bundled.reference()).isEqualTo(catalog.reference());
        assertThat(bundled.published()).isEqualTo(catalog.published());
        assertThat(bundled.eli()).isEqualTo(catalog.eli());
        assertThat(bundled.all()).isEqualTo(catalog.all());
    }

    @Test
    void shouldRejectADocumentWithoutLists() {
        String html = "<html><body><p class=\"oj-normal\">Nothing here</p></body></html>";

        assertThatThrownBy(
                        () ->
                                EuPublicFunctionsParser.parse(
                                        new ByteArrayInputStream(
                                                html.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IOException.class);
    }

    @Test
    void shouldTidyFunctionsAsTheListsWriteThem() {
        assertThat(EuPublicFunctionsParser.clean("— the President of the Republic;"))
                .isEqualTo("the President of the Republic");
        assertThat(EuPublicFunctionsParser.clean("Members of parliament (Section 2(1)(8)(b))"))
                .isEqualTo("Members of parliament");
        assertThat(EuPublicFunctionsParser.clean("Supreme Court judges (1)."))
                .isEqualTo("Supreme Court judges");
        assertThat(EuPublicFunctionsParser.clean("(Under-Secretary-General)"))
                .isEqualTo("Under-Secretary-General");
    }

    @Test
    void shouldTidyHeadingsOfTheirLeadInsAndUnclosedAsides() {
        assertThat(EuPublicFunctionsParser.headingText("Section 2 – Members of parliament"))
                .isEqualTo("Members of parliament");
        assertThat(
                        EuPublicFunctionsParser.headingText(
                                "Members of boards of State-Owned Enterprises (which are here to be"
                                        + " construed as those where the state owns more than 50 %:"))
                .isEqualTo("Members of boards of State-Owned Enterprises");
    }

    @Test
    void shouldTellItemMarkersFromText() {
        assertThat(EuPublicFunctionsParser.marker("1."))
                .isEqualTo(EuPublicFunctionsParser.Marker.NUMBER);
        assertThat(EuPublicFunctionsParser.marker("(a)"))
                .isEqualTo(EuPublicFunctionsParser.Marker.LETTER);
        assertThat(EuPublicFunctionsParser.marker("—"))
                .isEqualTo(EuPublicFunctionsParser.Marker.DASH);
        assertThat(EuPublicFunctionsParser.marker("*"))
                .isEqualTo(EuPublicFunctionsParser.Marker.STAR);
        assertThat(EuPublicFunctionsParser.marker(" "))
                .isEqualTo(EuPublicFunctionsParser.Marker.EMPTY);
        assertThat(EuPublicFunctionsParser.marker("Mayor"))
                .isEqualTo(EuPublicFunctionsParser.Marker.NONE);
    }
}
