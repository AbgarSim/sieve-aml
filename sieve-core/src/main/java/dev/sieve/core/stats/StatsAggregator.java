package dev.sieve.core.stats;

import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Computes {@link DatasetStats} over a collection of sanctioned entities.
 *
 * <p>The aggregator is a pure function of its input: it holds no state between calls and is safe to
 * share across threads. Both the snapshot command and the servers use it, so the public dashboard
 * and a live server report the same numbers for the same data.
 */
public final class StatsAggregator {

    /** Number of programs kept per list and overall. */
    public static final int TOP_PROGRAMS = 15;

    /** Number of unresolved country values kept for reporting. */
    public static final int TOP_UNRESOLVED = 25;

    private final CountryNormalizer countries;

    /**
     * Creates an aggregator.
     *
     * @param countries resolves free-text country values to ISO codes
     */
    public StatsAggregator(CountryNormalizer countries) {
        this.countries = Objects.requireNonNull(countries, "countries must not be null");
    }

    /**
     * Aggregates the given entities.
     *
     * @param entities the entities to aggregate, typically every entity of every list
     * @return the aggregates
     */
    public DatasetStats aggregate(Collection<SanctionedEntity> entities) {
        Objects.requireNonNull(entities, "entities must not be null");

        Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        Map<ListSource, SourceAccumulator> sources = new EnumMap<>(ListSource.class);
        Map<String, CountryAccumulator> byCountry = new TreeMap<>();
        Map<ProgramKey, Integer> programs = new HashMap<>();
        Map<ProgramKey, String> programNames = new HashMap<>();
        Map<IdentifierType, Integer> identifiers = new EnumMap<>(IdentifierType.class);
        Map<ScriptType, Integer> scripts = new EnumMap<>(ScriptType.class);
        Map<String, Integer> unresolved = new HashMap<>();
        int totalNames = 0;

        for (SanctionedEntity entity : entities) {
            int names = 1 + entity.aliases().size();
            totalNames += names;
            byType.merge(entity.entityType(), 1, Integer::sum);

            countScript(scripts, entity.primaryName());
            entity.aliases().forEach(alias -> countScript(scripts, alias));
            entity.identifiers().forEach(id -> identifiers.merge(id.type(), 1, Integer::sum));

            Set<String> byNationality = new TreeSet<>();
            resolveInto(entity.nationalities(), byNationality, unresolved);
            resolveInto(entity.citizenships(), byNationality, unresolved);
            Set<String> byAddress = new TreeSet<>();
            resolveInto(
                    entity.addresses().stream().map(Address::country).toList(),
                    byAddress,
                    unresolved);
            Set<String> linked = new TreeSet<>(byNationality);
            linked.addAll(byAddress);

            for (String code : linked) {
                byCountry
                        .computeIfAbsent(code, c -> new CountryAccumulator())
                        .add(entity, byNationality.contains(code), byAddress.contains(code));
            }

            Set<ProgramKey> entityPrograms = new HashSet<>();
            for (SanctionsProgram program : entity.programs()) {
                ProgramKey key = new ProgramKey(entity.listSource(), program.code());
                if (entityPrograms.add(key)) {
                    programs.merge(key, 1, Integer::sum);
                }
                if (program.name() != null && !program.name().isBlank()) {
                    programNames.putIfAbsent(key, program.name());
                }
            }

            sources.computeIfAbsent(entity.listSource(), s -> new SourceAccumulator())
                    .add(entity, names, linked);
        }

        List<ProgramCount> allPrograms = new ArrayList<>(programs.size());
        programs.forEach(
                (key, count) ->
                        allPrograms.add(
                                new ProgramCount(
                                        key.source(), key.code(), programNames.get(key), count)));
        allPrograms.sort(PROGRAM_ORDER);

        Map<ListSource, SourceStats> bySource = new EnumMap<>(ListSource.class);
        sources.forEach(
                (source, acc) ->
                        bySource.put(
                                source,
                                acc.toStats(
                                        allPrograms.stream()
                                                .filter(p -> p.source() == source)
                                                .limit(TOP_PROGRAMS)
                                                .toList())));

        Map<String, CountryStats> countryStats = new TreeMap<>();
        byCountry.forEach((code, acc) -> countryStats.put(code, acc.toStats()));

        int unresolvedOccurrences = unresolved.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Integer> topUnresolved = new LinkedHashMap<>();
        unresolved.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Integer>comparingByValue()
                                .reversed()
                                .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_UNRESOLVED)
                .forEach(e -> topUnresolved.put(e.getKey(), e.getValue()));

