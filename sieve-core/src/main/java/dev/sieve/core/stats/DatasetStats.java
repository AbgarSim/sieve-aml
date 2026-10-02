package dev.sieve.core.stats;

import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.ScriptType;
import java.util.List;
import java.util.Map;

/**
 * Aggregates over a whole set of sanctioned entities, as produced by {@link StatsAggregator}.
 *
 * @param totalEntities entities across all lists
 * @param totalNames primary names plus aliases across all lists
 * @param byType entities by entity type
 * @param bySource per-list aggregates; lists with no entities are absent
 * @param byCountry per-country aggregates keyed by ISO 3166-1 alpha-2 code
 * @param distinctPrograms distinct (list, program code) pairs
 * @param topPrograms programs with the most entities across all lists, largest first
 * @param identifiersByType identifiers by type
 * @param namesByScript names by script, counting only names whose script is known
 * @param unresolvedCountries country values that could not be mapped to a country code
 */
public record DatasetStats(
        int totalEntities,
        int totalNames,
        Map<EntityType, Integer> byType,
        Map<ListSource, SourceStats> bySource,
        Map<String, CountryStats> byCountry,
        int distinctPrograms,
        List<ProgramCount> topPrograms,
        Map<IdentifierType, Integer> identifiersByType,
        Map<ScriptType, Integer> namesByScript,
        UnresolvedCountries unresolvedCountries) {

    public DatasetStats {
        byType = byType == null ? Map.of() : Map.copyOf(byType);
        bySource = bySource == null ? Map.of() : Map.copyOf(bySource);
        byCountry = byCountry == null ? Map.of() : Map.copyOf(byCountry);
        topPrograms = topPrograms == null ? List.of() : List.copyOf(topPrograms);
        identifiersByType = identifiersByType == null ? Map.of() : Map.copyOf(identifiersByType);
        namesByScript = namesByScript == null ? Map.of() : Map.copyOf(namesByScript);
        unresolvedCountries =
                unresolvedCountries == null
                        ? new UnresolvedCountries(0, Map.of())
                        : unresolvedCountries;
    }
}
