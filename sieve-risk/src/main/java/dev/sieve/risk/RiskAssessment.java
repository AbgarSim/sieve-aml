package dev.sieve.risk;

import java.util.List;
import java.util.Objects;

/**
 * The behavioural risk of one subject, worked out from its transactions.
 *
 * @param subjectId the subject assessed
 * @param score the sum of the fired rules' points, capped at 100
 * @param level the band the score falls in
 * @param transactionCount how many transactions were assessed
 * @param signals the rules that fired, in the order the scorer runs them
 */
public record RiskAssessment(
        String subjectId,
        int score,
        RiskLevel level,
        int transactionCount,
        List<RiskSignal> signals) {

    /**
     * Compact constructor with validation and a defensive copy.
     *
     * @throws NullPointerException if any parameter is {@code null}
     * @throws IllegalArgumentException if {@code score} is outside [0, 100]
     */
    public RiskAssessment {
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(level, "level must not be null");
        signals = List.copyOf(Objects.requireNonNull(signals, "signals must not be null"));
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
    }
}
