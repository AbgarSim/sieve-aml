package dev.sieve.ingest.pep;

import java.util.Objects;

/**
 * One prominent public function as a jurisdiction lists it.
 *
 * @param jurisdiction the ISO 3166-1 alpha-2 code of the member state, or {@code EU} for the
 *     Union's institutions and bodies
 * @param category the directive category the list files the function under, or {@code null} when
 *     the list does not say
 * @param heading the heading the function appears under in the list, as the list words it, or
 *     {@code null} when it has none
 * @param function the function as the list words it
 * @param organisation the international organisation the function belongs to, or {@code null} for a
 *     national function
 */
public record PublicFunction(
        String jurisdiction,
        PublicFunctionCategory category,
        String heading,
        String function,
        String organisation) {

    public PublicFunction {
        Objects.requireNonNull(jurisdiction, "jurisdiction must not be null");
        Objects.requireNonNull(function, "function must not be null");
    }
}
