package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * Outbound DTO for index statistics.
 *
 * @param totalEntities total number of entities in the index
 * @param countBySource entity count broken down by list source
 * @param countByType entity count broken down by entity type
 * @param lastUpdated when the index was last modified
 */
@Schema(description = "Index statistics")
public record IndexStatsDto(
        @Schema(description = "Total entities in the index", example = "12543") int totalEntities,
        @Schema(description = "Entity count by list source") Map<String, Integer> countBySource,
        @Schema(description = "Entity count by entity type") Map<String, Integer> countByType,
        @Schema(description = "Last index update timestamp") Instant lastUpdated) {}
