package dev.sieve.ingest.enforcement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HmrcSettlementParserTest {

    private static String content(String body) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("details", Map.of("body", body)));
    }

    @Test
    void shouldParseNamedSettlementsAndSkipBoilerplate() throws Exception {
        String body =
                "<div class=\"govspeak\"><p>The current list contains compound settlements which"
                        + " have been agreed from 13 August 2026 to date.</p>"
                        + "<h2 id=\"a\">Illumina Cambridge Limited (published 8 September 2026)</h2>"
                        + "<h3 id=\"overview\">1. Overview&nbsp;</h3>"
                        + "<p>Illumina Cambridge Limited (Illumina) has paid HM Revenue and Customs"
                        + " (HMRC) a compound settlement of &pound;7,438,840.13 for offences under The"
                        + " Russia (Sanctions) (EU Exit) Regulations 2019.</p>"
                        + "<h3 id=\"criteria\">2. Compound settlements criteria&nbsp;</h3>"
                        + "<p>voluntarily told HMRC about Iran sanctions or export control breaches</p>"
                        + "<h2 id=\"b\">Airbus Operations Limited (published 30 July 2026)</h2>"
                        + "<p>Airbus Operations Limited (AOL) has paid more than £6.4 million to HM"
                        + " Revenue and Customs (HMRC) after admitting multiple breaches of Strategic"
                        + " Export Controls.</p>"
                        + "<h2 id=\"c\">Two anonymous cases (published 27 July 2026)</h2>"
                        + "<p>In June 2026, 2 UK exporters paid compound settlement offers of"
                        + " £216,530.30 and £20,889.15 to HM Revenue and Customs (HMRC).</p></div>";
        List<EnforcementAction> actions = HmrcSettlementParser.parseSettlements(content(body));
        assertThat(actions).hasSize(3);
        EnforcementAction illumina = actions.get(0);
        assertThat(illumina.id()).isEqualTo("HMRC-20260908-illumina-cambridge-limited");
        assertThat(illumina.authority()).isEqualTo("HMRC");
        assertThat(illumina.date()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(illumina.party()).contains("Illumina Cambridge Limited");
        assertThat(illumina.amount()).contains(7_438_840L);
        assertThat(illumina.currency()).isEqualTo("GBP");
        assertThat(illumina.kind()).isEqualTo(Kind.CIVIL_PENALTY);
        assertThat(illumina.programs()).containsExactly("Russia");
        assertThat(illumina.url()).startsWith(HmrcSettlementParser.PAGE.toString() + "#");
        EnforcementAction airbus = actions.get(1);
        assertThat(airbus.amount()).contains(6_400_000L);
        assertThat(airbus.programs()).isEmpty();
        EnforcementAction anonymous = actions.get(2);
        assertThat(anonymous.party()).isEmpty();
        assertThat(anonymous.amount()).contains(216_530L);
    }

    @Test
    void shouldKeepOnlyNoticesToExportersAboutSettlementsOrCourtCases() throws Exception {
        String json =
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "results",
                                        List.of(
                                                Map.of(
                                                        "title",
                                                        "Notice to exporters 2024/29: compound settlement for breaches of Russian sanctions – August 2024",
                                                        "link",
                                                        "/government/publications/nte-2024-29",
                                                        "public_timestamp",
                                                        "2024-11-04T09:30:00.000+00:00"),
                                                Map.of(
                                                        "title",
                                                        "Notice to exporters 2026/03: director jailed for illegal export",
                                                        "link",
                                                        "/government/publications/nte-2026-03",
                                                        "public_timestamp",
                                                        "2026-02-19T09:30:00.000+00:00"),
                                                Map.of(
                                                        "title",
                                                        "Excise Notice 202: registered mobile operators",
                                                        "link",
                                                        "/government/publications/excise",
                                                        "public_timestamp",
                                                        "2025-11-18T09:30:00.000+00:00"))));
        var notices = HmrcSettlementParser.notices(json);
        assertThat(notices)
                .extracting(HmrcSettlementParser.Notice::path)
                .containsExactly(
                        "/government/publications/nte-2024-29",
                        "/government/publications/nte-2026-03");
        assertThat(notices.get(0).published()).isEqualTo(LocalDate.of(2024, 11, 4));
    }

    @Test
    void shouldReadEachListedPaymentAndSkipTotals() throws Exception {
        var notice =
                new HmrcSettlementParser.Notice(
                        "Notice to exporters 2023/23: compound settlements",
                        "/government/publications/notice-to-exporters-202323-compound-settlements",
                        LocalDate.of(2023, 12, 1));
        String body =
                "<p>Between August and November 2023, HM Revenue and Customs ( HMRC ) issued"
                        + " compound settlement offers to three UK exporters totalling in excess of"
                        + " £77,000.00.</p><ul>"
                        + "<li>August 2023 - £67,001.31 was paid in relation to the attempted export"
                        + " of goods in breach of The Russia (Sanctions) (EU Exit) Regulations 2019</li>"
                        + "<li>September 2023 - £1,000 was paid relating to the attempted export of"
                        + " Dual Use goods controlled by Retained Regulation 428/2009</li></ul>"
                        + "<ul><li>to date the UK has sanctioned over 1,600 individuals and entities,"
                        + " including 29 banks with global assets worth £1 trillion</li></ul>";
        var actions = HmrcSettlementParser.parseNotice(notice, content(body));
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).id()).isEqualTo("HMRC-20231201-nte-2023-23-1");
        assertThat(actions.get(0).amount()).contains(67_001L);
        assertThat(actions.get(0).targets()).containsExactly("RU");
        assertThat(actions.get(0).party()).isEmpty();
        assertThat(actions.get(0).url())
                .isEqualTo(
                        "https://www.gov.uk/government/publications/notice-to-exporters-202323-compound-settlements");
        assertThat(actions.get(1).amount()).contains(1_000L);
        assertThat(actions.get(1).programs()).isEmpty();
    }

    @Test
    void shouldReadASingleSettlementAndCourtCases() throws Exception {
        var single =
                new HmrcSettlementParser.Notice(
                        "Notice to exporters 2023/17 : a compound settlement",
                        "/government/publications/nte-2023-17",
                        LocalDate.of(2023, 8, 21));
        var fined =
                HmrcSettlementParser.parseNotice(
                        single,
                        content(
                                "<p>In August 2023, a UK company was fined £1 million by HM Revenue &amp;"
                                        + " Customs ( HMRC ) in relation to the unlicensed trade of goods in"
                                        + " breach of The Russian (Sanctions) (EU Exit) Regulations 2019.</p>"
                                        + "<p>An additional £50 million in funding will be available.</p>"));
        assertThat(fined).hasSize(1);
        assertThat(fined.get(0).amount()).contains(1_000_000L);
        assertThat(fined.get(0).targets()).containsExactly("RU");

        var court =
                new HmrcSettlementParser.Notice(
                        "Notice to exporters 2026/03: director jailed for illegal export",
                        "/government/publications/nte-2026-03",
                        LocalDate.of(2026, 2, 19));
        var jailed =
                HmrcSettlementParser.parseNotice(
                        court,
                        content("<p>A company director was jailed for exporting to Iran.</p>"));
        assertThat(jailed).hasSize(1);
        assertThat(jailed.get(0).kind()).isEqualTo(Kind.CRIMINAL_CASE);
        assertThat(jailed.get(0).summary()).contains("director jailed for illegal export");
        assertThat(jailed.get(0).targets()).containsExactly("IR");
    }

    @Test
    void shouldFindTheTextAttachmentAndMatchRepeatedSettlements() throws Exception {
        String json =
                new ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "details",
                                        Map.of(
                                                "attachments",
                                                List.of(
                                                        Map.of(
                                                                "url",
                                                                "/government/publications/x/nte-x")))));
        assertThat(HmrcSettlementParser.attachment(json))
                .contains(
                        java.net.URI.create(
                                "https://www.gov.uk/api/content/government/publications/x/nte-x"));

        var page =
                HmrcSettlementParser.parseSettlements(
                                content(
                                        "<h2>Petrofac Facilities Management Limited (published 29 June 2026)</h2>"
                                                + "<p>PFML have paid a compound settlement of £569,157.07.</p>"))
                        .get(0);
        var notice =
                HmrcSettlementParser.parseNotice(
                                new HmrcSettlementParser.Notice(
                                        "Notice to exporters 2026/15: firm named after Russia sanctions breach settlement",
                                        "/government/publications/nte-2026-15",
                                        LocalDate.of(2026, 6, 29)),
                                content(
                                        "<p>Petrofac Facilities Management Limited ( PFML ) have paid HM Revenue and"
                                                + " Customs a compound settlement of £569,157.07.</p>"))
                        .get(0);
        assertThat(HmrcSettlementParser.sameSettlement(page, notice)).isTrue();
    }
}
