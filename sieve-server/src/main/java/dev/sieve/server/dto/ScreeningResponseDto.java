package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO for a screening response.
 *
 * @param query the original query name
 * @param totalMatches total number of matching results
 * @param screenedAt timestamp of the screening
 * @param results the list of match results
 */
@Schema(description = "Screening response containing matched sanctioned entities")
public record ScreeningResponseDto(
        @Schema(description = "Original query name") String query,
        @Schema(description = "Total number of matches") int totalMatches,
        @Schema(description = "Timestamp of the screening") Instant screenedAt,
        @Schema(description = "Match results sorted by score descending")
                List<MatchResultDto> results) {}
