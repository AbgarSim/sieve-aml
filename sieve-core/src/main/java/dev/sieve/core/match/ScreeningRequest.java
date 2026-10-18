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
 * @param criteria facts beyond the name, such as date of birth or country, weighed into the score;
 *     {@link ScreeningCriteria#none()} for a name-only screening
 */
public record ScreeningRequest(
        String name,
        Optional<EntityType> entityType,
        Optional<Set<ListSource>> sources,
        double threshold,
        ScreeningCriteria criteria) {

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
        Objects.requireNonNull(criteria, "criteria must not be null; use ScreeningCriteria.none()");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
        }
    }

    /**
     * A name-only screening request.
     *
     * @param name the name to screen
     * @param entityType optional entity type filter
     * @param sources optional list source filter
     * @param threshold minimum match score
     */
    public ScreeningRequest(
            String name,
            Optional<EntityType> entityType,
            Optional<Set<ListSource>> sources,
            double threshold) {
        this(name, entityType, sources, threshold, ScreeningCriteria.none());
    }

    /**
     * The same request with another threshold.
     *
     * @param newThreshold the threshold
     * @return a copy of this request with {@code newThreshold}
     */
    public ScreeningRequest withThreshold(double newThreshold) {
        return new ScreeningRequest(name, entityType, sources, newThreshold, criteria);
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
