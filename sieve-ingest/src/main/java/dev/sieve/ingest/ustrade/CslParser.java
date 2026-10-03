package dev.sieve.ingest.ustrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;

/**
 * Parses the downloadable Consolidated Screening List JSON, keeping the entries whose {@code
 * source} field passes a filter. The CSL bundles several U.S. lists, so one parser serves both the
 * whole feed and the single lists split out of it.
 */
final class CslParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ListSource source;
    private final String idPrefix;
    private final Predicate<String> sourceFilter;
    private final EntityType defaultType;
    private final RiskTopic topic;

    /**
     * Creates a parser.
     *
     * @param source the list the parsed entities belong to
     * @param idPrefix prefix for entity ids, such as {@code "csl-"}
     * @param sourceFilter keeps an entry when it accepts the entry's {@code source} value, which
     *     may be {@code null}
     * @param defaultType the type for entries without a {@code type} field; the BIS lists never set
     *     one
     * @param topic the risk topic every parsed entity carries
     */
    CslParser(
            ListSource source,
            String idPrefix,
            Predicate<String> sourceFilter,
            EntityType defaultType,
            RiskTopic topic) {
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.idPrefix = Objects.requireNonNull(idPrefix, "idPrefix must not be null");
        this.sourceFilter = Objects.requireNonNull(sourceFilter, "sourceFilter must not be null");
        this.defaultType = Objects.requireNonNull(defaultType, "defaultType must not be null");
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
    }

    @SuppressWarnings("unchecked")
    List<SanctionedEntity> parse(byte[] responseBody) throws ListIngestionException {
        String name = source.displayName();
        try {
            Map<String, Object> root = MAPPER.readValue(responseBody, Map.class);
            // The downloadable JSON has "results" at top level
            Object resultsObj = root.get("results");
            if (resultsObj == null) {
                resultsObj = root.get("sources");
            }
            if (!(resultsObj instanceof List)) {
                throw new ListIngestionException(
                        name + ": missing 'results' array in response", source);
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;
            List<SanctionedEntity> entities = new ArrayList<>();
            for (Map<String, Object> entry : results) {
                if (!sourceFilter.test(stringVal(entry, "source"))) {
                    continue;
                }
                SanctionedEntity entity = parseEntry(entry);
                if (entity != null) {
                    entities.add(entity);
                }
            }
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse " + name + " JSON: " + e.getMessage(), source, e);
        }
    }

    @SuppressWarnings("unchecked")
    private SanctionedEntity parseEntry(Map<String, Object> entry) {
        String id = stringVal(entry, "id");
        if (id == null) {
            id = stringVal(entry, "entity_number");
        }
        if (id == null) return null;

        String name = stringVal(entry, "name");
        if (name == null || name.isBlank()) return null;

        String typeStr = stringVal(entry, "type");
        EntityType entityType = typeStr == null ? defaultType : mapEntityType(typeStr);

        // Build primary name
        NameInfo primaryName =
                new NameInfo(name, null, null, null, null, NameType.PRIMARY, null, null);

        // Aliases
        List<NameInfo> aliases = new ArrayList<>();
        Object altNames = entry.get("alt_names");
        if (altNames instanceof List) {
            for (Object alt : (List<Object>) altNames) {
                if (alt instanceof String altStr && !altStr.isBlank()) {
                    aliases.add(
                            new NameInfo(altStr, null, null, null, null, NameType.AKA, null, null));
                }
            }
        } else if (altNames instanceof String altStr && !altStr.isBlank()) {
            for (String alt : altStr.split(";")) {
                String trimmed = alt.strip();
                if (!trimmed.isEmpty()) {
                    aliases.add(
                            new NameInfo(
                                    trimmed, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        // Addresses
        List<Address> addresses = new ArrayList<>();
        Object addrObj = entry.get("addresses");
        if (addrObj instanceof List) {
            for (Object a : (List<Object>) addrObj) {
                if (a instanceof Map) {
                    Map<String, Object> am = (Map<String, Object>) a;
                    addresses.add(
                            new Address(
                                    stringVal(am, "address"),
                                    stringVal(am, "city"),
                                    stringVal(am, "state"),
                                    stringVal(am, "postal_code"),
                                    stringVal(am, "country"),
                                    stringVal(am, "address")));
                }
            }
        }

        // Identifiers
        List<Identifier> identifiers = new ArrayList<>();
        Object idsObj = entry.get("ids");
        if (idsObj instanceof List) {
            for (Object idObj : (List<Object>) idsObj) {
                if (idObj instanceof Map) {
                    Map<String, Object> idMap = (Map<String, Object>) idObj;
                    String idType = stringVal(idMap, "type");
                    String idNum = stringVal(idMap, "number");
                    String country = stringVal(idMap, "country");
                    if (idNum != null && !idNum.isBlank()) {
                        identifiers.add(new Identifier(mapIdType(idType), idNum, country, null));
                    }
                }
            }
        }

        // Nationalities
        List<String> nationalities = new ArrayList<>();
        Object natObj = entry.get("nationalities");
        if (natObj instanceof String natStr && !natStr.isBlank()) {
            for (String n : natStr.split(";")) {
                String trimmed = n.strip();
                if (!trimmed.isEmpty()) nationalities.add(trimmed);
            }
        }

        // Programs
        List<SanctionsProgram> programs = new ArrayList<>();
        Object progsObj = entry.get("programs");
        if (progsObj instanceof List) {
            for (Object p : (List<Object>) progsObj) {
                if (p instanceof String ps && !ps.isBlank()) {
                    programs.add(new SanctionsProgram(ps.strip(), null, source));
                }
            }
        }
        String sourceListName = stringVal(entry, "source");
        if (sourceListName != null && !sourceListName.isBlank()) {
            programs.add(new SanctionsProgram(sourceListName.strip(), sourceListName, source));
        }

        // Dates of birth
        List<LocalDate> datesOfBirth = new ArrayList<>();
        String dobStr = stringVal(entry, "dates_of_birth");
        if (dobStr != null && !dobStr.isBlank()) {
            for (String d : dobStr.split(";")) {
                LocalDate dob = parseDateSafe(d.strip());
                if (dob != null) datesOfBirth.add(dob);
            }
        }

        // Places of birth
        List<String> placesOfBirth = new ArrayList<>();
        String pobStr = stringVal(entry, "places_of_birth");
        if (pobStr != null && !pobStr.isBlank()) {
            for (String p : pobStr.split(";")) {
                String trimmed = p.strip();
                if (!trimmed.isEmpty()) placesOfBirth.add(trimmed);
            }
        }

        String remarks = remarks(entry);
        LocalDate startDate = parseDateSafe(stringVal(entry, "start_date"));
        Instant listedDate =
                startDate == null ? null : startDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        return new SanctionedEntity(
                idPrefix + id,
                entityType,
                source,
                primaryName,
                aliases,
                addresses,
                identifiers,
                nationalities,
                List.of(),
                datesOfBirth,
                placesOfBirth,
                remarks,
                programs,
                listedDate,
                Instant.now(),
                Set.of(topic),
                List.of());
    }

    /**
     * Joins the free-text remarks with the export-control fields that BIS lists carry, so the
     * licence terms and the Federal Register notice behind a listing stay with the entity.
     */
    private static String remarks(Map<String, Object> entry) {
        StringJoiner joiner = new StringJoiner("\n");
        addField(joiner, null, stringVal(entry, "remarks"));
        addField(joiner, "License requirement", stringVal(entry, "license_requirement"));
        addField(joiner, "License policy", stringVal(entry, "license_policy"));
        addField(joiner, "Federal Register notice", stringVal(entry, "federal_register_notice"));
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private static void addField(StringJoiner joiner, String label, String value) {
        if (value != null) {
            joiner.add(label == null ? value.strip() : label + ": " + value.strip());
        }
    }

    static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    private static EntityType mapEntityType(String type) {
        if (type == null) return EntityType.INDIVIDUAL;
        return switch (type.strip().toLowerCase()) {
            case "individual" -> EntityType.INDIVIDUAL;
            case "entity" -> EntityType.ENTITY;
            case "vessel" -> EntityType.VESSEL;
            case "aircraft" -> EntityType.AIRCRAFT;
            default -> EntityType.ENTITY;
        };
    }

    private static IdentifierType mapIdType(String idType) {
        if (idType == null) return IdentifierType.OTHER;
        String n = idType.strip().toLowerCase();
        if (n.contains("passport")) return IdentifierType.PASSPORT;
        if (n.contains("national") || n.contains("cedula")) return IdentifierType.NATIONAL_ID;
        if (n.contains("tax") || n.contains("ssn")) return IdentifierType.TAX_ID;
        if (n.contains("imo")) return IdentifierType.IMO_NUMBER;
        if (n.contains("mmsi")) return IdentifierType.MMSI;
        if (n.contains("swift") || n.contains("bic")) return IdentifierType.SWIFT_BIC;
        return IdentifierType.OTHER;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
