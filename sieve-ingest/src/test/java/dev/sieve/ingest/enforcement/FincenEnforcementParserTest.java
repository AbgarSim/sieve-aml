package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FincenEnforcementParserTest {

    private static final String PAGE =
            "<table><thead><tr><th>Enforcement Action</th><th>Date</th></tr></thead><tbody>"
                    + "<tr><td><a href=\"/system/files/enforcement_action/2026-10-13/BofA.pdf\">In the Matter of Bank of America, N.A.</a></td>"
                    + "<td><time>10/13/2026</time></td><td>2026-03</td><td>Depository Institutions</td></tr>"
                    + "<tr><td><a href=\"/x.pdf\">Paxful, Inc. and Paxful USA, Inc.</a></td><td>12/09/2025</td><td>2025-02</td><td>Money Services Businesses</td></tr>"
                    + "</tbody></table>";

    @Test
    void shouldParseTheTable() {
        var actions = FincenEnforcementParser.parse(PAGE);
        assertThat(actions).hasSize(2);
        var a = actions.get(0);
        assertThat(a.id()).isEqualTo("FINCEN-20261013-bank-of-america-n-a");
        assertThat(a.party()).contains("Bank of America, N.A.");
        assertThat(a.date()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(a.summary()).contains("Depository Institutions");
        assertThat(a.url())
                .isEqualTo(
                        "https://www.fincen.gov/system/files/enforcement_action/2026-10-13/BofA.pdf");
    }

    @Test
    void shouldReadOnlyCountriesTiedToSanctions() {
        String text =
                "The bank has offices in Mexico and Cuba. Paxful facilitated trades by users in Iran,"
                        + " North Korea and Venezuela, jurisdictions subject to comprehensive OFAC sanctions.";
        assertThat(FincenEnforcementParser.programsIn(text).targets())
                .containsExactly("IR", "KP", "VE");
    }

    @Test
    void shouldReadThePenalty() {
        assertThat(
                        FincenEnforcementParser.penaltyIn(
                                "assess a civil money penalty in the amount of $3,500,000 against"))
                .contains(3_500_000L);
        assertThat(FincenEnforcementParser.penaltyIn("agreed to pay $1.3 billion")).isEmpty();
        assertThat(FincenEnforcementParser.penaltyIn("no amount here")).isEmpty();
        assertThat(
                        FincenEnforcementParser.penaltyIn(
                                "The Bank agreed to pay $3,000,000,000 to all agencies. FinCEN"
                                        + " will impose a Civil Money Penalty in the amount of $1,300,000,000."
                                        + " A Civil Money Penalty in the amount of $25,000 is the floor."))
                .contains(1_300_000_000L);
        assertThat(FincenEnforcementParser.penaltyIn("a penalty of up to $25,000 per violation"))
                .isEmpty();
        assertThat(
                        FincenEnforcementParser.penaltyIn(
                                "a civil money penalty of up to $71,545 per day. A penalty"
                                        + " applies to transactions of at least $5,000. FinCEN"
                                        + " will impose a Civil Money Penalty in the amount of $80,000,000 on"
                                        + " the Bank."))
                .contains(80_000_000L);
    }
}
