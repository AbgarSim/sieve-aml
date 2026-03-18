package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Outbound DTO for an ingestion refresh response.
 *
 * @param totalEntitiesLoaded total entities loaded across all providers
 * @param totalDurationMs total ingestion duration in milliseconds
 * @param results per-provider outcome
 */
@Schema(description = "Ingestion refresh report")
public record RefreshResponseDto(
        @Schema(description = "Total entities loaded") int totalEntitiesLoaded,
        @Schema(description = "Total duration in milliseconds") long totalDurationMs,
        @Schema(description = "Per-provider results") Map<String, ProviderResultDto> results) {}
