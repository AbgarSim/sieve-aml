package dev.sieve.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Outbound DTO for a single match result.
 *
 * @param entity the matched sanctioned entity
 * @param score match confidence score (0.0–1.0)
 * @param matchedField which field produced the match
 * @param matchAlgorithm the algorithm that produced the match
 * @param nameScore the score of the name alone; present only when the request gave facts beyond the
 *     name
 * @param evidence how each fact beyond the name compared with the record; present only when the
 *     request gave such facts
 */
@Schema(description = "A single match result from screening")
public record MatchResultDto(
        @Schema(description = "Matched sanctioned entity") EntityDto entity,
        @Schema(description = "Match confidence score (0.0-1.0)", example = "0.92") double score,
        @Schema(description = "Field that produced the match", example = "primaryName")
                String matchedField,
        @Schema(description = "Algorithm that produced the match", example = "JARO_WINKLER")
                String matchAlgorithm,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(
                        description =
                                "Score of the name alone, before date of birth, country and other"
                                        + " facts were weighed; only when such facts were given",
                        example = "0.88",
                        nullable = true)
                Double nameScore,
        @JsonInclude(JsonInclude.Include.NON_NULL)
                @Schema(
                        description =
                                "How each fact beyond the name compared with the record; only"
                                        + " when such facts were given",
                        nullable = true)
                List<FieldMatchDto> evidence) {}
