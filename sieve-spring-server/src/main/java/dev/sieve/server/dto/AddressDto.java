package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Outbound DTO for an address associated with a sanctioned entity.
 *
 * @param street street address line
 * @param city city or locality
 * @param stateOrProvince state, province, or region
 * @param postalCode postal or ZIP code
 * @param country ISO 3166-1 country code or name
 * @param fullAddress raw unparsed address string from the source
 */
@Schema(description = "Physical address associated with a sanctioned entity")
public record AddressDto(
        @Schema(description = "Street address", example = "123 Main Street") String street,
        @Schema(description = "City or locality", example = "London") String city,
        @Schema(description = "State, province, or region", example = "England")
                String stateOrProvince,
        @Schema(description = "Postal or ZIP code", example = "SW1A 1AA") String postalCode,
        @Schema(description = "Country", example = "GB") String country,
        @Schema(description = "Raw unparsed address string") String fullAddress) {}
