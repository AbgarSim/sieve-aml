package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for a single provider's ingestion result.
 *
 * @param status outcome status (SUCCESS, FAILED, SKIPPED)
 * @param entityCount number of entities loaded
 * @param durationMs time taken in milliseconds
 * @param error error message if failed, {@code null} otherwise
 */
@Schema(description = "Result of a single list provider ingestion")
public record ProviderResultDto(
        @Schema(description = "Outcome status", example = "SUCCESS") String status,
        @Schema(description = "Entities loaded", example = "12543") int entityCount,
        @Schema(description = "Duration in milliseconds", example = "3200") long durationMs,
        @Schema(description = "Error message if failed", nullable = true) String error) {}
