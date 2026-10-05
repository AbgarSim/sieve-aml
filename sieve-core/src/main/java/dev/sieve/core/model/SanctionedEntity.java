package dev.sieve.core.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * @param gender the gender the list records, {@code null} when it records none
 * @param deceased {@link Boolean#TRUE} when the list says the person is dead, {@code null} when it
 *     says nothing about it
 * @param remarks free-text remarks from the source list
 * @param listingReasons the reasons the list gives for the listing, such as the UK's statement of
 *     reasons or a debarment ground; empty when the list states none apart from its remarks
 * @param vessel what the list says about a vessel (flag, type, call sign, tonnage), {@code null}
 *     for anything but a vessel or when the list gives none of it
 * @param programs sanctions programs under which this entity is listed
 * @param listedDate when the entity was first added to the list, may be {@code null}
 * @param lastUpdated when the entity's record was last modified, may be {@code null}
 * @param topics why the entity is of interest (sanctioned, PEP, debarred and so on)
 * @param relations links from this entity to other entities
 * @param provenance where and when each value was seen, at most one entry per value (see {@link
 *     SourcedValue#keysOf}); empty until the entity is ingested
 * @param images pictures of the entity the list publishes, such as a wanted person's photo
 * @param links pages about the entity, such as its listing page or the legal acts that listed it
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
        Gender gender,
        Boolean deceased,
        String remarks,
        List<String> listingReasons,
        VesselDetails vessel,
        List<SanctionsProgram> programs,
        Instant listedDate,
        Instant lastUpdated,
        Set<RiskTopic> topics,
        List<Relation> relations,
        List<SourcedValue> provenance,
        List<EntityImage> images,
        List<EntityLink> links) {

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
        listingReasons = listingReasons == null ? List.of() : List.copyOf(listingReasons);
        programs = programs == null ? List.of() : List.copyOf(programs);
        topics =
                topics == null || topics.isEmpty()
                        ? Set.of()
                        : Collections.unmodifiableSet(EnumSet.copyOf(topics));
        relations = relations == null ? List.of() : List.copyOf(relations);
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
        images = images == null ? List.of() : List.copyOf(images);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /**
     * Creates an entry without pictures or links.
     *
     * @param id source-specific identifier
     * @param entityType classification of this entity
     * @param listSource the list this entity originates from
     * @param primaryName the entity's structured primary name
     * @param aliases alternative names
     * @param addresses known physical addresses
     * @param identifiers identity documents and reference numbers
     * @param nationalities known nationalities
     * @param citizenships known citizenships
     * @param datesOfBirth known dates of birth
     * @param placesOfBirth known places of birth
     * @param gender the gender the list records, {@code null} when it records none
     * @param deceased {@link Boolean#TRUE} when the list says the person is dead
     * @param remarks free-text remarks from the source list
     * @param listingReasons the reasons the list gives for the listing
     * @param vessel what the list says about a vessel, {@code null} when nothing
     * @param programs sanctions programs under which this entity is listed
     * @param listedDate when the entity was first added to the list, may be {@code null}
     * @param lastUpdated when the entity's record was last modified, may be {@code null}
     * @param topics why the entity is of interest
     * @param relations links from this entity to other entities
     * @param provenance where and when each value was seen
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
            Gender gender,
            Boolean deceased,
            String remarks,
            List<String> listingReasons,
            VesselDetails vessel,
            List<SanctionsProgram> programs,
            Instant listedDate,
            Instant lastUpdated,
            Set<RiskTopic> topics,
            List<Relation> relations,
            List<SourcedValue> provenance) {
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
                gender,
                deceased,
                remarks,
                listingReasons,
                vessel,
                programs,
                listedDate,
                lastUpdated,
                topics,
                relations,
                provenance,
                List.of(),
                List.of());
    }

    /**
     * Creates an entry without the fields of the second model round: no gender, no word on whether
     * the person is dead, no listing reasons and no vessel details.
     *
     * @param id source-specific identifier
     * @param entityType classification of this entity
     * @param listSource the list this entity originates from
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
     * @param topics why the entity is of interest
     * @param relations links from this entity to other entities
     * @param provenance where and when each value was seen
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
            Instant lastUpdated,
            Set<RiskTopic> topics,
            List<Relation> relations,
            List<SourcedValue> provenance) {
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
                null,
                null,
                remarks,
                List.of(),
                null,
                programs,
                listedDate,
                lastUpdated,
                topics,
                relations,
                provenance);
    }

    /**
     * Creates an entry with topics and relations but no provenance yet.
     *
     * @param id source-specific identifier
     * @param entityType classification of this entity
     * @param listSource the list this entity originates from
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
     * @param topics why the entity is of interest
     * @param relations links from this entity to other entities
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
            Instant lastUpdated,
            Set<RiskTopic> topics,
            List<Relation> relations) {
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
                topics,
                relations,
                List.of());
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
                List.of(),
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

    /**
     * Returns where and when a value of this entity was seen.
     *
     * @param key the value, for example {@code SourcedValue.Key.of(alias)}
     * @return its provenance, or empty if none is recorded
     */
    public Optional<Provenance> provenanceOf(SourcedValue.Key key) {
        Objects.requireNonNull(key, "key must not be null");
        return provenance.stream()
                .filter(v -> v.describes(key))
                .map(SourcedValue::provenance)
                .findFirst();
    }

    /**
     * Returns a copy with the given provenance.
     *
     * @param provenance where and when each value was seen
     * @return the copy
     */
    public SanctionedEntity withProvenance(List<SourcedValue> provenance) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance);
    }

    /**
     * Returns a copy with the given relations.
     *
     * @param relations links from this entity to other entities
     * @return the copy
     */
    public SanctionedEntity withRelations(List<Relation> relations) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance);
    }

    /**
     * Returns a copy with the given gender.
     *
     * @param gender the gender the list records, {@code null} when it records none
     * @return the copy
     */
    public SanctionedEntity withGender(Gender gender) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance);
    }

    /**
     * Returns a copy that says whether the list reports the person dead.
     *
     * @param deceased {@link Boolean#TRUE} when the list says so, {@code null} when it says nothing
     * @return the copy
     */
    public SanctionedEntity withDeceased(Boolean deceased) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance);
    }

    /**
     * Returns a copy with the given listing reasons.
     *
     * @param listingReasons the reasons the list gives for the listing
     * @return the copy
     */
    public SanctionedEntity withListingReasons(List<String> listingReasons) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance);
    }

    /**
     * Returns a copy with the given vessel details.
     *
     * @param vessel what the list says about the vessel, {@code null} when nothing
     * @return the copy
     */
    public SanctionedEntity withVessel(VesselDetails vessel) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance);
    }

    /**
     * Returns a copy with the given pictures.
     *
     * @param images pictures of the entity the list publishes
     * @return the copy
     */
    public SanctionedEntity withImages(List<EntityImage> images) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance, images, links);
    }

    /**
     * Returns a copy with the given links.
     *
     * @param links pages about the entity
     * @return the copy
     */
    public SanctionedEntity withLinks(List<EntityLink> links) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance, images, links);
    }

    private SanctionedEntity copy(
            Gender gender,
            Boolean deceased,
            List<String> listingReasons,
            VesselDetails vessel,
            List<Relation> relations,
            List<SourcedValue> provenance) {
        return copy(gender, deceased, listingReasons, vessel, relations, provenance, images, links);
    }

    private SanctionedEntity copy(
            Gender gender,
            Boolean deceased,
            List<String> listingReasons,
            VesselDetails vessel,
            List<Relation> relations,
            List<SourcedValue> provenance,
            List<EntityImage> images,
            List<EntityLink> links) {
        return new SanctionedEntity(
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
                gender,
                deceased,
                remarks,
                listingReasons,
                vessel,
                programs,
                listedDate,
                lastUpdated,
                topics,
                relations,
                provenance,
                images,
                links);
    }
}
