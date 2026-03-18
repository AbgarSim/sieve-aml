package dev.sieve.address;

import java.util.List;
import java.util.Objects;

/**
 * A structured representation of an address parsed by libpostal.
 *
 * <p>Provides both the raw list of {@link AddressComponent}s and convenience accessors for the most
 * commonly used fields. Any accessor may return {@code null} if the component was not identified in
 * the source address.
 *
 * @param components the labeled components extracted from the address
 */
public record ParsedAddress(List<AddressComponent> components) {

    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException if {@code components} is {@code null}
     */
    public ParsedAddress {
        Objects.requireNonNull(components, "components must not be null");
        components = List.copyOf(components);
    }

    /**
     * Returns the first component value matching the given label, or {@code null} if not found.
     *
     * @param label the component label to look up
     * @return the value, or {@code null}
     */
    public String component(String label) {
        return components.stream()
                .filter(c -> c.label().equals(label))
                .map(AddressComponent::value)
                .findFirst()
                .orElse(null);
    }

    /** Returns the house/building name, or {@code null}. */
    public String house() {
        return component("house");
    }

    /** Returns the house number, or {@code null}. */
    public String houseNumber() {
        return component("house_number");
    }

    /** Returns the road/street name, or {@code null}. */
    public String road() {
        return component("road");
    }

    /** Returns the unit/apartment number, or {@code null}. */
    public String unit() {
        return component("unit");
    }

    /** Returns the postal/ZIP code, or {@code null}. */
    public String postcode() {
        return component("postcode");
    }

    /** Returns the suburb/neighbourhood, or {@code null}. */
    public String suburb() {
        return component("suburb");
    }

    /** Returns the city, or {@code null}. */
    public String city() {
        return component("city");
    }

    /** Returns the state/province, or {@code null}. */
    public String state() {
        return component("state");
    }

    /** Returns the country, or {@code null}. */
    public String country() {
        return component("country");
    }
}
