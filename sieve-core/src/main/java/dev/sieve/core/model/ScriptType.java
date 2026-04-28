package dev.sieve.core.model;

import java.util.Objects;

/**
 * Writing script classification for a {@link NameInfo} entry.
 *
 * <p>Sanctions lists may include names in multiple scripts. This enum helps downstream matching
 * engines apply script-appropriate normalization and comparison logic.
 */
public enum ScriptType {

    /** Latin / Roman alphabet (English, French, Spanish, etc.). */
    LATIN("Latin"),

    /** Cyrillic alphabet (Russian, Ukrainian, Serbian, etc.). */
    CYRILLIC("Cyrillic"),

    /** Arabic script (Arabic, Persian, Urdu, etc.). */
    ARABIC("Arabic"),

    /** CJK ideographs (Chinese, Japanese Kanji, Korean Hanja). */
    CJK("CJK"),

    /** Any script not covered by the other categories. */
    OTHER("Other");

    private final String displayName;

    ScriptType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the human-readable display name for this script type.
     *
     * @return the display name, never {@code null}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Resolves a {@link ScriptType} from a case-insensitive string value.
     *
     * @param value the string to parse, must not be {@code null}
     * @return the matching {@link ScriptType}
     * @throws IllegalArgumentException if no matching type is found
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static ScriptType fromString(String value) {
        Objects.requireNonNull(value, "ScriptType value must not be null");
        String normalized = value.strip().toUpperCase();
        for (ScriptType type : values()) {
            if (type.name().equals(normalized)
                    || type.displayName.equalsIgnoreCase(value.strip())) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown ScriptType: " + value);
    }
}
