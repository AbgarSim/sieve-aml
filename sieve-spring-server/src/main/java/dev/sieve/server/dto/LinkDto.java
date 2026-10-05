package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * Outbound DTO for a page about an entity.
 *
 * @param url the page
 * @param title what the page is
 * @param kind SOURCE_PAGE, LEGAL_ACT, ENCYCLOPEDIA, WEBSITE or NEWS
 * @param date when the page was published, {@code null} if unknown
 */
@Schema(description = "A page about the entity, such as its listing page or a legal act")
public record LinkDto(
        @Schema(description = "The page") String url,
        @Schema(description = "What the page is", example = "Council Regulation 2014/269 (OJ L78)")
                String title,
        @Schema(description = "Kind of page", example = "LEGAL_ACT") String kind,
        @Schema(description = "When the page was published") LocalDate date) {}
