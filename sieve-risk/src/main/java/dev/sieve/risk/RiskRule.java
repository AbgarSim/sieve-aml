package dev.sieve.risk;

import java.util.List;
import java.util.Optional;

/** One behavioural pattern the scorer looks for in a subject's transactions. */
public interface RiskRule {

    /**
     * Looks for the pattern.
     *
     * @param transactions one subject's transactions, oldest first
     * @return the signal when the pattern is there, or empty
     */
    Optional<RiskSignal> evaluate(List<Transaction> transactions);
}
