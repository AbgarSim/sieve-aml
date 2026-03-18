package dev.sieve.core.model;

/**
 * A physical address associated with a sanctioned entity.
 *
 * <p>Fields are populated on a best-effort basis from the source list. The {@link #fullAddress}
 * field contains the raw, unparsed address string when structured components are not available.
 *
 * @param street street address line
 * @param city city or locality
 * @param stateOrProvince state, province, or region
 * @param postalCode postal or ZIP code
 * @param country ISO 3166-1 alpha-2 country code (e.g., "US", "GB")
 * @param fullAddress raw unparsed address string from the source
 */
public record Address(
        String street,
        String city,
        String stateOrProvince,
        String postalCode,
        String country,
        String fullAddress) {}
