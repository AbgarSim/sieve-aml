package dev.sieve.core.media;

/**
 * Looks up candidate adverse media articles for a name in a news index.
 *
 * <p>This is separate from {@link dev.sieve.core.match.MatchEngine} and the entity index: articles
 * are never stored as entities and never produce a match result.
 */
public interface AdverseMediaSearch {

    /**
     * Searches the news index for articles that mention the name together with adverse terms.
     *
     * <p>Implementations do not throw on network or index failures; they return a report with
     * status {@link AdverseMediaReport.Status#UNAVAILABLE} instead.
     *
     * @param query the query
     * @return the report, never {@code null}
     */
    AdverseMediaReport search(AdverseMediaQuery query);
}
