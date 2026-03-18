package dev.sieve.core.model;

import java.util.Objects;

/**
 * Classification of a name entry for a sanctioned entity.
 *
 * <p>Sanctions lists distinguish between the primary name and various types of aliases.
 */
public enum NameType {

    /** The entity's primary or legal name. */
    PRIMARY("Primary"),

    /** Also Known As — a current alias. */
    AKA("AKA"),

    /** Formerly Known As — a previous name or alias. */
    FKA("FKA"),

    /** A maiden name (pre-marriage surname). */
    MAIDEN("Maiden");

    private final String displayName;

    NameType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this name type.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a {@link NameType} from a case-insensitive string value.
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link NameType}
     * @throws IllegalArgumentException if no matching type is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static NameType fromString(String value) {
        Objects.requireNonNull(value, "NameType value must not be null");
        String normalized = value.strip().toUpperCase();
        for (NameType type : values()) {
            if (type.name().equals(normalized)
                    || type.displayName.equalsIgnoreCase(value.strip())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown NameType: " + value);
    }
}
