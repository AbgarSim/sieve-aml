package dev.sieve.ingest.ftm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes entities in the FollowTheMoney entity format: one JSON object per line, each with an
 * {@code id}, a {@code schema} and multi-valued {@code properties}.
 *
 * <p>Each entity becomes one object of its kind's schema (see {@link EntityType#schema()}). Its
 * relations become link objects ({@code Ownership}, {@code Family}, {@code Occupancy} and so on)
 * and a sanctioned entity's programs become one {@code Sanction} object. A position named by a
 * {@link RelationType#POSITION_HELD} relation is written once as a {@code Position}. Values a
 * schema has no property for are left out, so the output loads in FollowTheMoney tools unchanged.
 */
public final class FtmWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CountryNormalizer countries = CountryNormalizer.standard();

    /**
     * Writes the entities to the stream as JSON lines, in the order given.
     *
     * @param entities the entities to write, must not be {@code null}
     * @param out where to write; it is flushed but not closed
     * @return the number of objects written, including links, sanctions and positions
     * @throws IOException if writing fails
     */
    public long write(Collection<SanctionedEntity> entities, OutputStream out) throws IOException {
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(out, "out must not be null");
        Set<String> positions = new HashSet<>();
        long written = 0;
        for (SanctionedEntity entity : entities) {
            for (ObjectNode node : toFtm(entity)) {
                if (node.path("schema").asText().equals("Position")
                        && !positions.add(node.path("id").asText())) {
                    continue;
                }
                out.write(MAPPER.writeValueAsBytes(node));
                out.write('\n');
                written++;
            }
        }
        out.flush();
        return written;
    }

    /**
     * Converts one entity to its FollowTheMoney objects: the entity first, then its sanction,
     * relations and positions.
     *
     * @param entity the entity, must not be {@code null}
     * @return the objects, never empty
     */
    public List<ObjectNode> toFtm(SanctionedEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        List<ObjectNode> nodes = new ArrayList<>();
        nodes.add(thing(entity));
        sanction(entity).ifPresent(nodes::add);
        for (Relation relation : entity.relations()) {
            nodes.add(link(entity, relation));
            if (relation.type() == RelationType.POSITION_HELD && relation.role() != null) {
                ObjectNode position = node(relation.targetId(), "Position", entity);
                add(position, "name", relation.role());
                nodes.add(position);
            }
        }
        return nodes;
    }

    private ObjectNode thing(SanctionedEntity entity) {
        EntityType type = entity.entityType();
        ObjectNode node = node(entity.id(), type.schema(), entity);
        Props props = new Props(node, type);
        NameInfo primary = entity.primaryName();
        props.add("name", primary.fullName());
        Set<String> names = new LinkedHashSet<>();
        names.add(primary.fullName());
        for (NameInfo alias : entity.aliases()) {
            if (names.add(alias.fullName())) {
                props.add("alias", alias.fullName());
            }
        }
        props.add("firstName", primary.givenName());
        props.add("lastName", primary.familyName());
        props.add("middleName", primary.middleName());
        props.add("title", primary.title());
        for (LocalDate date : entity.datesOfBirth()) {
            props.add("birthDate", date.toString());
        }
        entity.placesOfBirth().forEach(place -> props.add("birthPlace", place));
        String nationalityProperty =
                type == EntityType.INDIVIDUAL
                        ? "nationality"
                        : type.isLegalEntity() ? "jurisdiction" : "country";
        entity.nationalities().forEach(c -> props.add(nationalityProperty, country(c)));
        entity.citizenships().forEach(c -> props.add("citizenship", country(c)));
        for (Address address : entity.addresses()) {
            props.add("address", fullAddress(address));
            props.add("country", country(address.country()));
        }
        for (Identifier identifier : entity.identifiers()) {
            FtmProperties.identifierProperty(type, identifier.type())
                    .ifPresent(p -> props.add(p, identifier.value()));
        }
        props.add("notes", entity.remarks());
        entity.topics().forEach(t -> props.add("topics", t.code()));
        for (SanctionsProgram program : entity.programs()) {
            props.add("programId", program.code());
            props.add("program", program.name());
        }
        if (entity.listedDate() != null) {
            props.add("createdAt", date(entity.listedDate()));
        }
        if (entity.lastUpdated() != null) {
            props.add("modifiedAt", date(entity.lastUpdated()));
        }
        return node;
    }

    /** One {@code Sanction} for an entity listed under a sanctions program. */
    private Optional<ObjectNode> sanction(SanctionedEntity entity) {
        if (!entity.hasTopic(RiskTopic.SANCTION) || entity.programs().isEmpty()) {
            return Optional.empty();
        }
        ObjectNode node = node(entity.id() + "-sanction", "Sanction", entity);
        add(node, "entity", entity.id());
        add(node, "authority", entity.listSource().displayName());
        for (SanctionsProgram program : entity.programs()) {
            add(node, "programId", program.code());
            add(node, "program", program.name());
        }
        if (entity.listedDate() != null) {
            add(node, "listingDate", date(entity.listedDate()));
        }
        return Optional.of(node);
    }

    private ObjectNode link(SanctionedEntity entity, Relation relation) {
        RelationType type = relation.type();
        String id =
                entity.id()
                        + "-"
                        + type.name().toLowerCase().replace('_', '-')
                        + "-"
                        + relation.targetId();
        ObjectNode node = node(id, type.schema(), entity);
        String[] ends = ends(type);
        add(node, ends[0], entity.id());
        add(node, ends[1], relation.targetId());
        if (ends[2] != null) {
            add(node, ends[2], relation.role());
        }
        if (relation.sharePercentage() != null) {
            add(node, "percentage", String.valueOf(relation.sharePercentage()));
        }
        if (relation.startDate() != null) {
            add(node, "startDate", relation.startDate().toString());
        }
        if (relation.endDate() != null) {
            add(node, "endDate", relation.endDate().toString());
        }
        return node;
    }

    /**
     * The properties of a link's schema that hold the source end, the target end and the role, in
     * that order. The role is {@code null} where the schema has none.
     */
    static String[] ends(RelationType type) {
        return switch (type) {
            case OWNERSHIP -> new String[] {"owner", "asset", "role"};
            case DIRECTORSHIP -> new String[] {"director", "organization", "role"};
            case FAMILY -> new String[] {"person", "relative", "relationship"};
            case ASSOCIATE -> new String[] {"person", "associate", "relationship"};
            case LINKED -> new String[] {"subject", "object", "role"};
            case POSITION_HELD -> new String[] {"holder", "post", null};
        };
    }

    private static ObjectNode node(String id, String schema, SanctionedEntity entity) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", id);
        node.put("schema", schema);
        node.putObject("properties");
        node.putArray("datasets").add(entity.listSource().name().toLowerCase());
        return node;
    }

    private static void add(ObjectNode node, String property, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        ObjectNode properties = (ObjectNode) node.get("properties");
        ArrayNode values =
                properties.has(property)
                        ? (ArrayNode) properties.get(property)
                        : properties.putArray(property);
        for (var existing : values) {
            if (existing.asText().equals(value)) {
                return;
            }
        }
        values.add(value);
    }

    private String country(String raw) {
        return countries.toIso2(raw).map(String::toLowerCase).orElse(null);
    }

    private static String date(java.time.Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static String fullAddress(Address address) {
        if (address.fullAddress() != null && !address.fullAddress().isBlank()) {
            return address.fullAddress();
        }
        List<String> parts =
                Stream.of(
                                address.street(),
                                address.city(),
                                address.stateOrProvince(),
                                address.postalCode(),
                                address.country())
                        .filter(p -> p != null && !p.isBlank())
                        .toList();
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** Adds values only to properties the entity's schema defines. */
    private record Props(ObjectNode node, EntityType type) {
        void add(String property, String value) {
            if (FtmProperties.accepts(type, property)) {
                FtmWriter.add(node, property, value);
            }
        }
    }

    /**
     * Writes the entities to a string, one JSON object per line. Meant for tests and small sets.
     *
     * @param entities the entities to write
     * @return the JSON lines
     */
    public String writeToString(Collection<SanctionedEntity> entities) {
        var out = new java.io.ByteArrayOutputStream();
        try {
            write(entities, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
