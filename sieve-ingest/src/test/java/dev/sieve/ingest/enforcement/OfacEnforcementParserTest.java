package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OfacEnforcementParserTest {

    private static final String PAGE =
            "<table class=\"table usa-table\"><thead><tr><th>Detailed</th><th>Name</th><th>Aggregate</th><th>Total</th></tr></thead><tbody>"
                    + "<tr><td><a href=\"/media/937231/download?inline\" title=\"20261013_bank_of_america.pdf\"><strong>10/13/2026</strong></a></td>"
                    + "<td>Bank of America, N.A.</td><td><p class=\"text-align-center\">1</p></td><td><p class=\"text-align-right\">11,662,930</p></td></tr>"
                    + "<tr><td><a href=\"/media/12896/download?inline\"><strong>12/05/2003</strong></a></td>"
                    + "<td>Aeronet Worldwide</td><td><p>1</p></td><td><p>3,978</p></td></tr>"
                    + "<tr><td><a href=\"/media/12896/download?inline\"><strong>12/05/2003</strong></a></td>"
                    + "<td>Aeronet Worldwide</td><td><p>1</p></td><td><p>2,750</p></td></tr>"
                    + "<tr><td>no date</td><td>x</td><td>1</td><td>1</td></tr>"
                    + "</tbody></table>";

    @Test
    void shouldParseRowsOfTheChart() {
        var actions = OfacEnforcementParser.parse(PAGE, OfacEnforcementParser.CURRENT);
        assertThat(actions).hasSize(3);
        var a = actions.get(0);
        assertThat(a.id()).isEqualTo("OFAC-20261013-bank-of-america-n-a");
        assertThat(a.date()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(a.party()).contains("Bank of America, N.A.");
        assertThat(a.amount()).contains(11_662_930L);
        assertThat(a.violations()).contains(1);
        assertThat(a.url()).isEqualTo("https://ofac.treasury.gov/media/937231/download?inline");
        assertThat(actions.get(2).id()).isEqualTo("OFAC-20031205-aeronet-worldwide-2");
    }

    @Test
    void shouldAddressEarlierYearsByYear() {
        assertThat(OfacEnforcementParser.yearPage(2026, 2026))
                .isEqualTo(OfacEnforcementParser.CURRENT);
        assertThat(OfacEnforcementParser.yearPage(2003, 2026))
                .isEqualTo(
                        URI.create(
                                "https://ofac.treasury.gov/civil-penalties-and-enforcement-information/2003-enforcement-information"));
    }

    @Test
    void shouldReadTheProgramNextToThePartyInASharedNotice() {
        String text =
                "Name of Entity Involved Address Sanctions Program\nAeronet Worldwide\nSan Francisco, CA 94083\nSudan\n"
                        + "2000 Attempted export of goods to Sudan\n$\n3,978.00\nS\nBethyl Laboratories, Inc.\nMontgomery, TX\nIran\n$ 4,125.00";
        assertThat(OfacEnforcementParser.programsFor(text, "Aeronet Worldwide", true).targets())
                .containsExactly("SD");
        assertThat(
                        OfacEnforcementParser.programsFor(text, "Bethyl Laboratories, Inc.", true)
                                .targets())
                .containsExactly("IR");
        assertThat(OfacEnforcementParser.programsFor(text, "Nobody", true).isEmpty()).isTrue();
    }
}
