package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for what a list says about a vessel.
 *
 * @param flag the flag state as the list writes it
 * @param type the vessel type
 * @param callSign the radio call sign
 * @param tonnage the tonnage the list gives without naming the measure
 * @param grossRegisteredTonnage the gross registered tonnage
 */
@Schema(description = "What the list says about a vessel")
public record VesselDto(
        @Schema(description = "Flag state as the list writes it", example = "Panama") String flag,
        @Schema(description = "Vessel type", example = "Bulk Carrier") String type,
        @Schema(description = "Radio call sign", example = "3EXY9") String callSign,
        @Schema(description = "Tonnage, measure unstated", example = "48000") Integer tonnage,
        @Schema(description = "Gross registered tonnage", example = "52000")
                Integer grossRegisteredTonnage) {}
