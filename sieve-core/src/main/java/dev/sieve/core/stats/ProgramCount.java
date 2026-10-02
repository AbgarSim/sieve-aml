package dev.sieve.core.stats;

import dev.sieve.core.model.ListSource;
import java.util.Objects;

/**
 * Number of entities designated under one sanctions program of one list.
 *
 * @param source the list that publishes the program
 * @param code the program code as published
 * @param name the program name, or {@code null} when the list publishes none
 * @param entities entities carrying the program
 */
public record ProgramCount(ListSource source, String code, String name, int entities) {

    public ProgramCount {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(code, "code must not be null");
    }
}
