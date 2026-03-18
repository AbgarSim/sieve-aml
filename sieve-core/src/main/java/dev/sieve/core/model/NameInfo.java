package dev.sieve.core.model;

import java.util.Objects;

/**
 * Structured name information for a sanctioned entity.
 *
 * <p>A single entity may have multiple {@link NameInfo} entries: one primary name and zero or more
 * aliases (AKAs, FKAs, maiden names). The {@link #fullName} field is always populated and contains
 * the pre-composed name as it appears on the source list.
 *
 * @param fullName pre-composed full name (e.g., "LAST, First Middle" or script-original)
 * @param givenName the given (first) name, may be {@code null} if unparsed
 * @param familyName the family (last) name, may be {@code null} if unparsed
 * @param middleName the middle name, may be {@code null}
 * @param title honorific or professional title (e.g., "Dr.", "Mr."), may be {@code null}
 * @param nameType classification of this name entry
 * @param strength confidence strength of this alias (OFAC concept)
 * @param script the writing script of this name
 */
public record NameInfo(
        String fullName,
        String givenName,
        String familyName,
        String middleName,
        String title,
        NameType nameType,
        NameStrength strength,
        ScriptType script) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code fullName} or {@code nameType} is {@code null}
     */
    public NameInfo {
        Objects.requireNonNull(fullName, "fullName must not be null");
        Objects.requireNonNull(nameType, "nameType must not be null");
    }
}
