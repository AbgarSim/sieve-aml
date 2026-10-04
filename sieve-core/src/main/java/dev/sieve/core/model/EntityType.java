package dev.sieve.core.model;

import java.util.Objects;

/**
 * The kind of thing an entry in the risk database describes.
 *
 * <p>Most lists only say whether an entry is a person or not, so {@link #INDIVIDUAL} and the
 * generic {@link #ENTITY} cover most records. Sources that say more use {@link #COMPANY} or {@link
 * #ORGANIZATION}. {@link #VESSEL} and {@link #AIRCRAFT} appear mainly on maritime and aviation
 * programs, {@link #CRYPTO_WALLET} on lists of digital currency addresses and {@link #SECURITY} for
 * listed shares and bonds.
 *
 * <p>Each kind maps to a schema name of the open FollowTheMoney entity model (see {@link
 * #schema()}), so records can be exported to and imported from tools that read that format.
 */
public enum EntityType {

    /** A natural person. */
    INDIVIDUAL("Individual", "Person"),

    /** A legal entity whose source does not say whether it is a company or an organisation. */
    ENTITY("Entity", "LegalEntity"),

    /** A maritime vessel (ship, boat, etc.). */
    VESSEL("Vessel", "Vessel"),

    /** An aircraft. */
    AIRCRAFT("Aircraft", "Airplane"),

    /** A company or other business registered for profit. */
    COMPANY("Company", "Company"),

    /**
     * A non-commercial body: a government agency, armed group, party, charity or similar
     * organisation.
     */
    ORGANIZATION("Organization", "Organization"),

    /** A digital currency address, linked to its owner by a relation. */
    CRYPTO_WALLET("Crypto wallet", "CryptoWallet"),

    /** A tradable financial instrument such as a share or bond, identified by its ISIN. */
    SECURITY("Security", "Security");

    private final String displayName;
    private final String schema;

    EntityType(String displayName, String schema) {
        this.displayName = displayName;
        this.schema = schema;
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
     * Returns the schema name of this kind in the FollowTheMoney entity model, for example {@code
     * Person} or {@code Airplane}.
     *
     * @return the schema name, never {@code null}
     */
    public String schema() {
        return schema;
    }

    /**
     * Returns whether this kind is a legal entity: {@link #ENTITY}, {@link #COMPANY} or {@link
     * #ORGANIZATION}.
     *
     * @return {@code true} for the three legal entity kinds
     */
    public boolean isLegalEntity() {
        return this == ENTITY || this == COMPANY || this == ORGANIZATION;
    }

    /**
     * Returns whether a record of this kind can describe the same thing as a record of the other
     * kind. Equal kinds are compatible, and the generic {@link #ENTITY} is compatible with {@link
     * #COMPANY} and {@link #ORGANIZATION}, because a list that only says "entity" may mean either.
     * A company and an organisation are not compatible.
     *
     * @param other the other kind, must not be {@code null}
     * @return {@code true} if the two kinds are compatible
     */
    public boolean isCompatibleWith(EntityType other) {
        Objects.requireNonNull(other, "other must not be null");
        if (this == other) {
            return true;
        }
        return isLegalEntity() && other.isLegalEntity() && (this == ENTITY || other == ENTITY);
    }

    /**
     * Resolves an {@link EntityType} from a case-insensitive string value.
     *
     * <p>Accepts the enum constant name (e.g., {@code "INDIVIDUAL"}), the display name (e.g.,
     * {@code "Individual"}), the schema name (e.g., {@code "Person"}) or the British spelling
     * {@code "Organisation"}.
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link EntityType}
     * @throws IllegalArgumentException if no matching type is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static EntityType fromString(String value) {
        Objects.requireNonNull(value, "EntityType value must not be null");
        String stripped = value.strip();
        if (stripped.equalsIgnoreCase("Organisation")) {
            return ORGANIZATION;
        }
        for (EntityType type : values()) {
            if (type.name().equalsIgnoreCase(stripped)
                    || type.displayName.equalsIgnoreCase(stripped)
                    || type.schema.equalsIgnoreCase(stripped)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown EntityType: " + value);
    }

    /**
     * Resolves an {@link EntityType} from a FollowTheMoney schema name. Schemas with no kind of
     * their own map to the nearest one: {@code PublicBody} to {@link #ORGANIZATION} and {@code
     * Asset} or {@code Thing} to {@link #ENTITY}.
     *
     * @param schema the schema name, must not be {@code null}
     * @return the matching kind
     * @throws IllegalArgumentException if the schema does not describe an entity Sieve holds
     */
    public static EntityType fromSchema(String schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        for (EntityType type : values()) {
            if (type.schema.equals(schema)) {
                return type;
            }
        }
        return switch (schema) {
            case "PublicBody" -> ORGANIZATION;
            case "Asset", "Thing" -> ENTITY;
            default -> throw new IllegalArgumentException("Unsupported schema: " + schema);
        };
    }
}
