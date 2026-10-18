package dev.sieve.core.match;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A date that may be known only to the year or the month, such as a date of birth given as "1970"
 * or "1970-05".
 *
 * @param year the year
 * @param month the month (1-12), or empty when only the year is known
 * @param day the day of the month, or empty when only the year or month is known
 */
public record PartialDate(int year, Optional<Integer> month, Optional<Integer> day) {

    private static final Pattern ISO = Pattern.compile("(\\d{4})(?:-(\\d{1,2})(?:-(\\d{1,2}))?)?");
    private static final Pattern DAY_FIRST =
            Pattern.compile("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})");

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code month} or {@code day} is {@code null}
     * @throws IllegalArgumentException if the parts do not form a date, or a day is given without a
     *     month
     */
    public PartialDate {
        Objects.requireNonNull(month, "month must not be null; use Optional.empty()");
        Objects.requireNonNull(day, "day must not be null; use Optional.empty()");
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("Year out of range: " + year);
        }
        if (day.isPresent() && month.isEmpty()) {
            throw new IllegalArgumentException("A day needs a month");
        }
        try {
            LocalDate.of(year, month.orElse(1), day.orElse(1));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Not a date: " + e.getMessage(), e);
        }
    }

    /**
     * Parses {@code YYYY}, {@code YYYY-MM}, {@code YYYY-MM-DD}, {@code DD.MM.YYYY} or {@code
     * DD/MM/YYYY}.
     *
     * @param text the text to parse
     * @return the date
     * @throws IllegalArgumentException if the text is not one of those forms
     */
    public static PartialDate parse(String text) {
        Objects.requireNonNull(text, "text must not be null");
        String value = text.strip();
        Matcher iso = ISO.matcher(value);
        if (iso.matches()) {
            return new PartialDate(
                    Integer.parseInt(iso.group(1)),
                    optionalInt(iso.group(2)),
                    optionalInt(iso.group(3)));
        }
        Matcher dayFirst = DAY_FIRST.matcher(value);
        if (dayFirst.matches()) {
            return new PartialDate(
                    Integer.parseInt(dayFirst.group(3)),
                    optionalInt(dayFirst.group(2)),
                    optionalInt(dayFirst.group(1)));
        }
        throw new IllegalArgumentException(
                "Unrecognised date '" + text + "'; use YYYY, YYYY-MM or YYYY-MM-DD");
    }

    /**
     * A full date.
     *
     * @param date the date
     * @return the date with year, month and day known
     */
    public static PartialDate of(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");
        return new PartialDate(
                date.getYear(),
                Optional.of(date.getMonthValue()),
                Optional.of(date.getDayOfMonth()));
    }

    /** Whether year, month and day are all known. */
    public boolean isComplete() {
        return day.isPresent();
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(String.format("%04d", year));
        month.ifPresent(m -> text.append(String.format("-%02d", m)));
        day.ifPresent(d -> text.append(String.format("-%02d", d)));
        return text.toString();
    }

    private static Optional<Integer> optionalInt(String group) {
        return group == null ? Optional.empty() : Optional.of(Integer.parseInt(group));
    }
}
