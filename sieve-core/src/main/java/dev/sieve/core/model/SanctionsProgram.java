package dev.sieve.core.model;

import java.util.Objects;

/**
 * A sanctions program or regime under which an entity is listed.
 *
 * <p>For example, OFAC maintains programs such as "SDGT" (Specially Designated Global Terrorist),
 * "IRAN", and "CYBER2". EU and UN have their own regime identifiers.
 *
 * @param code the short program code (e.g., "SDGT", "IRAN")
 * @param name the full program name, may be {@code null} if not provided by the source
 * @param source the sanctions list that defines this program
 */
public record SanctionsProgram(String code, String name, ListSource source) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code code} or {@code source} is {@code null}
     */
    public SanctionsProgram {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
