package dev.sieve.risk;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Fires when several amounts sit just under the reporting threshold within a short window, the
 * pattern of splitting a sum to keep each part from being reported.
 */
final class StructuringRule implements RiskRule {

    static final String ID = "structuring";
    static final int POINTS = 35;

    private final BigDecimal threshold;
    private final BigDecimal floor;
    private final int count;
    private final Duration window;

    StructuringRule(BigDecimal threshold, double band, int count, Duration window) {
        this.threshold = threshold;
        this.floor = threshold.multiply(BigDecimal.valueOf(band));
        this.count = count;
        this.window = window;
    }

    @Override
    public Optional<RiskSignal> evaluate(List<Transaction> transactions) {
        List<Transaction> justUnder =
                transactions.stream()
                        .filter(
                                t ->
                                        t.amount().compareTo(floor) >= 0
                                                && t.amount().compareTo(threshold) < 0)
                        .toList();
        if (justUnder.size() < count) {
            return Optional.empty();
        }
        int busiest = Windows.busiest(justUnder, window);
        if (busiest < count) {
            return Optional.empty();
        }
        return Optional.of(
                new RiskSignal(
                        ID,
                        POINTS,
                        busiest
                                + " amounts from "
                                + floor.stripTrailingZeros().toPlainString()
                                + " to just under "
                                + threshold.stripTrailingZeros().toPlainString()
                                + " within "
                                + Windows.describe(window)));
    }
}
