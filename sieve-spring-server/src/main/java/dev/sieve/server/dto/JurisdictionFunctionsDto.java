package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Outbound DTO for the prominent public functions one jurisdiction lists.
 *
 * @param jurisdiction the ISO 3166-1 alpha-2 code of the member state, or {@code EU}
 * @param reference the Official Journal number the list is read from
 * @param count the number of functions returned
 * @param functions the functions, in list order
 */
@Schema(description = "The prominent public functions a jurisdiction lists")
public record JurisdictionFunctionsDto(
        @Schema(description = "ISO 3166-1 alpha-2 code, or EU", example = "DE") String jurisdiction,
        @Schema(description = "Official Journal number", example = "C/2023/724") String reference,
        @Schema(description = "Number of functions returned", example = "27") int count,
        @Schema(description = "The functions, in list order") List<PublicFunctionDto> functions) {}
