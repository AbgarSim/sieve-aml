package dev.sieve.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BehaviouralRiskScorerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T09:00:00Z");

    private final BehaviouralRiskScorer scorer =
            BehaviouralRiskScorer.withDefaultRules(
                    RiskConfig.defaults(),
                    name ->
                            name.equalsIgnoreCase("Listed Trading LLC")
                                    ? Optional.of(
                                            new CounterpartyHit(
                                                    name, "LISTED TRADING LLC", "OFAC_SDN", 1.0))
                                    : Optional.empty());

    @Test
    void shouldScoreLowWhenBehaviourIsOrdinary() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "Grocer", "US", "84.12", 0, Direction.OUT),
                        tx("s1", "Employer Inc", "US", "3120.55", 24, Direction.IN),
                        tx("s1", "Utility Co", "US", "142.90", 72, Direction.OUT));

        RiskAssessment assessment = scorer.assess("s1", txs);

        assertThat(assessment.score()).isZero();
        assertThat(assessment.level()).isEqualTo(RiskLevel.LOW);
        assertThat(assessment.signals()).isEmpty();
        assertThat(assessment.transactionCount()).isEqualTo(3);
    }

    @Test
    void shouldFlagStructuringWhenAmountsSitJustUnderThreshold() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "Cash", "", "9500", 0, Direction.IN),
                        tx("s1", "Cash", "", "9800.50", 30, Direction.IN),
                        tx("s1", "Cash", "", "9900", 60, Direction.IN));

        RiskAssessment assessment = scorer.assess("s1", txs);

        assertThat(rules(assessment)).containsExactly("structuring");
        assertThat(assessment.score()).isEqualTo(35);
        assertThat(assessment.level()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(assessment.signals().get(0).detail())
                .isEqualTo("3 amounts from 9000 to just under 10000 within 7 days");
    }

    @Test
    void shouldNotFlagStructuringWhenAmountsAreSpreadOut() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "Cash", "", "9500.10", 0, Direction.IN),
                        tx("s1", "Cash", "", "9800.50", 24 * 10, Direction.IN),
                        tx("s1", "Cash", "", "9900.20", 24 * 20, Direction.IN));

        assertThat(rules(scorer.assess("s1", txs))).isEmpty();
    }

    @Test
    void shouldFlagVelocityWhenManyTransactionsFallInOneDay() {
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            txs.add(tx("s1", "Shop " + i, "US", "12.34", i, Direction.OUT));
        }

        RiskAssessment assessment = scorer.assess("s1", txs);

        assertThat(rules(assessment)).containsExactly("velocity");
        assertThat(assessment.signals().get(0).detail()).isEqualTo("10 transactions within 1 day");
    }

    @Test
    void shouldFlagRoundAmountsWhenMostAreWholeThousands() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "A", "US", "2000", 0, Direction.OUT),
                        tx("s1", "B", "US", "5000", 24 * 10, Direction.OUT),
                        tx("s1", "C", "US", "3000.00", 24 * 20, Direction.OUT),
                        tx("s1", "D", "US", "41.20", 24 * 30, Direction.OUT));

        RiskAssessment assessment = scorer.assess("s1", txs);

        assertThat(rules(assessment)).containsExactly("round-amounts");
        assertThat(assessment.level()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void shouldFlagHighRiskJurisdictionWhenCounterpartyIsInOne() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "Supplier", "ir", "850.40", 0, Direction.OUT),
                        tx("s1", "Supplier", "DE", "120.10", 48, Direction.OUT));

        RiskAssessment assessment = scorer.assess("s1", txs);

        assertThat(rules(assessment)).containsExactly("high-risk-jurisdiction");
        assertThat(assessment.signals().get(0).detail())
                .isEqualTo("1 transaction(s) with counterparties in IR");
    }

    @Test
    void shouldFlagPassThroughWhenIncomingMoneyLeavesQuickly() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "Sender", "GB", "25000.75", 0, Direction.IN),
                        tx("s1", "Recipient A", "GB", "12000.30", 5, Direction.OUT),
                        tx("s1", "Recipient B", "GB", "11000.45", 20, Direction.OUT));

        assertThat(rules(scorer.assess("s1", txs))).containsExactly("pass-through");
    }

    @Test
    void shouldFlagListedCounterpartyWhenScreenerMatches() {
        List<Transaction> txs =
                List.of(tx("s1", "Listed Trading LLC", "AE", "730.25", 0, Direction.OUT));

        RiskAssessment assessment = scorer.assess("s1", txs);

        assertThat(rules(assessment)).containsExactly("listed-counterparty");
        assertThat(assessment.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(assessment.signals().get(0).detail())
                .isEqualTo("Listed Trading LLC matches LISTED TRADING LLC on OFAC_SDN (1.00)");
    }

    @Test
    void shouldCapScoreAtHundredWhenManyRulesFire() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "Listed Trading LLC", "IR", "9500", 0, Direction.IN),
                        tx("s1", "Cash", "", "9600", 1, Direction.IN),
                        tx("s1", "Cash", "", "9700", 2, Direction.IN),
                        tx("s1", "Out", "KP", "28000", 3, Direction.OUT));

        RiskAssessment assessment = scorer.assess("s1", txs);

        assertThat(rules(assessment))
                .containsExactly(
                        "listed-counterparty",
                        "structuring",
                        "high-risk-jurisdiction",
                        "pass-through");
        assertThat(assessment.score()).isEqualTo(100);
        assertThat(assessment.level()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void shouldAssessEachSubjectSeparatelyWhenBatchMixesSubjects() {
        List<Transaction> txs =
                List.of(
                        tx("a", "Listed Trading LLC", "AE", "10.10", 0, Direction.OUT),
                        tx("b", "Grocer", "US", "20.20", 0, Direction.OUT),
                        tx("a", "Grocer", "US", "30.30", 1, Direction.OUT));

        List<RiskAssessment> assessments = scorer.assessAll(txs);

        assertThat(assessments).extracting(RiskAssessment::subjectId).containsExactly("a", "b");
        assertThat(assessments.get(0).level()).isEqualTo(RiskLevel.HIGH);
        assertThat(assessments.get(0).transactionCount()).isEqualTo(2);
        assertThat(assessments.get(1).level()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void shouldScoreZeroWhenSubjectHasNoTransactions() {
        RiskAssessment assessment = scorer.assess("s1", List.of());

        assertThat(assessment.score()).isZero();
        assertThat(assessment.transactionCount()).isZero();
    }

    @Test
    void shouldBandScoresIntoLevels() {
        assertThat(RiskLevel.of(29)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.of(30)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.of(59)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.of(60)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void shouldCountBusiestWindowWhenTimestampsAreUneven() {
        List<Transaction> txs =
                List.of(
                        tx("s1", "A", "", "1", 0, Direction.OUT),
                        tx("s1", "A", "", "1", 30, Direction.OUT),
                        tx("s1", "A", "", "1", 31, Direction.OUT),
                        tx("s1", "A", "", "1", 32, Direction.OUT));

        assertThat(Windows.busiest(txs, Duration.ofHours(2))).isEqualTo(3);
        assertThat(Windows.busiest(List.of(), Duration.ofHours(2))).isZero();
    }

    private static List<String> rules(RiskAssessment assessment) {
        return assessment.signals().stream().map(RiskSignal::rule).toList();
    }

    private static int seq;

    private static Transaction tx(
            String subject,
            String counterparty,
            String country,
            String amount,
            int hoursAfterStart,
            Direction direction) {
        return new Transaction(
                "t" + (++seq),
                subject,
                counterparty,
                country.isEmpty() ? Optional.empty() : Optional.of(country),
                new BigDecimal(amount),
                "USD",
                T0.plus(Duration.ofHours(hoursAfterStart)),
                direction);
    }
}
