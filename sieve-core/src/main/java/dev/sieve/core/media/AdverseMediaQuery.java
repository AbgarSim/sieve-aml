package dev.sieve.core.media;

import java.time.Duration;
import java.util.Objects;

/**
 * A request for candidate adverse media articles about a name.
 *
 * @param name the person or organisation name, must not be blank
 * @param lookback how far back to search, between one day and the index's limit
 * @param maxArticles the most articles to return, between 1 and 250
 */
public record AdverseMediaQuery(String name, Duration lookback, int maxArticles) {

    /** Default search window. */
    public static final Duration DEFAULT_LOOKBACK = Duration.ofDays(90);

    /** Default number of articles. */
    public static final int DEFAULT_MAX_ARTICLES = 25;

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code name} or {@code lookback} is {@code null}
     * @throws IllegalArgumentException if {@code name} is blank, {@code lookback} is shorter than a
     *     day or {@code maxArticles} is outside 1 to 250
     */
    public AdverseMediaQuery {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(lookback, "lookback must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        name = name.strip();
        if (lookback.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("lookback must be at least one day");
        }
        if (maxArticles < 1 || maxArticles > 250) {
            throw new IllegalArgumentException("maxArticles must be between 1 and 250");
        }
    }

    /**
     * Creates a query with the default window and article count.
     *
     * @param name the name to search for
     * @return the query
     */
    public static AdverseMediaQuery of(String name) {
        return new AdverseMediaQuery(name, DEFAULT_LOOKBACK, DEFAULT_MAX_ARTICLES);
    }
}
