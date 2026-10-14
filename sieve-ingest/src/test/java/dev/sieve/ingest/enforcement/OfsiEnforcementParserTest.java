package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OfsiEnforcementParserTest {

    private static String section(
            String href, String name, String date, String penalty, String regs) {
        return "<h3 id=\"x\"><a rel=\"external\" href=\""
                + href
                + "\">"
                + name
                + "</a></h3>"
                + "<table><tbody><tr><td>Date</td><td>"
                + date
                + "</td></tr><tr><td>Sector</td><td>Banking</td></tr>"
                + "<tr><td>Penalty</td><td>  "
                + penalty
                + " </td></tr><tr><td>Reason</td><td>Making funds available to a designated person without a licence</td></tr>"
                + "<tr><td>Regulations</td><td>"
                + regs
                + "</td></tr></tbody></table>";
    }

    @Test
    void shouldParsePenaltiesAndDisclosures() throws Exception {
        String body =
                "<p>intro</p>"
                        + section(
                                "https://assets.publishing.service.gov.uk/media/a/LBG.pdf",
                                "Bank of Scotland PLC",
                                "26 January 2026",
                                "&pound;160,000",
                                "Regulation 11 and 12 of the Russia (Sanctions) (EU Exit) Regulations 2019")
                        + section(
                                "https://assets.publishing.service.gov.uk/media/b/VBL.pdf",
                                "Vanquis Bank Limited (VBL)",
                                "08 September 2025",
                                "Disclosure",
                                "Counter-Terrorism (Sanctions) (EU Exit) Regulations 2019");
        String json =
                new ObjectMapper().writeValueAsString(Map.of("details", Map.of("body", body)));
        var actions = OfsiEnforcementParser.parse(json);
        assertThat(actions).hasSize(2);
        var bos = actions.get(0);
        assertThat(bos.id()).isEqualTo("OFSI-20260126-bank-of-scotland-plc");
        assertThat(bos.date()).isEqualTo(LocalDate.of(2026, 1, 26));
        assertThat(bos.kind()).isEqualTo(Kind.CIVIL_PENALTY);
        assertThat(bos.amount()).contains(160_000L);
        assertThat(bos.currency()).isEqualTo("GBP");
        assertThat(bos.targets()).containsExactly("RU");
        assertThat(bos.summary())
                .contains("Making funds available to a designated person without a licence");
        var vbl = actions.get(1);
        assertThat(vbl.kind()).isEqualTo(Kind.DISCLOSURE);
        assertThat(vbl.amount()).isEmpty();
        assertThat(vbl.programs()).containsExactly("Terrorism");
    }
}
