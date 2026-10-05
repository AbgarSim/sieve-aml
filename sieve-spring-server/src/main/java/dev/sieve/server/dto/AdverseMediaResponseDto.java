package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Candidate adverse media for one name. Experimental, and never a screening match: the articles
 * mention the name as text and have not been read or confirmed to be about the subject.
 *
 * @param name the name searched
 * @param index the news index that was asked
 * @param indexQuery the query sent to the index
 * @param status {@code OK} when the index answered, {@code UNAVAILABLE} when it could not be asked
 * @param detail why the index could not be asked
 * @param totalArticles number of articles returned
 * @param articles the candidate articles, newest first
 * @param searchedAt when the index was asked
 * @param notice how the result may be used
 */
@Schema(description = "Candidate adverse media for a name (experimental, never a match)")
public record AdverseMediaResponseDto(
        @Schema(description = "Name searched") String name,
        @Schema(description = "News index asked", example = "GDELT") String index,
        @Schema(description = "Query sent to the index") String indexQuery,
        @Schema(description = "OK or UNAVAILABLE", example = "OK") String status,
        @Schema(description = "Why the index could not be asked") String detail,
        @Schema(description = "Number of articles returned") int totalArticles,
        @Schema(description = "Candidate articles, newest first") List<ArticleDto> articles,
        @Schema(description = "When the index was asked") Instant searchedAt,
        @Schema(description = "How the result may be used") String notice) {

    /** The fixed notice sent with every response. */
    public static final String NOTICE =
            "Experimental. Candidate articles that mention the name, not a match: they may be about"
                    + " someone else and must be read and confirmed by an analyst. An empty list"
                    + " does not clear the name.";

    /**
     * One candidate article.
     *
     * @param url link to the article
     * @param title headline
     * @param domain publishing site
     * @param language article language
     * @param sourceCountry publisher country, may be {@code null}
     * @param seenAt when the index first saw the article
     * @param adverseTerms what makes the article adverse: terms in the headline or index themes
     * @param mentionedAs the name in the article taken for the searched name, may be {@code null}
     */
    @Schema(description = "Candidate article")
    public record ArticleDto(
            String url,
            String title,
            String domain,
            String language,
            String sourceCountry,
            Instant seenAt,
            List<String> adverseTerms,
            String mentionedAs) {}
}
