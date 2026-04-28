package dev.sieve.core.dedup;

import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A canonical (deduplicated) entity that merges one or more {@link SanctionedEntity} records
 * representing the same real-world person, organization, vessel, or aircraft across different
 * sanctions lists.
 *
 * <p>When the same person appears on OFAC, EU, and UN lists with different spellings and metadata,
 * the deduplication process merges them into a single canonical entity. This eliminates
 * false-positive inflation from duplicate entries during screening.
 *
 * <p>The canonical entity preserves all source entities for audit and compliance purposes, while
 * providing a unified view of the merged data.
 *
 * @param canonicalId stable identifier for this canonical entity
 * @param entityType the shared entity type (all merged entities must have the same type)
 * @param primaryName the best primary name chosen from source entities
 * @param allNames all unique names (primary + aliases) collected across all source entities
 * @param addresses union of all addresses from source entities
 * @param identifiers union of all identifiers from source entities
 * @param nationalities union of all nationalities
 * @param citizenships union of all citizenships
 * @param datesOfBirth union of all dates of birth
 * @param placesOfBirth union of all places of birth
 * @param programs union of all sanctions programs
 * @param sourceEntities the original entities that were merged, keyed by {@link ListSource}
 */
public record CanonicalEntity(
        String canonicalId,
        EntityType entityType,
        NameInfo primaryName,
        List<NameInfo> allNames,
        List<Address> addresses,
        List<Identifier> identifiers,
        List<String> nationalities,
        List<String> citizenships,
        List<LocalDate> datesOfBirth,
        List<String> placesOfBirth,
        List<SanctionsProgram> programs,
        Map<ListSource, List<SanctionedEntity>> sourceEntities) {

    /**
     * Compact constructor with validation and defensive copies.
     *
     * @throws NullPointerException if any required field is {@code null}
     * @throws IllegalArgumentException if {@code sourceEntities} is empty
     */
    public CanonicalEntity {
        Objects.requireNonNull(canonicalId, "canonicalId must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(primaryName, "primaryName must not be null");
        Objects.requireNonNull(sourceEntities, "sourceEntities must not be null");
        if (sourceEntities.isEmpty()) {
            throw new IllegalArgumentException("sourceEntities must not be empty");
        }
        allNames = allNames == null ? List.of() : List.copyOf(allNames);
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        nationalities = nationalities == null ? List.of() : List.copyOf(nationalities);
        citizenships = citizenships == null ? List.of() : List.copyOf(citizenships);
        datesOfBirth = datesOfBirth == null ? List.of() : List.copyOf(datesOfBirth);
        placesOfBirth = placesOfBirth == null ? List.of() : List.copyOf(placesOfBirth);
        programs = programs == null ? List.of() : List.copyOf(programs);
        sourceEntities = Map.copyOf(sourceEntities);
    }

    /**
     * Returns all source entity IDs across all list sources.
     *
     * @return flat list of source entity IDs, never {@code null}
     */
    public List<String> sourceEntityIds() {
        return sourceEntities.values().stream()
                .flatMap(List::stream)
                .map(SanctionedEntity::id)
                .toList();
    }

    /**
     * Returns the set of list sources this canonical entity appears on.
     *
     * @return the list sources, never empty
     */
    public Set<ListSource> listSources() {
        return sourceEntities.keySet();
    }

    /**
     * Returns the total number of source entities merged into this canonical entity.
     *
     * @return source entity count, always {@code >= 1}
     */
    public int sourceCount() {
        return sourceEntities.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Creates a canonical entity from a single source entity (no merge).
     *
     * @param entity the source entity
     * @return a singleton canonical entity
     */
    public static CanonicalEntity singleton(SanctionedEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new CanonicalEntity(
                "canonical-" + entity.id(),
                entity.entityType(),
                entity.primaryName(),
                collectAllNames(List.of(entity)),
                entity.addresses(),
                entity.identifiers(),
                entity.nationalities(),
                entity.citizenships(),
                entity.datesOfBirth(),
                entity.placesOfBirth(),
                entity.programs(),
                Map.of(entity.listSource(), List.of(entity)));
    }

    /**
     * Merges multiple source entities into a single canonical entity.
     *
     * <p>The primary name is chosen from the entity with the most complete name information. All
     * names, identifiers, addresses, and other metadata are merged with deduplication.
     *
     * @param canonicalId the canonical ID to assign
     * @param entities the entities to merge, must all share the same {@link EntityType}
     * @return the merged canonical entity
     * @throws IllegalArgumentException if entities is empty or entity types differ
     */
    public static CanonicalEntity merge(String canonicalId, List<SanctionedEntity> entities) {
        Objects.requireNonNull(canonicalId, "canonicalId must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        if (entities.isEmpty()) {
            throw new IllegalArgumentException("entities must not be empty");
        }

        EntityType sharedType = entities.getFirst().entityType();
        for (SanctionedEntity e : entities) {
            if (e.entityType() != sharedType) {
                throw new IllegalArgumentException(
                        "Cannot merge entities of different types: " + sharedType + " vs "
                                + e.entityType());
            }
        }

        if (entities.size() == 1) {
            SanctionedEntity sole = entities.getFirst();
            return new CanonicalEntity(
                    canonicalId,
                    sharedType,
                    sole.primaryName(),
                    collectAllNames(entities),
                    sole.addresses(),
                    sole.identifiers(),
                    sole.nationalities(),
                    sole.citizenships(),
                    sole.datesOfBirth(),
                    sole.placesOfBirth(),
                    sole.programs(),
                    Map.of(sole.listSource(), List.of(sole)));
        }

        // Choose the best primary name (most complete structured name)
        NameInfo bestPrimary = chooseBestPrimary(entities);

        // Merge all metadata with deduplication
        Set<String> seenAddresses = new LinkedHashSet<>();
        List<Address> mergedAddresses = new ArrayList<>();
        Set<String> seenIdentifiers = new LinkedHashSet<>();
        List<Identifier> mergedIdentifiers = new ArrayList<>();
        Set<String> mergedNationalities = new LinkedHashSet<>();
        Set<String> mergedCitizenships = new LinkedHashSet<>();
        Set<LocalDate> mergedDobs = new LinkedHashSet<>();
        Set<String> mergedPobs = new LinkedHashSet<>();
        Set<String> seenPrograms = new LinkedHashSet<>();
        List<SanctionsProgram> mergedPrograms = new ArrayList<>();

        Map<ListSource, List<SanctionedEntity>> sourceMap = new LinkedHashMap<>();

        for (SanctionedEntity entity : entities) {
            // Addresses
            for (Address addr : entity.addresses()) {
                String key = addressKey(addr);
                if (seenAddresses.add(key)) {
                    mergedAddresses.add(addr);
                }
            }
            // Identifiers
            for (Identifier id : entity.identifiers()) {
                String key = id.type() + ":" + id.value();
                if (seenIdentifiers.add(key)) {
                    mergedIdentifiers.add(id);
                }
            }
            mergedNationalities.addAll(entity.nationalities());
            mergedCitizenships.addAll(entity.citizenships());
            mergedDobs.addAll(entity.datesOfBirth());
            mergedPobs.addAll(entity.placesOfBirth());
            for (SanctionsProgram prog : entity.programs()) {
                String key = prog.source() + ":" + prog.code();
                if (seenPrograms.add(key)) {
                    mergedPrograms.add(prog);
                }
            }
            sourceMap
                    .computeIfAbsent(entity.listSource(), k -> new ArrayList<>())
                    .add(entity);
        }

        return new CanonicalEntity(
                canonicalId,
                sharedType,
                bestPrimary,
                collectAllNames(entities),
                mergedAddresses,
                mergedIdentifiers,
                List.copyOf(mergedNationalities),
                List.copyOf(mergedCitizenships),
                List.copyOf(mergedDobs),
                List.copyOf(mergedPobs),
                mergedPrograms,
                sourceMap);
    }

    private static NameInfo chooseBestPrimary(List<SanctionedEntity> entities) {
        NameInfo best = entities.getFirst().primaryName();
        int bestScore = nameCompleteness(best);
        for (int i = 1; i < entities.size(); i++) {
            NameInfo candidate = entities.get(i).primaryName();
            int score = nameCompleteness(candidate);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private static int nameCompleteness(NameInfo name) {
        int score = 0;
        if (name.givenName() != null && !name.givenName().isBlank()) score++;
        if (name.familyName() != null && !name.familyName().isBlank()) score++;
        if (name.middleName() != null && !name.middleName().isBlank()) score++;
        if (name.fullName() != null && !name.fullName().isBlank()) score++;
        return score;
    }

    private static List<NameInfo> collectAllNames(List<SanctionedEntity> entities) {
        Set<String> seenNames = new LinkedHashSet<>();
        List<NameInfo> allNames = new ArrayList<>();
        for (SanctionedEntity entity : entities) {
            addNameIfNew(entity.primaryName(), seenNames, allNames);
            for (NameInfo alias : entity.aliases()) {
                addNameIfNew(alias, seenNames, allNames);
            }
        }
        return allNames;
    }

    private static void addNameIfNew(NameInfo name, Set<String> seen, List<NameInfo> target) {
        String key = name.fullName().strip().toLowerCase();
        if (seen.add(key)) {
            target.add(name);
        }
    }

    private static String addressKey(Address addr) {
        String full = addr.fullAddress();
        if (full != null && !full.isBlank()) {
            return full.strip().toLowerCase();
        }
        return String.valueOf(addr.street()) + "|" + addr.city() + "|" + addr.country();
    }
}
