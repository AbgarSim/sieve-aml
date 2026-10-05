package dev.sieve.ingest.ftm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.Provenance;
import dev.sieve.core.model.Relation;
import dev.sieve.core.model.RelationType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.core.model.SourcedValue;
import dev.sieve.core.model.VesselDetails;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads entities in the FollowTheMoney entity format, one JSON object per line, as written by
 * {@link FtmWriter} or by other tools that use the format.
 *
 * <p>Objects whose schema is an entity kind (see {@link EntityType#fromSchema(String)}) become
 * entities. Link objects become {@link Relation}s on their source end (a {@code Position} names the
 * position held), {@code Sanction} objects add programs to the entity they name, and other schemas
 * are skipped. Partial dates such as {@code 1970} or {@code 1970-05} are kept as the first day of
 * the year or month.
 */
public final class FtmReader {

    private static final Logger log = LoggerFactory.getLogger(FtmReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Reads all entities from the stream and assigns them to the given list.
     *
     * @param in the JSON lines; it is not closed
     * @param source the list the imported entities belong to
     * @return the entities in the order they appear, with their relations and programs attached
     * @throws IOException if reading fails or a line is not valid JSON
     */
    public List<SanctionedEntity> read(InputStream in, ListSource source) throws IOException {
        Objects.requireNonNull(in, "in must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Map<String, JsonNode> things = new LinkedHashMap<>();
        Map<String, List<Relation>> relations = new HashMap<>();
        Map<String, List<JsonNode>> sanctions = new HashMap<>();
        Map<String, String> positions = new HashMap<>();
        int skipped = 0;
        BufferedReader reader =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            String schema = node.path("schema").asText();
            String id = node.path("id").asText();
            Optional<RelationType> linkType = linkType(schema);
            if (linkType.isPresent()) {
                link(linkType.get(), node)
                        .ifPresent(
                                e ->
                                        relations
                                                .computeIfAbsent(e.holder(), k -> new ArrayList<>())
                                                .add(e.relation()));
            } else if (schema.equals("Sanction")) {
                first(node, "entity")
                        .ifPresent(
                                e ->
                                        sanctions
                                                .computeIfAbsent(e, k -> new ArrayList<>())
                                                .add(node));
            } else if (schema.equals("Position")) {
                first(node, "name").ifPresent(name -> positions.put(id, name));
            } else if (!id.isBlank() && isEntity(schema) && first(node, "name").isPresent()) {
                things.put(id, node);
            } else {
                skipped++;
            }
        }
        List<SanctionedEntity> entities = new ArrayList<>(things.size());
        for (Map.Entry<String, JsonNode> entry : things.entrySet()) {
            String id = entry.getKey();
            entities.add(
                    entity(
                            id,
                            entry.getValue(),
                            source,
                            named(relations.getOrDefault(id, List.of()), positions),
                            sanctions.getOrDefault(id, List.of())));
        }
        log.info(
                "FtM import read [source={}, entities={}, skipped={}]",
                source,
                entities.size(),
                skipped);
        return entities;
    }

    private static SanctionedEntity entity(
            String id,
            JsonNode node,
            ListSource source,
            List<Relation> relations,
            List<JsonNode> sanctions) {
        EntityType type = EntityType.fromSchema(node.path("schema").asText());
        List<String> names = values(node, "name");
        NameInfo primary =
                new NameInfo(
                        names.getFirst(),
                        first(node, "firstName").orElse(null),
                        first(node, "lastName").orElse(null),
                        first(node, "middleName").orElse(null),
                        first(node, "title").orElse(null),
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        List<NameInfo> aliases = new ArrayList<>();
        names.stream().skip(1).forEach(n -> aliases.add(alias(n, NameStrength.STRONG)));
        values(node, "alias").forEach(n -> aliases.add(alias(n, NameStrength.STRONG)));
        values(node, "weakAlias").forEach(n -> aliases.add(alias(n, NameStrength.WEAK)));

        List<Identifier> identifiers = new ArrayList<>();
        node.path("properties")
                .fields()
                .forEachRemaining(
                        field ->
                                FtmProperties.identifierType(type, field.getKey())
                                        .ifPresent(
                                                t ->
                                                        field.getValue()
                                                                .forEach(
                                                                        v ->
                                                                                identifiers.add(
                                                                                        new Identifier(
                                                                                                t,
                                                                                                v
                                                                                                        .asText(),
                                                                                                null,
                                                                                                null)))));

        List<Address> addresses = new ArrayList<>();
        values(node, "address")
                .forEach(a -> addresses.add(new Address(null, null, null, null, null, a)));
        List<String> nationalities = new ArrayList<>();
        nationalities.addAll(upper(values(node, "nationality")));
        nationalities.addAll(upper(values(node, "jurisdiction")));
        if (nationalities.isEmpty() && addresses.isEmpty()) {
            nationalities.addAll(upper(values(node, "country")));
        }

        List<SanctionsProgram> programs = new ArrayList<>();
        addPrograms(node, source, programs);
        Instant listed = instant(first(node, "createdAt"));
        for (JsonNode sanction : sanctions) {
            addPrograms(sanction, source, programs);
            if (listed == null) {
                listed =
                        instant(
                                first(sanction, "listingDate")
                                        .or(() -> first(sanction, "startDate")));
            }
        }

        Set<RiskTopic> topics = EnumSet.noneOf(RiskTopic.class);
        for (String code : values(node, "topics")) {
            topic(code).ifPresent(topics::add);
        }
        if (!sanctions.isEmpty()) {
            topics.add(RiskTopic.SANCTION);
        }

        SanctionedEntity entity =
                new SanctionedEntity(
                        id,
                        type,
                        source,
                        primary,
                        aliases,
                        addresses,
                        identifiers,
                        nationalities,
                        upper(values(node, "citizenship")),
                        dates(values(node, "birthDate")),
                        values(node, "birthPlace"),
                        String.join("\n", values(node, "notes")).strip().isEmpty()
                                ? null
                                : String.join("\n", values(node, "notes")),
                        programs.stream().distinct().toList(),
                        listed,
                        instant(first(node, "modifiedAt")),
                        topics,
                        relations);
        entity = entity.withGender(first(node, "gender").flatMap(Gender::parse).orElse(null));
        if (type == EntityType.VESSEL) {
            entity =
                    entity.withVessel(
                            VesselDetails.of(
                                    first(node, "flag").map(String::toUpperCase).orElse(null),
                                    first(node, "type").orElse(null),
                                    first(node, "callSign").orElse(null),
                                    integer(first(node, "tonnage")),
                                    integer(first(node, "grossRegisteredTonnage"))));
        }
        List<String> reasons =
                sanctions.stream().flatMap(s -> values(s, "reason").stream()).distinct().toList();
        entity = entity.withListingReasons(reasons);
        return withSeen(entity, node);
    }

    private static Integer integer(Optional<String> value) {
        try {
            return value.map(String::strip).map(Integer::valueOf).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Gives every value the entity's {@code first_seen} and {@code last_seen}, when the object has
     * them; the format records them per entity, not per value.
     */
    private static SanctionedEntity withSeen(SanctionedEntity entity, JsonNode node) {
        Instant firstSeen = timestamp(node.path("first_seen").asText(null));
        Instant lastSeen = timestamp(node.path("last_seen").asText(null));
        if (firstSeen == null) {
            return entity;
        }
        if (lastSeen == null || lastSeen.isBefore(firstSeen)) {
            lastSeen = firstSeen;
        }
        List<SourcedValue> provenance = new ArrayList<>();
        for (SourcedValue.Key key : SourcedValue.keysOf(entity)) {
            provenance.add(
                    new SourcedValue(
                            key.kind(),
                            key.value(),
                            new Provenance(entity.listSource(), null, firstSeen, lastSeen)));
        }
        return entity.withProvenance(provenance);
    }

    private static Instant timestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.DateTimeException e) {
            try {
                return java.time.LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (java.time.DateTimeException e2) {
                return instant(Optional.of(value));
            }
        }
    }

    /** Gives each position held without a role the name of its {@code Position}. */
    private static List<Relation> named(List<Relation> relations, Map<String, String> positions) {
        return relations.stream()
                .map(
                        r ->
                                r.type() == RelationType.POSITION_HELD
                                                && r.role() == null
                                                && positions.containsKey(r.targetId())
                                        ? new Relation(
                                                r.type(),
                                                r.targetId(),
                                                positions.get(r.targetId()),
                                                r.sharePercentage(),
                                                r.startDate(),
                                                r.endDate())
                                        : r)
                .toList();
    }

    private static void addPrograms(
            JsonNode node, ListSource source, List<SanctionsProgram> programs) {
        List<String> ids = values(node, "programId");
        List<String> names = values(node, "program");
        int count = Math.max(ids.size(), names.size());
        for (int i = 0; i < count; i++) {
            String name = i < names.size() ? names.get(i) : null;
            String code = i < ids.size() ? ids.get(i) : name;
            programs.add(new SanctionsProgram(code, name, source));
        }
    }

    private static Optional<Link> link(RelationType type, JsonNode node) {
        String[] ends = FtmWriter.ends(type);
        Optional<String> holder = first(node, ends[0]);
        Optional<String> target = first(node, ends[1]);
        if (holder.isEmpty() || target.isEmpty()) {
            return Optional.empty();
        }
        String role = ends[2] == null ? null : first(node, ends[2]).orElse(null);
        Double share = first(node, "percentage").map(FtmReader::share).orElse(null);
        LocalDate start = first(node, "startDate").map(FtmReader::date).orElse(null);
        LocalDate end = first(node, "endDate").map(FtmReader::date).orElse(null);
        if (start != null && end != null && end.isBefore(start)) {
            end = null;
        }
        return Optional.of(
                new Link(holder.get(), new Relation(type, target.get(), role, share, start, end)));
    }

    private static Optional<RelationType> linkType(String schema) {
        for (RelationType type : RelationType.values()) {
            if (type.schema().equals(schema)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    private static boolean isEntity(String schema) {
        try {
            EntityType.fromSchema(schema);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Optional<RiskTopic> topic(String code) {
        try {
            return Optional.of(RiskTopic.fromString(code));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static NameInfo alias(String name, NameStrength strength) {
        return new NameInfo(name, null, null, null, null, NameType.AKA, strength, ScriptType.LATIN);
    }

    private static List<String> values(JsonNode node, String property) {
        JsonNode array = node.path("properties").path(property);
        List<String> out = new ArrayList<>();
        array.forEach(
                v -> {
                    String text = v.asText().strip();
                    if (!text.isEmpty() && !out.contains(text)) {
                        out.add(text);
                    }
                });
        return out;
    }

    private static Optional<String> first(JsonNode node, String property) {
        List<String> all = values(node, property);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.getFirst());
    }

    private static List<String> upper(List<String> codes) {
        return codes.stream().map(c -> c.toUpperCase(java.util.Locale.ROOT)).toList();
    }

    private static List<LocalDate> dates(List<String> values) {
        return values.stream().map(FtmReader::date).filter(Objects::nonNull).distinct().toList();
    }

    /** Parses a full or partial ISO date; returns {@code null} if it is not a date. */
    static LocalDate date(String value) {
        String v = value.length() > 10 ? value.substring(0, 10) : value;
        try {
            return switch (v.length()) {
                case 4 -> LocalDate.of(Integer.parseInt(v), 1, 1);
                case 7 ->
                        LocalDate.of(
                                Integer.parseInt(v.substring(0, 4)),
                                Integer.parseInt(v.substring(5, 7)),
                                1);
                case 10 -> LocalDate.parse(v);
                default -> null;
            };
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    private static Instant instant(Optional<String> value) {
        return value.map(FtmReader::date)
                .map(d -> d.atStartOfDay().toInstant(ZoneOffset.UTC))
                .orElse(null);
    }

    private static Double share(String value) {
        try {
            double share = Double.parseDouble(value.replace("%", "").strip());
            return share >= 0 && share <= 100 ? share : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Link(String holder, Relation relation) {}
}
