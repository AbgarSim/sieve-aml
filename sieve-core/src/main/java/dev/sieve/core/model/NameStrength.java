package dev.sieve.core.model;

import java.util.Objects;

/**
 * Indicates the confidence strength of a name alias.
 *
 * <p>This concept originates from OFAC's SDN list, where aliases are classified as either strong
 * (high confidence that the alias belongs to the entity) or weak (low confidence or unverified).
 */
public enum NameStrength {

    /** High-confidence alias — strongly associated with the entity. */
    STRONG("Strong"),

    /** Low-confidence or unverified alias. */
    WEAK("Weak");

    private final String displayName;

    NameStrength(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this name strength.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a {@link NameStrength} from a case-insensitive string value.
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link NameStrength}
     * @throws IllegalArgumentException if no matching strength is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static NameStrength fromString(String value) {
        Objects.requireNonNull(value, "NameStrength value must not be null");
        String normalized = value.strip().toUpperCase();
        for (NameStrength strength : values()) {
            if (strength.name().equals(normalized)
                    || strength.displayName.equalsIgnoreCase(value.strip())) {
                return strength;
            }
        }
        throw new IllegalArgumentException("Unknown NameStrength: " + value);
    }
}
