package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO for a sanctioned entity.
 *
 * @param id source-specific entity ID
 * @param entityType type classification (INDIVIDUAL, ENTITY, VESSEL, AIRCRAFT)
 * @param listSource originating sanctions list
 * @param primaryName the entity's primary name
 * @param aliases list of alternative names
 * @param nationalities known nationalities
 * @param programs sanctions programs the entity is listed under
 * @param remarks free-text remarks
 * @param lastUpdated last modification timestamp
 */
@Schema(description = "Sanctioned entity from a sanctions list")
public record EntityDto(
        @Schema(description = "Source-specific entity ID", example = "12345") String id,
        @Schema(description = "Entity type", example = "INDIVIDUAL") String entityType,
        @Schema(description = "Originating sanctions list", example = "OFAC_SDN") String listSource,
        @Schema(description = "Primary name", example = "DOE, John") String primaryName,
        @Schema(description = "Alternative names / aliases") List<String> aliases,
        @Schema(description = "Known addresses") List<AddressDto> addresses,
        @Schema(description = "Known nationalities") List<String> nationalities,
        @Schema(description = "Sanctions programs") List<String> programs,
        @Schema(description = "Remarks") String remarks,
        @Schema(description = "Last updated timestamp") Instant lastUpdated) {}
