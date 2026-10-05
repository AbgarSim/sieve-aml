package dev.sieve.core.media;

import java.util.List;
import java.util.Objects;

/**
 * An adverse news article together with the people and organisations the news index found in it.
 *
 * <p>The names are machine-extracted by the index and may be misspelt, cut short or wrong; the
 * article being adverse says nothing about which of the names it is adverse for.
 *
 * @param article the article
 * @param persons person names the index extracted, as it spells them
 * @param organisations organisation names the index extracted, as it spells them
 */
public record NewsMention(MediaArticle article, List<String> persons, List<String> organisations) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public NewsMention {
        Objects.requireNonNull(article, "article must not be null");
        persons = List.copyOf(Objects.requireNonNull(persons, "persons must not be null"));
        organisations =
                List.copyOf(
                        Objects.requireNonNull(organisations, "organisations must not be null"));
    }
}
