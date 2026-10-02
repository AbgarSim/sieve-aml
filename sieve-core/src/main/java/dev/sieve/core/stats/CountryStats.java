package dev.sieve.core.stats;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import java.util.Map;

/**
 * Entities linked to one country.
 *
 * <p>An entity is linked to a country by nationality or citizenship, by address, or both. {@code
 * entities} counts each entity once per country whatever the basis; the per-basis counts let a
 * reader show either view on its own.
 *
 * @param entities entities linked by nationality, citizenship or address
 * @param byNationality entities linked by nationality or citizenship
 * @param byAddress entities linked by address
 * @param bySource {@code entities} split by list
 * @param byType {@code entities} split by entity type
 */
public record CountryStats(
        int entities,
        int byNationality,
        int byAddress,
        Map<ListSource, Integer> bySource,
        Map<EntityType, Integer> byType) {

    public CountryStats {
        bySource = bySource == null ? Map.of() : Map.copyOf(bySource);
        byType = byType == null ? Map.of() : Map.copyOf(byType);
    }
}
