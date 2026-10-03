package dev.sieve.core.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The unified domain model for an entry in the risk database.
 *
 * <p>This record normalizes data from heterogeneous sanctions sources (OFAC SDN, EU Consolidated,
 * UN Consolidated, UK HMT) into a single, consistent representation. Every field beyond the
 * required identifiers is populated on a best-effort basis from the source data.
 *
 * @param id source-specific identifier (e.g., OFAC SDN entry ID)
 * @param entityType classification of this entity
 * @param listSource the sanctions list this entity originates from
 * @param primaryName the entity's structured primary name
 * @param aliases alternative names (AKAs, FKAs, maiden names)
 * @param addresses known physical addresses
 * @param identifiers identity documents and reference numbers
 * @param nationalities known nationalities
 * @param citizenships known citizenships
 * @param datesOfBirth known dates of birth
 * @param placesOfBirth known places of birth
 * @param remarks free-text remarks from the source list
 * @param programs sanctions programs under which this entity is listed
 * @param listedDate when the entity was first added to the list, may be {@code null}
 * @param lastUpdated when the entity's record was last modified, may be {@code null}
 * @param topics why the entity is of interest (sanctioned, PEP, debarred and so on)
 * @param relations links from this entity to other entities
 */
public record SanctionedEntity(
        String id,
        EntityType entityType,
        ListSource listSource,
        NameInfo primaryName,
        List<NameInfo> aliases,
        List<Address> addresses,
        List<Identifier> identifiers,
        List<String> nationalities,
        List<String> citizenships,
        List<LocalDate> datesOfBirth,
        List<String> placesOfBirth,
        String remarks,
        List<SanctionsProgram> programs,
        Instant listedDate,
        Instant lastUpdated,
        Set<RiskTopic> topics,
        List<Relation> relations) {

    /**
     * Compact constructor with validation and defensive copies.
     *
     * @throws NullPointerException if any required field is {@code null}
     */
    public SanctionedEntity {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");
        Objects.requireNonNull(listSource, "listSource must not be null");
        Objects.requireNonNull(primaryName, "primaryName must not be null");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        nationalities = nationalities == null ? List.of() : List.copyOf(nationalities);
        citizenships = citizenships == null ? List.of() : List.copyOf(citizenships);
        datesOfBirth = datesOfBirth == null ? List.of() : List.copyOf(datesOfBirth);
        placesOfBirth = placesOfBirth == null ? List.of() : List.copyOf(placesOfBirth);
        programs = programs == null ? List.of() : List.copyOf(programs);
        topics =
                topics == null || topics.isEmpty()
                        ? Set.of()
                        : Collections.unmodifiableSet(EnumSet.copyOf(topics));
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    /**
     * Creates an entry from a sanctions list, with the {@link RiskTopic#SANCTION} topic and no
     * relations.
     *
     * @param id source-specific identifier
     * @param entityType classification of this entity
     * @param listSource the sanctions list this entity originates from
     * @param primaryName the entity's structured primary name
     * @param aliases alternative names
     * @param addresses known physical addresses
     * @param identifiers identity documents and reference numbers
     * @param nationalities known nationalities
     * @param citizenships known citizenships
     * @param datesOfBirth known dates of birth
     * @param placesOfBirth known places of birth
     * @param remarks free-text remarks from the source list
     * @param programs sanctions programs under which this entity is listed
     * @param listedDate when the entity was first added to the list, may be {@code null}
     * @param lastUpdated when the entity's record was last modified, may be {@code null}
     */
    public SanctionedEntity(
            String id,
            EntityType entityType,
            ListSource listSource,
            NameInfo primaryName,
            List<NameInfo> aliases,
            List<Address> addresses,
            List<Identifier> identifiers,
            List<String> nationalities,
            List<String> citizenships,
            List<LocalDate> datesOfBirth,
            List<String> placesOfBirth,
            String remarks,
            List<SanctionsProgram> programs,
            Instant listedDate,
            Instant lastUpdated) {
        this(
                id,
                entityType,
                listSource,
                primaryName,
                aliases,
                addresses,
                identifiers,
                nationalities,
                citizenships,
                datesOfBirth,
                placesOfBirth,
                remarks,
                programs,
                listedDate,
                lastUpdated,
                Set.of(RiskTopic.SANCTION),
                List.of());
    }

    /**
     * Returns whether this entity carries the given topic.
     *
     * @param topic the topic to check, must not be {@code null}
     * @return {@code true} if the entity has the topic
     */
    public boolean hasTopic(RiskTopic topic) {
        Objects.requireNonNull(topic, "topic must not be null");
        return topics.contains(topic);
    }
}
