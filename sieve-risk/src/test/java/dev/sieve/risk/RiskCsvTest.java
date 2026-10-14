package dev.sieve.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RiskCsvTest {

    @Test
    void shouldReadTransactionsWhenFileHasHeaderAndQuotedNames() throws Exception {
        String csv =
                RiskCsv.TRANSACTION_HEADER
                        + "\n"
                        + "t1,acct-1,\"DOE, John\",GB,9500.00,USD,2026-10-01T09:30:00Z,in\n"
                        + "\n"
                        + "t2,acct-1,Grocer,,12.5,USD,2026-10-01T10:00:00Z,OUT\n";

        List<Transaction> txs = RiskCsv.readTransactions(new StringReader(csv));

        assertThat(txs).hasSize(2);
        assertThat(txs.get(0).counterparty()).isEqualTo("DOE, John");
        assertThat(txs.get(0).counterpartyCountry()).contains("GB");
        assertThat(txs.get(0).amount()).isEqualByComparingTo(new BigDecimal("9500"));
        assertThat(txs.get(0).timestamp()).isEqualTo(Instant.parse("2026-10-01T09:30:00Z"));
        assertThat(txs.get(0).direction()).isEqualTo(Direction.IN);
        assertThat(txs.get(1).counterpartyCountry()).isEmpty();
        assertThat(txs.get(1).direction()).isEqualTo(Direction.OUT);
    }

    @Test
    void shouldNameLineWhenRowIsMalformed() {
        String csv =
                RiskCsv.TRANSACTION_HEADER
                        + "\nt1,acct-1,Grocer,US,abc,USD,2026-10-01T09:30:00Z,in\n";

        assertThatThrownBy(() -> RiskCsv.readTransactions(new StringReader(csv)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Line 2: not an amount");
    }

    @Test
    void shouldQuoteFieldsWhenFormattingAssessment() {
        RiskAssessment assessment =
                new RiskAssessment(
                        "acct-1",
                        45,
                        RiskLevel.MEDIUM,
                        4,
                        List.of(
                                new RiskSignal("structuring", 35, "3 amounts, just under"),
                                new RiskSignal("round-amounts", 10, "3 of 4")));

        assertThat(RiskCsv.formatAssessment(assessment))
                .isEqualTo(
                        "acct-1,45,MEDIUM,4,structuring;round-amounts,"
                                + "\"structuring: 3 amounts, just under; round-amounts: 3 of 4\"");
    }

    @Test
    void shouldUnescapeDoubledQuotesWhenSplitting() {
        assertThat(RiskCsv.split("a,\"say \"\"hi\"\"\",c")).containsExactly("a", "say \"hi\"", "c");
        assertThat(Optional.of(RiskCsv.split(""))).get().asList().containsExactly("");
    }
}
