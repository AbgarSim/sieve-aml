package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request for candidate adverse media about a name.
 *
 * @param name the person or organisation name
 * @param lookbackDays how many days back to search, defaults to the server setting
 * @param maxArticles the most articles to return, defaults to the server setting
 */
@Schema(description = "Adverse media request")
public record AdverseMediaRequestDto(
        @NotBlank @Schema(description = "Name to search for", example = "Viktor Bout") String name,
        @Min(1) @Max(90) @Schema(description = "Days back to search (1-90)", example = "90")
                Integer lookbackDays,
        @Min(1) @Max(250) @Schema(description = "Most articles to return (1-250)", example = "25")
                Integer maxArticles) {}
