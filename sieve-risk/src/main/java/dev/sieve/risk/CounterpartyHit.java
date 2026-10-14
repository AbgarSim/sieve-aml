package dev.sieve.risk;

import java.util.Objects;

/**
 * A counterparty name that matched a listed entity.
 *
 * @param counterparty the name as it appears on the transaction
 * @param matchedName the listed entity's name
 * @param source the list the entity is on
 * @param score the match score, from 0.0 to 1.0
 */
public record CounterpartyHit(
        String counterparty, String matchedName, String source, double score) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any name is {@code null}
     */
    public CounterpartyHit {
        Objects.requireNonNull(counterparty, "counterparty must not be null");
        Objects.requireNonNull(matchedName, "matchedName must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
