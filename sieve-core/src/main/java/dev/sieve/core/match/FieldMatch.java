package dev.sieve.core.match;

import java.util.Objects;

/**
 * How one screened fact compared with the matched record.
 *
 * @param field the fact compared: {@code dateOfBirth}, {@code placeOfBirth}, {@code country},
 *     {@code address}, {@code identifier} or {@code gender}
 * @param outcome how it compared
 * @param recordValue the record's value that decided the outcome, or the record's values when they
 *     disagree; empty when the record has none
 */
public record FieldMatch(String field, Outcome outcome, String recordValue) {

    /** How a screened fact compared with the record. */
    public enum Outcome {
        /** The record states the same value. */
        MATCH,
        /**
         * The record states a close value: same year only, off by a year, or day and month swapped.
         */
        PARTIAL,
        /** The record states a different value. */
        MISMATCH,
        /** The record does not state this fact; the score is unchanged. */
        NOT_ON_RECORD
    }

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public FieldMatch {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(recordValue, "recordValue must not be null");
    }
}
