package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;

/**
 * Inbound DTO for a screening request.
 *
 * @param name the name to screen against sanctions lists
 * @param entityType optional entity type filter (e.g., "INDIVIDUAL", "ENTITY")
 * @param sources optional set of list sources to screen against (e.g., ["OFAC_SDN"])
 * @param threshold minimum match score (0.0–1.0); if {@code null}, the server default is used
 * @param dateOfBirth optional date of birth: {@code YYYY}, {@code YYYY-MM} or {@code YYYY-MM-DD}
 * @param placeOfBirth optional place of birth
 * @param country optional country of nationality, citizenship, residence or registration
 * @param address optional address
 * @param identifiers optional identifier values (passport, national id, tax id, LEI, registration
 *     number)
 * @param gender optional gender: {@code male}, {@code female} or {@code other}
 */
@Schema(description = "Request to screen a name against sanctions lists")
public record ScreeningRequestDto(
        @NotBlank(message = "Name must not be blank")
                @Schema(description = "Name to screen", example = "John Doe")
                String name,
        @Schema(description = "Entity type filter", example = "INDIVIDUAL", nullable = true)
                String entityType,
        @Schema(
                        description = "List sources to screen against",
                        example = "[\"OFAC_SDN\"]",
                        nullable = true)
                Set<String> sources,
        @DecimalMin(value = "0.0", message = "Threshold must be >= 0.0")
                @DecimalMax(value = "1.0", message = "Threshold must be <= 1.0")
                @Schema(
                        description = "Minimum match score threshold (0.0-1.0)",
                        example = "0.80",
                        nullable = true)
                Double threshold,
        @Schema(
                        description =
                                "Date of birth: YYYY, YYYY-MM or YYYY-MM-DD. Weighed into the"
                                        + " score: agreement raises it, a different date lowers"
                                        + " it",
                        example = "1970-05-12",
                        nullable = true)
                String dateOfBirth,
        @Schema(description = "Place of birth", example = "Leningrad", nullable = true)
                String placeOfBirth,
        @Schema(
                        description =
                                "Country of nationality, citizenship, residence or registration,"
                                        + " as a name or ISO code",
                        example = "RU",
                        nullable = true)
                String country,
        @Schema(description = "Address, free text", example = "Moscow", nullable = true)
                String address,
        @Schema(
                        description =
                                "Identifier values of any type: passport, national id, tax id,"
                                        + " LEI, registration number",
                        example = "[\"AB123456\"]",
                        nullable = true)
                List<String> identifiers,
        @Schema(description = "Gender: male, female or other", example = "male", nullable = true)
                String gender) {

    /**
     * A name-only request.
     *
     * @param name the name
     * @param entityType entity type filter
     * @param sources list sources
     * @param threshold threshold
     */
    public ScreeningRequestDto(
            String name, String entityType, Set<String> sources, Double threshold) {
        this(name, entityType, sources, threshold, null, null, null, null, null, null);
    }
}
