package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;

/**
 * Inbound DTO for a screening request.
 *
 * @param name the name to screen against sanctions lists
 * @param entityType optional entity type filter (e.g., "INDIVIDUAL", "ENTITY")
 * @param sources optional set of list sources to screen against (e.g., ["OFAC_SDN"])
 * @param threshold minimum match score (0.0–1.0); if {@code null}, the server default is used
 */
@Schema(description = "Request to screen a name against sanctions lists")
public record ScreeningRequestDto(
        @NotBlank(message = "Name must not be blank")
                @Schema(description = "Name to screen", example = "John Doe")
                String name,
        @Schema(
                        description = "Entity type filter",
                        example = "INDIVIDUAL",
                        nullable = true)
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
                Double threshold) {}
