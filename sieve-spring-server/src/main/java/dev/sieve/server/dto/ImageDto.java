package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for a picture of an entity, which must be shown with its credit and licence.
 *
 * @param url the full-size picture
 * @param thumbnailUrl a small version, {@code null} when the publisher offers none
 * @param pageUrl the page that publishes the picture with its terms
 * @param credit who to credit
 * @param licence the licence the picture is published under, {@code null} when none is stated
 */
@Schema(description = "A picture of the entity, shown with its credit and licence")
public record ImageDto(
        @Schema(description = "Full-size picture") String url,
        @Schema(description = "Small version of the picture") String thumbnailUrl,
        @Schema(description = "Page that publishes the picture with its terms") String pageUrl,
        @Schema(description = "Who to credit", example = "FBI") String credit,
        @Schema(description = "Licence the picture is published under", example = "CC BY-SA 4.0")
                String licence) {}
