package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO for a batch screening response.
 *
 * @param totalRequests total number of requests processed
 * @param screenedAt timestamp of the batch screening
 * @param results individual screening results for each request
 */
@Schema(description = "Batch screening response containing results for each request")
public record BatchScreeningResponseDto(
        @Schema(description = "Total number of requests processed") int totalRequests,
        @Schema(description = "Timestamp of the batch screening") Instant screenedAt,
        @Schema(description = "Individual screening results") List<ScreeningResponseDto> results) {}
