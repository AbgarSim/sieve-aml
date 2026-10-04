package dev.sieve.core.model;

import java.util.Objects;

/**
 * The kind of link between two entities.
 *
 * <p>Each kind maps to an interval schema of the FollowTheMoney entity model (see {@link
 * #schema()}), so relations can be exported and imported with the entities they link.
 */
public enum RelationType {

    /** The source entity owns all or part of the target. */
    OWNERSHIP("Ownership", "Ownership"),

    /** The source entity is a director, officer or board member of the target. */
    DIRECTORSHIP("Directorship", "Directorship"),

    /** The two people are family members. */
    FAMILY("Family", "Family"),

    /** The two entities are business or personal associates. */
    ASSOCIATE("Associate", "Associate"),

    /** A list links the two entities without saying how, for example "linked to". */
    LINKED("Linked", "UnknownLink"),

    /**
     * The source person holds, or held, a position. The target is the position's id, for example a
     * Wikidata office such as {@code wd-Q11696}, and the relation's role is the position's name.
     */
    POSITION_HELD("Position held", "Occupancy");

    private final String displayName;
    private final String schema;

    RelationType(String displayName, String schema) {
        this.displayName = displayName;
        this.schema = schema;
    }

    /**
     * Returns the FollowTheMoney schema name of this link, for example {@code Ownership} or {@code
     * Occupancy}.
     *
     * @return the schema name
     */
    public String schema() {
        return schema;
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
     * Parses a relation type from its enum name, display name or schema name (case-insensitive).
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
                    || type.displayName.equalsIgnoreCase(trimmed)
                    || type.schema.equalsIgnoreCase(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown RelationType: " + value);
    }
}
