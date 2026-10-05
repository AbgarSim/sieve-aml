package dev.sieve.core.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A page about an entity: its listing page, a legal act that listed it, an article.
 *
 * @param url the page
 * @param title what the page is, as a reader would name it, may be {@code null}
 * @param kind what kind of page it is
 * @param date when the page was published, may be {@code null}
 */
public record EntityLink(String url, String title, LinkKind kind, LocalDate date) {

    /**
     * Compact constructor with validation; a blank title becomes {@code null}.
     *
     * @throws NullPointerException if {@code url} or {@code kind} is {@code null}
     * @throws IllegalArgumentException if {@code url} is blank
     */
    public EntityLink {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        url = url.strip();
        title = title == null || title.isBlank() ? null : title.strip();
    }
}
