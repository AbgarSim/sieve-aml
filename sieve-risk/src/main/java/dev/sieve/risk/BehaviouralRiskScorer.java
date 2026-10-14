package dev.sieve.risk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scores how risky a subject's behaviour is from its transactions.
 *
 * <p>Each {@link RiskRule} looks for one pattern (many transactions in a short time, amounts just
 * under the reporting threshold, round amounts, high-risk countries, money passing straight
 * through, listed counterparties). The score is the sum of the points of the rules that fire,
 * capped at 100, and the assessment lists every rule that fired with what it saw, so an analyst can
 * see why a subject scored as it did.
 */
public final class BehaviouralRiskScorer {

    private static final Logger log = LoggerFactory.getLogger(BehaviouralRiskScorer.class);

    private final List<RiskRule> rules;

    /**
     * Creates a scorer that runs the given rules in order.
     *
     * @param rules the rules
     */
    public BehaviouralRiskScorer(List<RiskRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
    }

    /**
     * Creates a scorer with Sieve's built-in rules.
     *
     * @param config the rules' thresholds
     * @param screener screens counterparty names against the lists
     * @return the scorer
     */
    public static BehaviouralRiskScorer withDefaultRules(
            RiskConfig config, CounterpartyScreener screener) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(screener, "screener must not be null");
        return new BehaviouralRiskScorer(
                List.of(
                        new ListedCounterpartyRule(screener),
                        new StructuringRule(
                                config.reportingThreshold(),
                                config.structuringBand(),
                                config.structuringCount(),
                                config.structuringWindow()),
                        new HighRiskJurisdictionRule(config.highRiskCountries()),
                        new PassThroughRule(config.passThroughWindow(), config.passThroughShare()),
                        new VelocityRule(config.velocityCount(), config.velocityWindow()),
                        new RoundAmountRule(
                                config.roundUnit(), config.roundShare(), config.roundMinCount())));
    }

    /**
     * Assesses one subject.
     *
     * @param subjectId the subject
     * @param transactions the subject's transactions, in any order
     * @return the assessment
     */
    public RiskAssessment assess(String subjectId, Collection<Transaction> transactions) {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        List<Transaction> sorted =
                transactions.stream().sorted(Comparator.comparing(Transaction::timestamp)).toList();
        List<RiskSignal> signals = new ArrayList<>();
        int score = 0;
        if (!sorted.isEmpty()) {
            for (RiskRule rule : rules) {
                rule.evaluate(sorted).ifPresent(signals::add);
            }
            score = Math.min(100, signals.stream().mapToInt(RiskSignal::points).sum());
        }
        log.debug(
                "Assessed subject [subject={}, transactions={}, score={}]",
                subjectId,
                sorted.size(),
                score);
        return new RiskAssessment(subjectId, score, RiskLevel.of(score), sorted.size(), signals);
    }

    /**
     * Assesses every subject in a batch of transactions.
     *
     * @param transactions transactions of any number of subjects
     * @return one assessment per subject, in the order subjects first appear
     */
    public List<RiskAssessment> assessAll(Collection<Transaction> transactions) {
        Map<String, List<Transaction>> bySubject = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            bySubject.computeIfAbsent(t.subjectId(), k -> new ArrayList<>()).add(t);
        }
        List<RiskAssessment> assessments = new ArrayList<>(bySubject.size());
        bySubject.forEach((subject, txs) -> assessments.add(assess(subject, txs)));
        return assessments;
    }
}
