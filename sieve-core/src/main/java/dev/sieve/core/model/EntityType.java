package dev.sieve.core.model;

import java.util.Objects;

/**
 * Classification of a sanctioned entity.
 *
 * <p>Each sanctions list categorizes entries into one of these types. The most common are {@link
 * #INDIVIDUAL} and {@link #ENTITY} (organizations), with {@link #VESSEL} and {@link #AIRCRAFT}
 * appearing primarily on maritime and aviation-related sanctions programs.
 */
public enum EntityType {

    /** A natural person. */
    INDIVIDUAL("Individual"),

    /** An organization, company, or other legal entity. */
    ENTITY("Entity"),

    /** A maritime vessel (ship, boat, etc.). */
    VESSEL("Vessel"),

    /** An aircraft. */
    AIRCRAFT("Aircraft");

    private final String displayName;

    EntityType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this entity type.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves an {@link EntityType} from a case-insensitive string value.
     *
     * <p>Accepts the enum constant name (e.g., {@code "INDIVIDUAL"}) or the display name (e.g.,
     * {@code "Individual"}).
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link EntityType}
     * @throws IllegalArgumentException if no matching type is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static EntityType fromString(String value) {
        Objects.requireNonNull(value, "EntityType value must not be null");
        String normalized = value.strip().toUpperCase();
        for (EntityType type : values()) {
            if (type.name().equals(normalized)
                    || type.displayName.equalsIgnoreCase(value.strip())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown EntityType: " + value);
    }
}
