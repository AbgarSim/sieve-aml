package dev.sieve.core.model;

import java.util.Objects;

/**
 * Classification of identity documents and reference numbers associated with sanctioned entities.
 *
 * <p>These identifiers appear across all major sanctions lists and are critical for secondary
 * verification during the screening process.
 */
public enum IdentifierType {

    /** Passport number. */
    PASSPORT("Passport"),

    /** National identity card number. */
    NATIONAL_ID("National ID"),

    /** Tax identification number (TIN, SSN, EIN, etc.). */
    TAX_ID("Tax ID"),

    /** IMO number for vessels (International Maritime Organization). */
    IMO_NUMBER("IMO Number"),

    /** MMSI — Maritime Mobile Service Identity. */
    MMSI("MMSI"),

    /** Aircraft registration / tail number. */
    REGISTRATION_NUMBER("Registration Number"),

    /** SWIFT/BIC code for financial institutions. */
    SWIFT_BIC("SWIFT/BIC"),

    /** Legal Entity Identifier (LEI). */
    LEI("LEI"),

    /** Company or business registration number. */
    BUSINESS_REGISTRATION("Business Registration"),

    /** Any identifier type not covered by the other categories. */
    OTHER("Other");

    private final String displayName;

    IdentifierType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this identifier type.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves an {@link IdentifierType} from a case-insensitive string value.
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link IdentifierType}
     * @throws IllegalArgumentException if no matching type is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static IdentifierType fromString(String value) {
        Objects.requireNonNull(value, "IdentifierType value must not be null");
        String normalized = value.strip().toUpperCase().replace(' ', '_').replace('/', '_');
        for (IdentifierType type : values()) {
            if (type.name().equals(normalized)
                    || type.displayName.equalsIgnoreCase(value.strip())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown IdentifierType: " + value);
    }
}
