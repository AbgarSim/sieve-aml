package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for the health check endpoint.
 *
 * @param status overall application status
 * @param index index statistics
 */
@Schema(description = "Health check response")
public record HealthResponseDto(
        @Schema(description = "Application status", example = "UP") String status,
        @Schema(description = "Index statistics") IndexStatsDto index) {}
