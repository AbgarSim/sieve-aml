package dev.sieve.server.controller;

import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.server.config.SieveProperties;
import dev.sieve.server.dto.AdverseMediaRequestDto;
import dev.sieve.server.dto.AdverseMediaResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for experimental adverse media.
 *
 * <p>Kept apart from {@link ScreeningController} on purpose: articles never become screening
 * matches and screening never calls the news index.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Adverse media", description = "Candidate news articles about a name (experimental)")
public class AdverseMediaController {

    private final ObjectProvider<AdverseMediaSearch> search;
    private final SieveProperties properties;

    /**
     * Creates the controller.
     *
     * @param search the adverse media search, present only when the feature is enabled
     * @param properties the Sieve configuration properties
     */
    public AdverseMediaController(
            ObjectProvider<AdverseMediaSearch> search, SieveProperties properties) {
        this.search = search;
        this.properties = properties;
    }

    /**
     * Looks up candidate adverse media articles for a name.
     *
     * @param requestDto the request
     * @return the candidate articles, or 503 when adverse media is turned off
     */
    @PostMapping("/adverse-media")
    @Operation(
            summary = "Find candidate adverse media for a name (experimental)",
            description =
                    "Asks an open news index for recent articles that mention the name together"
                            + " with financial crime, corruption, sanctions or terrorism terms."
                            + " The articles are leads for an analyst, never a match, and do not"
                            + " affect screening. Off unless sieve.adverse-media.enabled is true.")
    @ApiResponse(responseCode = "200", description = "The index was asked; see status")
    @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    @ApiResponse(responseCode = "503", description = "Adverse media is turned off")
    public ResponseEntity<?> search(@Valid @RequestBody AdverseMediaRequestDto requestDto) {
        AdverseMediaSearch available = search.getIfAvailable();
        if (available == null) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
            problem.setType(URI.create("https://sieve.dev/errors/adverse-media-disabled"));
            problem.setTitle("Adverse Media Disabled");
            problem.setDetail("Adverse media is off. Set sieve.adverse-media.enabled=true.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
        }

        SieveProperties.AdverseMediaProperties settings = properties.adverseMedia();
        AdverseMediaQuery query =
                new AdverseMediaQuery(
                        requestDto.name(),
                        Duration.ofDays(
                                Optional.ofNullable(requestDto.lookbackDays())
                                        .orElse(settings.lookbackDays())),
                        Optional.ofNullable(requestDto.maxArticles())
                                .orElse(settings.maxArticles()));
        return ResponseEntity.ok(toDto(available.search(query)));
    }

    private static AdverseMediaResponseDto toDto(AdverseMediaReport report) {
        return new AdverseMediaResponseDto(
                report.name(),
                report.index(),
                report.indexQuery(),
                report.status().name(),
                report.detail().orElse(null),
                report.articles().size(),
                report.articles().stream().map(AdverseMediaController::toDto).toList(),
                report.searchedAt(),
                AdverseMediaResponseDto.NOTICE);
    }

    private static AdverseMediaResponseDto.ArticleDto toDto(MediaArticle article) {
        return new AdverseMediaResponseDto.ArticleDto(
                article.url(),
                article.title(),
                article.domain(),
                article.language(),
                article.sourceCountry().orElse(null),
                article.seenAt(),
                article.adverseTerms(),
                article.mentionedAs().orElse(null));
    }
}
