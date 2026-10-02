package dev.sieve.core.stats;

/**
 * How many entities of a group carry each optional field. Counts, not percentages, so groups can be
 * summed and percentages computed by the reader.
 *
 * @param total entities in the group
 * @param withDateOfBirth entities with at least one date of birth
 * @param withNationality entities with at least one nationality or citizenship
 * @param withAddress entities with at least one address
 * @param withIdentifiers entities with at least one identifier
 * @param withAliases entities with at least one alias
 * @param withProgram entities with at least one sanctions program
 * @param withListedDate entities with a listing date
 */
public record Completeness(
        int total,
        int withDateOfBirth,
        int withNationality,
        int withAddress,
        int withIdentifiers,
        int withAliases,
        int withProgram,
        int withListedDate) {}
