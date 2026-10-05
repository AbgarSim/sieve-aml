package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * Outbound DTO summarising the EU list of prominent public functions.
 *
 * @param title the Official Journal document's title
 * @param reference the Official Journal number
 * @param published the publication date
 * @param eli the document's European Legislation Identifier
 * @param total the number of functions in the catalogue
 * @param jurisdictions the number of functions each jurisdiction lists
 */
@Schema(description = "Summary of the EU list of prominent public functions")
public record PublicFunctionsSummaryDto(
        @Schema(description = "Official Journal document title") String title,
        @Schema(description = "Official Journal number", example = "C/2023/724") String reference,
        @Schema(description = "Publication date", example = "2023-11-10") LocalDate published,
        @Schema(
                        description = "European Legislation Identifier",
                        example = "http://data.europa.eu/eli/C/2023/724/oj")
                String eli,
        @Schema(description = "Number of functions in the catalogue", example = "2161") int total,
        @Schema(description = "Number of functions each jurisdiction lists")
                List<JurisdictionFunctionCountDto> jurisdictions) {}
