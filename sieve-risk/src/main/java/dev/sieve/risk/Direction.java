package dev.sieve.risk;

import java.util.Locale;

/** Which way money moves from the point of view of the subject being assessed. */
public enum Direction {
    /** Money the subject receives. */
    IN,
    /** Money the subject sends. */
    OUT;

    /**
     * Parses a direction, ignoring case.
     *
     * @param value {@code in} or {@code out}
     * @return the direction
     * @throws IllegalArgumentException if the value is neither
     */
    public static Direction fromString(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "in", "credit" -> IN;
            case "out", "debit" -> OUT;
            default -> throw new IllegalArgumentException("Unknown direction: " + value);
        };
    }
}
