package dev.sieve.core.model;

import java.util.Objects;

/**
 * An identity document or reference number associated with a sanctioned entity.
 *
 * <p>Examples include passport numbers, national IDs, tax IDs, IMO numbers for vessels, and
 * SWIFT/BIC codes for financial institutions.
 *
 * @param type the category of this identifier
 * @param value the identifier value (e.g., passport number)
 * @param issuingCountry ISO 3166-1 alpha-2 country code of the issuing authority, may be {@code null}
 * @param remarks additional context or notes, may be {@code null}
 */
public record Identifier(IdentifierType type, String value, String issuingCountry, String remarks) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code type} or {@code value} is {@code null}
     */
    public Identifier {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