        return new DatasetStats(
                entities.size(),
                totalNames,
                byType,
                bySource,
                countryStats,
                programs.size(),
                allPrograms.stream().limit(TOP_PROGRAMS).toList(),
                identifiers,
                scripts,
                new UnresolvedCountries(unresolvedOccurrences, topUnresolved));
    }

    /**
     * Returns the countries an entity is linked to by nationality, citizenship or address.
     *
     * @param entity the entity
     * @return ISO 3166-1 alpha-2 codes, sorted
     */
    public Set<String> countryCodes(SanctionedEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        Set<String> codes = new TreeSet<>();
        Map<String, Integer> ignored = new HashMap<>();
        resolveInto(entity.nationalities(), codes, ignored);
        resolveInto(entity.citizenships(), codes, ignored);
        resolveInto(entity.addresses().stream().map(Address::country).toList(), codes, ignored);
        return codes;
    }

    private void resolveInto(
            Collection<String> values, Set<String> codes, Map<String, Integer> unresolved) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            for (String part : value.split(";")) {
                String trimmed = part.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                countries
                        .toIso2(trimmed)
                        .ifPresentOrElse(
                                codes::add, () -> unresolved.merge(trimmed, 1, Integer::sum));
            }
        }
    }

    private static void countScript(Map<ScriptType, Integer> scripts, NameInfo name) {
        if (name.script() != null) {
            scripts.merge(name.script(), 1, Integer::sum);
        }
    }

    private static final Comparator<ProgramCount> PROGRAM_ORDER =
            Comparator.comparingInt(ProgramCount::entities)
                    .reversed()
                    .thenComparing(ProgramCount::source)
                    .thenComparing(ProgramCount::code);

    private record ProgramKey(ListSource source, String code) {}

    private static final class SourceAccumulator {
        private int entities;
        private int names;
        private final Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);
        private final Set<String> countries = new TreeSet<>();
        private int withDateOfBirth;
        private int withNationality;
        private int withAddress;
        private int withIdentifiers;
        private int withAliases;
        private int withProgram;
        private int withListedDate;

        void add(SanctionedEntity entity, int entityNames, Set<String> linkedCountries) {
            entities++;
            names += entityNames;
            byType.merge(entity.entityType(), 1, Integer::sum);
            countries.addAll(linkedCountries);
            if (!entity.datesOfBirth().isEmpty()) withDateOfBirth++;
            if (!entity.nationalities().isEmpty() || !entity.citizenships().isEmpty()) {
                withNationality++;
            }
            if (entity.addresses().stream().anyMatch(StatsAggregator::hasContent)) withAddress++;
            if (!entity.identifiers().isEmpty()) withIdentifiers++;
            if (!entity.aliases().isEmpty()) withAliases++;
            if (!entity.programs().isEmpty()) withProgram++;
            if (entity.listedDate() != null) withListedDate++;
        }

        SourceStats toStats(List<ProgramCount> topPrograms) {
            return new SourceStats(
                    entities,
                    names,
                    byType,
                    countries.size(),
                    new Completeness(
                            entities,
                            withDateOfBirth,
                            withNationality,
                            withAddress,
                            withIdentifiers,
                            withAliases,
                            withProgram,
                            withListedDate),
                    topPrograms);
        }
    }

    private static final class CountryAccumulator {
        private int entities;
        private int byNationality;
        private int byAddress;
        private final Map<ListSource, Integer> bySource = new EnumMap<>(ListSource.class);
        private final Map<EntityType, Integer> byType = new EnumMap<>(EntityType.class);

        void add(SanctionedEntity entity, boolean nationality, boolean address) {
            entities++;
            if (nationality) byNationality++;
            if (address) byAddress++;
            bySource.merge(entity.listSource(), 1, Integer::sum);
            byType.merge(entity.entityType(), 1, Integer::sum);
        }

        CountryStats toStats() {
            return new CountryStats(entities, byNationality, byAddress, bySource, byType);
        }
    }

    private static boolean hasContent(Address address) {
        return isPresent(address.fullAddress())
                || isPresent(address.street())
                || isPresent(address.city())
                || isPresent(address.country());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
