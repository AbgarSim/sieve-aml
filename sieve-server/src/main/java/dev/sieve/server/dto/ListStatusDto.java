package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Outbound DTO for the status of a single sanctions list source.
 *
 * @param source the list source identifier
 * @param entityCount number of entities loaded from this source
 * @param lastFetched when the source was last successfully fetched
 * @param status current loading status
 */
@Schema(description = "Status of a sanctions list source")
public record ListStatusDto(
        @Schema(description = "List source identifier", example = "OFAC_SDN") String source,
        @Schema(description = "Number of entities loaded", example = "12543") int entityCount,
        @Schema(description = "Last successful fetch timestamp") Instant lastFetched,
        @Schema(description = "Current status", example = "LOADED") String status) {}
