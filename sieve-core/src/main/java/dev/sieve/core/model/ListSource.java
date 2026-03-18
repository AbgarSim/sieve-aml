package dev.sieve.core.model;

import java.util.Objects;

/**
 * Identifies the origin sanctions list for a {@link SanctionedEntity}.
 *
 * <p>Each value corresponds to a publicly available sanctions list maintained by a government or
 * international body.
 */
public enum ListSource {

    /** U.S. Office of Foreign Assets Control — Specially Designated Nationals list. */
    OFAC_SDN("OFAC SDN"),

    /** European Union Consolidated List of sanctions targets. */
    EU_CONSOLIDATED("EU Consolidated"),

    /** United Nations Security Council Consolidated List. */
    UN_CONSOLIDATED("UN Consolidated"),

    /** UK HM Treasury sanctions list. */
    UK_HMT("UK HMT");

    private final String displayName;

    ListSource(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this list source.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a {@link ListSource} from a case-insensitive string value.
     *
     * <p>Accepts the enum constant name (e.g., {@code "OFAC_SDN"}), the display name (e.g., {@code
     * "OFAC SDN"}), or a hyphenated form (e.g., {@code "ofac-sdn"}).
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link ListSource}
     * @throws IllegalArgumentException if no matching source is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static ListSource fromString(String value) {
        Objects.requireNonNull(value, "ListSource value must not be null");
        String normalized = value.strip().toUpperCase().replace('-', '_');
        for (ListSource source : values()) {
            if (source.name().equals(normalized)
                    || source.displayName.equalsIgnoreCase(value.strip())) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown ListSource: " + value);
    }
}
