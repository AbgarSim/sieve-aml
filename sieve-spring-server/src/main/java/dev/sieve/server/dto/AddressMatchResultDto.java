package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for a single address match result.
 *
 * @param entity the matched sanctioned entity
 * @param score address match confidence score (0.0–1.0)
 * @param matchedAddress the specific entity address that produced the best match
 */
@Schema(description = "A single address match result from screening")
public record AddressMatchResultDto(
        @Schema(description = "Matched sanctioned entity") EntityDto entity,
        @Schema(description = "Address match confidence score (0.0-1.0)", example = "0.85")
                double score,
        @Schema(description = "The entity address that produced the match")
                AddressDto matchedAddress) {}
