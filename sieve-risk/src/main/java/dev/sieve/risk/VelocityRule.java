package dev.sieve.risk;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Fires when many transactions fall inside a short window. */
final class VelocityRule implements RiskRule {

    static final String ID = "velocity";
    static final int POINTS = 20;

    private final int count;
    private final Duration window;

    VelocityRule(int count, Duration window) {
        this.count = count;
        this.window = window;
    }

    @Override
    public Optional<RiskSignal> evaluate(List<Transaction> transactions) {
        int busiest = Windows.busiest(transactions, window);
        if (busiest < count) {
            return Optional.empty();
        }
        return Optional.of(
                new RiskSignal(
                        ID, POINTS, busiest + " transactions within " + Windows.describe(window)));
    }
}
