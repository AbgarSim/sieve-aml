package dev.sieve.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for one prominent public function as a jurisdiction lists it.
 *
 * @param category the point of Article 3(9) of Directive (EU) 2015/849 the list files it under,
 *     {@code a} to {@code h}, or {@code null} when the list does not say
 * @param heading the heading it appears under in the list, or {@code null} when it has none
 * @param function the function as the list words it
 * @param organisation the international organisation the function belongs to, or {@code null} for a
 *     national function
 */
@Schema(description = "One prominent public function as a jurisdiction lists it")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicFunctionDto(
        @Schema(description = "Point of Article 3(9) of Directive (EU) 2015/849", example = "a")
                String category,
        @Schema(description = "Heading the function appears under", example = "Head of Government")
                String heading,
        @Schema(description = "The function", example = "Federal Chancellor (Bundeskanzler)")
                String function,
        @Schema(description = "International organisation the function belongs to")
                String organisation) {}
