package dev.sieve.core.media;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The candidate articles a news index returned for one name.
 *
 * <p>A report is kept apart from list screening on purpose. Its articles are unverified leads for
 * an analyst to read, never a match: no score, no entity, and nothing here changes a screening
 * result. An empty report with status {@link Status#OK} means the index found nothing for the
 * query, not that the name is clean; {@link Status#UNAVAILABLE} means the index could not be asked,
 * so nothing can be said either way.
 *
 * @param name the name that was searched
 * @param index the news index that answered, for example {@code GDELT}
 * @param indexQuery the query as sent to the index, so an analyst can repeat it
 * @param status whether the index answered
 * @param articles the candidate articles, newest first
 * @param searchedAt when the index was asked
 * @param detail why the index could not be asked, when {@code status} is {@code UNAVAILABLE}
 */
public record AdverseMediaReport(
        String name,
        String index,
        String indexQuery,
        Status status,
        List<MediaArticle> articles,
        Instant searchedAt,
        Optional<String> detail) {

    /** Whether the news index answered the query. */
    public enum Status {
        /** The index answered; the article list is what it returned. */
        OK,
        /** The index failed, timed out or refused the request; the article list is empty. */
        UNAVAILABLE
    }

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public AdverseMediaReport {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(index, "index must not be null");
        Objects.requireNonNull(indexQuery, "indexQuery must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(searchedAt, "searchedAt must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        articles = List.copyOf(Objects.requireNonNull(articles, "articles must not be null"));
    }
}
