package dev.sieve.risk;

import java.util.Objects;

/**
 * A behavioural rule that fired for a subject, with the points it adds to the risk score.
 *
 * @param rule the rule's identifier, such as {@code structuring}
 * @param points how much the rule adds to the score
 * @param detail what the rule saw, in words an analyst can check against the transactions
 */
public record RiskSignal(String rule, int points, String detail) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code rule} or {@code detail} is {@code null}
     * @throws IllegalArgumentException if {@code points} is negative
     */
    public RiskSignal {
        Objects.requireNonNull(rule, "rule must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        if (points < 0) {
            throw new IllegalArgumentException("points must not be negative");
        }
    }
}
