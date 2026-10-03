package dev.sieve.core.model;

import java.util.Objects;

/** The kind of link between two entities. */
public enum RelationType {

    /** The source entity owns all or part of the target. */
    OWNERSHIP("Ownership"),

    /** The source entity is a director, officer or board member of the target. */
    DIRECTORSHIP("Directorship"),

    /** The two people are family members. */
    FAMILY("Family"),

    /** The two entities are business or personal associates. */
    ASSOCIATE("Associate"),

    /** A list links the two entities without saying how, for example "linked to". */
    LINKED("Linked");

    private final String displayName;

    RelationType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable name of this relation type.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Parses a relation type from its enum name or display name (case-insensitive).
     *
     * @param value the value to parse, must not be {@code null}
     * @return the matching relation type
     * @throws IllegalArgumentException if no type matches
     */
    public static RelationType fromString(String value) {
        Objects.requireNonNull(value, "RelationType value must not be null");
        String trimmed = value.strip();
        for (RelationType type : values()) {
            if (type.name().equalsIgnoreCase(trimmed)
                    || type.displayName.equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown RelationType: " + value);
    }
}
