package dev.sieve.core.stats;

import dev.sieve.core.model.EntityType;
import java.util.List;
import java.util.Map;

/**
 * Aggregates for the entities of one list.
 *
 * @param entities entities on the list
 * @param names primary names plus aliases
 * @param byType entities by entity type
 * @param countries distinct countries the entities are linked to
 * @param completeness how many entities carry each optional field
 * @param topPrograms the list's programs with the most entities, largest first
 */
public record SourceStats(
        int entities,
        int names,
        Map<EntityType, Integer> byType,
        int countries,
        Completeness completeness,
        List<ProgramCount> topPrograms) {

    public SourceStats {
        byType = byType == null ? Map.of() : Map.copyOf(byType);
        topPrograms = topPrograms == null ? List.of() : List.copyOf(topPrograms);
    }
}
