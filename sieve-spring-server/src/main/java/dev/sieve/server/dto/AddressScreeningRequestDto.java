package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

/**
 * Inbound DTO for an address screening request.
 *
 * @param address the free-text address to screen (NLP-friendly, parsed by libpostal)
 * @param threshold minimum match score (0.0–1.0); if {@code null}, the server default is used
 */
@Schema(description = "Request to screen an address against sanctions entity addresses")
public record AddressScreeningRequestDto(
        @NotBlank(message = "Address must not be blank")
                @Schema(
                        description = "Free-text address to screen",
                        example = "123 Main Street, London, United Kingdom")
                String address,
        @DecimalMin(value = "0.0", message = "Threshold must be >= 0.0")
                @DecimalMax(value = "1.0", message = "Threshold must be <= 1.0")
                @Schema(
                        description = "Minimum match score threshold (0.0-1.0)",
                        example = "0.80",
                        nullable = true)
                Double threshold) {}
