package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for the number of prominent public functions one jurisdiction lists.
 *
 * @param jurisdiction the ISO 3166-1 alpha-2 code of the member state, or {@code EU}
 * @param count the number of functions it lists
 */
@Schema(description = "Number of prominent public functions a jurisdiction lists")
public record JurisdictionFunctionCountDto(
        @Schema(description = "ISO 3166-1 alpha-2 code, or EU", example = "DE") String jurisdiction,
        @Schema(description = "Number of functions listed", example = "27") int count) {}
