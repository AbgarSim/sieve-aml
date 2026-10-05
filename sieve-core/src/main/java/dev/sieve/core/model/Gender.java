package dev.sieve.core.model;

import java.util.Locale;
import java.util.Optional;

/** The gender a list records for a person. */
public enum Gender {
    MALE,
    FEMALE,
    /** A gender the list records that is neither male nor female. */
    OTHER;

    /**
     * Reads the gender a list writes in its own words: {@code M}, {@code Male}, {@code F}, {@code
     * Female}, {@code Other}, {@code X}. Blank and {@code Unknown} values give an empty result.
     *
     * @param value the list's value, may be {@code null}
     * @return the gender, empty when the value does not state one
     */
    public static Optional<Gender> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "m", "male", "man" -> Optional.of(MALE);
            case "f", "female", "woman" -> Optional.of(FEMALE);
            case "o", "x", "other", "non-binary", "nonbinary" -> Optional.of(OTHER);
            default -> Optional.empty();
        };
    }
}
