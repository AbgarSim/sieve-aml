package dev.sieve.core.match;

import dev.sieve.core.model.Gender;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Facts about the screened party beyond its name. Each one that is given is weighed against the
 * matched record: agreement raises the score, a clear disagreement lowers it, and a fact the record
 * does not carry changes nothing.
 *
 * @param dateOfBirth date of birth, possibly only the year or year and month
 * @param placeOfBirth place of birth, free text such as a city
 * @param country country of nationality, citizenship, residence or registration, as a name or ISO
 *     code
 * @param address address, free text
 * @param identifiers identifier values, such as passport, national id, tax id, LEI or registration
 *     numbers, of any type
 * @param gender gender
 */
public record ScreeningCriteria(
        Optional<PartialDate> dateOfBirth,
        Optional<String> placeOfBirth,
        Optional<String> country,
        Optional<String> address,
        List<String> identifiers,
        Optional<Gender> gender) {

    private static final ScreeningCriteria NONE =
            new ScreeningCriteria(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    Optional.empty());

    /**
     * Compact constructor with validation. Blank text and blank identifiers are dropped.
     *
     * @throws NullPointerException if any parameter is {@code null}
     */
    public ScreeningCriteria {
        Objects.requireNonNull(dateOfBirth, "dateOfBirth must not be null; use Optional.empty()");
        placeOfBirth = nonBlank(placeOfBirth, "placeOfBirth");
        country = nonBlank(country, "country");
        address = nonBlank(address, "address");
        Objects.requireNonNull(identifiers, "identifiers must not be null; use List.of()");
        identifiers =
                identifiers.stream()
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .toList();
        Objects.requireNonNull(gender, "gender must not be null; use Optional.empty()");
    }

    /**
     * Builds criteria from text as an API or command line receives it. Any argument may be {@code
     * null} or blank.
     *
     * @param dateOfBirth {@code YYYY}, {@code YYYY-MM}, {@code YYYY-MM-DD}, {@code DD.MM.YYYY} or
     *     {@code DD/MM/YYYY}
     * @param placeOfBirth place of birth
     * @param country country name or ISO code
     * @param address address
     * @param identifiers identifier values
     * @param gender {@code male}, {@code female} or {@code other} (or {@code m}, {@code f})
     * @return the criteria
     * @throws IllegalArgumentException if the date of birth or gender is not recognised
     */
    public static ScreeningCriteria parse(
            String dateOfBirth,
            String placeOfBirth,
            String country,
            String address,
            List<String> identifiers,
            String gender) {
        Optional<Gender> parsedGender = Optional.empty();
        if (gender != null && !gender.isBlank()) {
            parsedGender =
                    Optional.of(
                            Gender.parse(gender)
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Unrecognised gender '"
                                                                    + gender
                                                                    + "'; use male, female or"
                                                                    + " other")));
        }
        return new ScreeningCriteria(
                dateOfBirth == null || dateOfBirth.isBlank()
                        ? Optional.empty()
                        : Optional.of(PartialDate.parse(dateOfBirth)),
                Optional.ofNullable(placeOfBirth),
                Optional.ofNullable(country),
                Optional.ofNullable(address),
                identifiers == null ? List.of() : identifiers,
                parsedGender);
    }

    /** No facts beyond the name: the screening is by name only. */
    public static ScreeningCriteria none() {
        return NONE;
    }

    /** Whether no fact beyond the name is given. */
    public boolean isEmpty() {
        return dateOfBirth.isEmpty()
                && placeOfBirth.isEmpty()
                && country.isEmpty()
                && address.isEmpty()
                && identifiers.isEmpty()
                && gender.isEmpty();
    }

    private static Optional<String> nonBlank(Optional<String> value, String field) {
        Objects.requireNonNull(value, field + " must not be null; use Optional.empty()");
        return value.map(String::strip).filter(s -> !s.isEmpty());
    }
}
