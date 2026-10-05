package dev.sieve.core.media;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A news article that a news index returned for a name. It is a candidate only: the index matched
 * the name as text, so the article may be about someone else, and nobody has read it.
 *
 * @param url link to the article
 * @param title the article headline as the index gives it
 * @param domain the publishing site, for example {@code reuters.com}
 * @param language the article language as the index names it, for example {@code English}
 * @param sourceCountry the country the index assigns to the publisher, if any
 * @param seenAt when the index first saw the article
 * @param adverseTerms what makes the article adverse, in plain words: the adverse terms found in
 *     the headline, or the crime and corruption themes the index tagged it with; may be empty when
 *     the index matched the terms in the body text only
 * @param mentionedAs the name in the article that was taken for the searched name, as the index
 *     spells it; empty when the index matched the searched name as text
 */
public record MediaArticle(
        String url,
        String title,
        String domain,
        String language,
        Optional<String> sourceCountry,
        Instant seenAt,
        List<String> adverseTerms,
        Optional<String> mentionedAs) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public MediaArticle {
        Objects.requireNonNull(url, "url must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(domain, "domain must not be null");
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(sourceCountry, "sourceCountry must not be null");
        Objects.requireNonNull(seenAt, "seenAt must not be null");
        adverseTerms =
                List.copyOf(Objects.requireNonNull(adverseTerms, "adverseTerms must not be null"));
        Objects.requireNonNull(mentionedAs, "mentionedAs must not be null");
    }

    /**
     * Returns a copy of this article that records which name in it was taken for the searched one.
     *
     * @param name the name as the index spells it
     * @return the copy
     */
    public MediaArticle mentionedAs(String name) {
        return new MediaArticle(
                url,
                title,
                domain,
                language,
                sourceCountry,
                seenAt,
                adverseTerms,
                Optional.of(name));
    }
}
