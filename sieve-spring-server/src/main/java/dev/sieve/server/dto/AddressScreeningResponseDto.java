package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO for an address screening response.
 *
 * @param query the original address query
 * @param totalMatches total number of matching results
 * @param screenedAt timestamp of the screening
 * @param results the list of address match results
 */
@Schema(description = "Address screening response containing matched entities")
public record AddressScreeningResponseDto(
        @Schema(description = "Original address query") String query,
        @Schema(description = "Total number of matches") int totalMatches,
        @Schema(description = "Timestamp of the screening") Instant screenedAt,
        @Schema(description = "Address match results sorted by score descending")
                List<AddressMatchResultDto> results) {}
