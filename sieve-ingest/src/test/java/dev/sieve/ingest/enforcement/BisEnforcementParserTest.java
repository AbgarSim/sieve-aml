package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BisEnforcementParserTest {

    private static Map<String, Object> release(
            String title, String path, long published, String topic) {
        return Map.of(
                "title",
                title,
                "path",
                path,
                "publishedOn",
                Map.of("timestamp", published),
                "newsUpdatesTopic",
                List.of(Map.of("name", topic)));
    }

    private static String page(List<Map<String, Object>> releases) throws Exception {
        String data =
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "props",
                                        Map.of(
                                                "pageProps",
                                                Map.of("pressReleaseTeasers", releases))));
        return "<html><body><script id=\"__NEXT_DATA__\" type=\"application/json\">"
                + data
                + "</script></body></html>";
    }

    @Test
    void shouldKeepPenaltiesAndCriminalCases() throws Exception {
        // 1790956800 is 2 October 2026 16:00 UTC.
        String html =
                page(
                        List.of(
                                release(
                                        "BIS Reaches Administrative Enforcement Settlement with Lambda Research Corporation",
                                        "/press-release/lambda",
                                        1790956800L,
                                        "Enforcement"),
                                release(
                                        "BIS Reaches Administrative Enforcement Settlement with Lambda Research Corporation",
                                        "/press-release/lambda-again",
                                        1790956800L,
                                        "Enforcement"),
                                release(
                                        "Applied Materials to Pay $252 Million Penalty to BIS for Illegally Exporting Semiconductor Manufacturing Equipment",
                                        "/press-release/applied",
                                        1790956800L,
                                        "Enforcement"),
                                release(
                                        "Alabama Man Sentenced to Five Years in Prison for Violating U.S. Sanctions on Iran",
                                        "/press-release/alabama",
                                        1740700800L,
                                        "Enforcement"),
                                release(
                                        "Commerce Adds Four To Entity List Related To Circumvention Of U.S. Sanctions Against Venezuela, Russia",
                                        "/press-release/entity-list",
                                        1700000000L,
                                        "Enforcement"),
                                release(
                                        "Missouri-Based Defense Department Contractor Admits Fraud",
                                        "/press-release/fraud",
                                        1700000000L,
                                        "Enforcement"),
                                release(
                                        "BIS Imposes Administrative Penalty on Example Ltd.",
                                        "/press-release/rule",
                                        1700000000L,
                                        "Regulations")));
        List<EnforcementAction> actions = BisEnforcementParser.parse(html);
        assertThat(actions).hasSize(3);
        EnforcementAction lambda = actions.get(0);
        assertThat(lambda.id()).isEqualTo("BIS-20261002-lambda-research-corporation");
        assertThat(lambda.date()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(lambda.kind()).isEqualTo(Kind.CIVIL_PENALTY);
        assertThat(lambda.party()).contains("Lambda Research Corporation");
        assertThat(lambda.url()).isEqualTo("https://www.bis.gov/press-release/lambda");
        EnforcementAction applied = actions.get(1);
        assertThat(applied.party()).contains("Applied Materials");
        assertThat(applied.amount()).contains(252_000_000L);
        EnforcementAction alabama = actions.get(2);
        assertThat(alabama.kind()).isEqualTo(Kind.CRIMINAL_CASE);
        assertThat(alabama.targets()).containsExactly("IR");
        assertThat(alabama.amount()).isEmpty();
    }

    @Test
    void shouldReadPartiesAndAmountsFromTitles() {
        assertThat(
                        BisEnforcementParser.party(
                                "BIS Imposes Administrative Penalty on Hallewell Ventures Ltd."))
                .contains("Hallewell Ventures Ltd.");
        assertThat(
                        BisEnforcementParser.party(
                                "BIS Reaches Administrative Enforcement Settlement with Andritz Inc. ORDER"))
                .contains("Andritz Inc.");
        assertThat(
                        BisEnforcementParser.party(
                                "Robert Bosch GmbH (Bosch) To Pay $36 Million Penalty to BIS for Violations"))
                .contains("Robert Bosch GmbH");
        assertThat(
                        BisEnforcementParser.party(
                                "BIS Imposes $180,000 Mitigated Penalty Against Indium Corporation for 11 Exports"))
                .contains("Indium Corporation");
        assertThat(
                        BisEnforcementParser.party(
                                "BIS Imposes Penalty on Quantum Corporation to Resolve Alleged Violations of the Antiboycott Regulations"))
                .contains("Quantum Corporation");
        assertThat(
                        BisEnforcementParser.party(
                                "BIS Reaches Administrative Enforcement Settlement with Thermotron Industries, Inc. Additional information on the settlement is available online at"))
                .contains("Thermotron Industries, Inc.");
        assertThat(
                        BisEnforcementParser.party(
                                "BIS Imposes Administrative Penalty on Virginia Company for Exports to Russian Military End User"))
                .isEmpty();
        assertThat(
                        BisEnforcementParser.kind(
                                "Former federal agent convicted of corruption in connection to illicit Syrian relationship"))
                .isEmpty();
        assertThat(
                        BisEnforcementParser.amount(
                                "Robert Bosch GmbH (Bosch) To Pay $36 Million Penalty"))
                .contains(36_000_000L);
        assertThat(
                        BisEnforcementParser.amount(
                                "Microsoft To Pay Over $3.3m In Total Combined Civil Penalties To Bis And Ofac"))
                .isEmpty();
    }

    @Test
    void shouldFailWithoutReleaseData() {
        assertThatThrownBy(() -> BisEnforcementParser.parse("<html></html>"))
                .isInstanceOf(IOException.class);
    }
}
