package dev.sieve.address;

import java.util.Objects;

/**
 * A single labeled component extracted from a parsed address.
 *
 * <p>Labels follow the libpostal convention: {@code house}, {@code house_number}, {@code road},
 * {@code unit}, {@code level}, {@code postcode}, {@code suburb}, {@code city_district}, {@code
 * city}, {@code state_district}, {@code state}, {@code country_region}, {@code country}, {@code
 * world_region}, among others.
 *
 * @param label the component label (e.g., "road", "city", "country")
 * @param value the component value (e.g., "Main Street", "London", "United Kingdom")
 */
public record AddressComponent(String label, String value) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if either parameter is {@code null}
     */
    public AddressComponent {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(value, "value must not be null");
    }
}
