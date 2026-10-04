package dev.sieve.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Where a value came from and when it was seen.
 *
 * @param source the list that published the value
 * @param sourceUrl the address the value was read from, may be {@code null} if unknown
 * @param firstSeen when Sieve first saw the value on this list
 * @param lastSeen when Sieve last saw the value on this list
 */
public record Provenance(ListSource source, String sourceUrl, Instant firstSeen, Instant lastSeen) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code source}, {@code firstSeen} or {@code lastSeen} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code lastSeen} is before {@code firstSeen}
     */
    public Provenance {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(firstSeen, "firstSeen must not be null");
        Objects.requireNonNull(lastSeen, "lastSeen must not be null");
        if (lastSeen.isBefore(firstSeen)) {
            throw new IllegalArgumentException("lastSeen must not be before firstSeen");
        }
    }
}
