package dev.sieve.risk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One movement of money in or out of a subject's account.
 *
 * @param id the transaction's own identifier
 * @param subjectId the customer or account whose behaviour is assessed
 * @param counterparty the name of the other party
 * @param counterpartyCountry the other party's country as an ISO 3166-1 alpha-2 code, when known
 * @param amount the amount, positive, in {@code currency}
 * @param currency the ISO 4217 currency code
 * @param timestamp when the transaction took place
 * @param direction whether the subject received or sent the money
 */
public record Transaction(
        String id,
        String subjectId,
        String counterparty,
        Optional<String> counterpartyCountry,
        BigDecimal amount,
        String currency,
        Instant timestamp,
        Direction direction) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any parameter is {@code null}
     * @throws IllegalArgumentException if {@code subjectId} is blank or {@code amount} is not
     *     positive
     */
    public Transaction {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(counterparty, "counterparty must not be null");
        Objects.requireNonNull(counterpartyCountry, "counterpartyCountry must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        if (subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
    }
}
