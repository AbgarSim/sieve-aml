package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DojSanctionsParserTest {

    private static final String PAGE =
            """
            {"metadata":{"resultset":{"count":"3"}},"results":[
             {"date":"1784030400","title":"Massachusetts Man Convicted of Violating U.S. Sanctions Against Iran","url":"https://www.justice.gov/opa/pr/a"},
             {"date":"1784116800","title":"Oahu Man Pleads Guilty to Transferring Money to Iran in Violation of U.S. Sanctions","url":"https://www.justice.gov/usao-hi/pr/b"},
             {"date":"1784116800","title":"Justice Department Statement on New Sanctions Policy","url":"https://www.justice.gov/opa/pr/c"}]}
            """;

    @Test
    void shouldKeepReleasesAboutBreakingSanctions() throws Exception {
        var actions = DojSanctionsParser.parse(PAGE);
        assertThat(actions).hasSize(2);
        var a = actions.get(0);
        assertThat(a.date()).isEqualTo(LocalDate.of(2026, 7, 14));
        assertThat(a.targets()).containsExactly("IR");
        assertThat(a.party()).isEmpty();
        assertThat(a.summary())
                .contains("Massachusetts Man Convicted of Violating U.S. Sanctions Against Iran");
        assertThat(DojSanctionsParser.results(PAGE)).isEqualTo(3);
    }

    @Test
    void shouldAskForTitlesContainingSanctions() {
        assertThat(DojSanctionsParser.page(2).toString())
                .contains("page=2")
                .contains("parameters%5Btitle%5D=sanctions");
    }
}
