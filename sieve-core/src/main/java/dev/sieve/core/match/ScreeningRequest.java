package dev.sieve.core.match;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A request to screen a name against the sanctions index.
 *
 * @param name the name to screen, must not be {@code null} or blank
 * @param entityType optional filter to restrict results to a specific entity type
 * @param sources optional filter to restrict results to specific list sources
 * @param threshold minimum match score (0.0–1.0) for results to be included
 */
public record ScreeningRequest(
        String name,
        Optional<EntityType> entityType,
        Optional<Set<ListSource>> sources,
        double threshold) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code name}, {@code entityType}, or {@code sources} is
     *     {@code null}
     * @throws IllegalArgumentException if {@code name} is blank or {@code threshold} is outside
     *     [0.0, 1.0]
     */
    public ScreeningRequest {
        Objects.requireNonNull(name, "Name must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null; use Optional.empty()");
        Objects.requireNonNull(sources, "sources must not be null; use Optional.empty()");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
        }
    }

    /**
     * Convenience factory for a simple name-only screening request with default threshold.
     *
     * @param name the name to screen
     * @param threshold minimum match score
     * @return a new screening request with no filters
     */
    public static ScreeningRequest of(String name, double threshold) {
        return new ScreeningRequest(name, Optional.empty(), Optional.empty(), threshold);
    }
}
