package dev.sieve.core.model;

import java.util.Objects;

/**
 * Why an entity is of interest to a compliance team.
 *
 * <p>An entity can carry several topics, for example a sanctioned person who is also a politically
 * exposed person. Each topic has a short dotted code that is stable across releases and is used in
 * API responses and exports.
 */
public enum RiskTopic {

    /** Listed under a sanctions program (asset freeze, travel ban, arms embargo). */
    SANCTION("sanction", "Sanctioned"),

    /** Owned or controlled by a sanctioned party without being listed itself. */
    SANCTION_LINKED("sanction.linked", "Sanction-linked"),

    /** Subject to export-control restrictions, such as an entity or end-user list. */
    EXPORT_CONTROL("export.control", "Export control"),

    /** Excluded from contracts by a government or development bank. */
    DEBARMENT("debarment", "Debarred"),

    /** Holds, or has held, a prominent public function. */
    PEP("role.pep", "Politically exposed person"),

    /** A relative or close associate of a politically exposed person. */
    RCA("role.rca", "Relative or close associate"),

    /** Convicted of, or charged with, a crime. */
    CRIME("crime", "Crime"),

    /** Sought by law enforcement. */
    WANTED("wanted", "Wanted"),

    /** Owned or controlled by a state. */
    STATE_OWNED("gov.soe", "State-owned");

    private final String code;
    private final String displayName;

    RiskTopic(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     * Returns the stable dotted code, for example {@code role.pep}.
     *
     * @return the topic code
     */
    public String code() {
        return code;
    }

    /**
     * Returns the human-readable name of this topic.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Parses a topic from its code, enum name or display name (case-insensitive).
     *
     * @param value the value to parse, must not be {@code null}
     * @return the matching topic
     * @throws IllegalArgumentException if no topic matches
     */
    public static RiskTopic fromString(String value) {
        Objects.requireNonNull(value, "RiskTopic value must not be null");
        String trimmed = value.strip();
        for (RiskTopic topic : values()) {
            if (topic.code.equalsIgnoreCase(trimmed)
                    || topic.name().equalsIgnoreCase(trimmed)
                    || topic.displayName.equalsIgnoreCase(trimmed)) {
                return topic;
            }
        }
        throw new IllegalArgumentException("Unknown RiskTopic: " + value);
    }
}
