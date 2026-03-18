package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for a single match result.
 *
 * @param entity the matched sanctioned entity
 * @param score match confidence score (0.0–1.0)
 * @param matchedField which field produced the match
 * @param matchAlgorithm the algorithm that produced the match
 */
@Schema(description = "A single match result from screening")
public record MatchResultDto(
        @Schema(description = "Matched sanctioned entity") EntityDto entity,
        @Schema(description = "Match confidence score (0.0-1.0)", example = "0.92") double score,
        @Schema(description = "Field that produced the match", example = "primaryName")
                String matchedField,
        @Schema(description = "Algorithm that produced the match", example = "JARO_WINKLER")
                String matchAlgorithm) {}
