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
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches and parses the U.S. Trade Consolidated Screening List (CSL) via api.trade.gov.
 *
 * <p>This single JSON API bundles ~12 US export-control and sanctions lists including SDN, Entity
 * List, DPL, MEU, UVL, AECA debarred, ISN, and more. Typically contains ~23,000+ entities.
 *
 * <p>Uses streaming JSON parsing to handle the large response efficiently.
 *
 * @see <a href="https://api.trade.gov/gateway/v2/consolidated_screening_list/search">US Trade CSL
 *     API</a>
 */
public final class UsTradeCslProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://data.trade.gov/downloadable_consolidated_screening_list/v1/consolidated.json";

    /** Creates a provider with the default US Trade CSL API URL. */
    public UsTradeCslProvider() {
        super(ListSource.US_TRADE_CSL, URI.create(DEFAULT_URL), "application/json");
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the CSL JSON from
     */
    public UsTradeCslProvider(URI sourceUri) {
        super(ListSource.US_TRADE_CSL, sourceUri, "application/json");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the CSL JSON from
     * @param httpClient the HTTP client to use for requests
     */
    public UsTradeCslProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.US_TRADE_CSL,
                sourceUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(120));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        try {
            // Use built-in JSON parsing via javax.json or simple map parsing
            Object parsed = parseJson(responseBody);
            if (!(parsed instanceof Map)) {
                throw new ListIngestionException(
                        "US Trade CSL: unexpected JSON root type", ListSource.US_TRADE_CSL);
            }

            Map<String, Object> root = (Map<String, Object>) parsed;
            // The downloadable JSON has "results" at top level
            Object resultsObj = root.get("results");
            if (resultsObj == null) {
                // Try direct list format
                resultsObj = root.get("sources");
            }
            if (!(resultsObj instanceof List)) {
                // Fallback: if the root itself is a list-like structure, try parsing entries
                // directly
                throw new ListIngestionException(
                        "US Trade CSL: missing 'results' array in response",
                        ListSource.US_TRADE_CSL);
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) resultsObj;
            List<SanctionedEntity> entities = new ArrayList<>(results.size());

            for (Map<String, Object> entry : results) {
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
                    "Failed to parse US Trade CSL JSON: " + e.getMessage(),
                    ListSource.US_TRADE_CSL,
                    e);
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
        EntityType entityType = mapEntityType(typeStr);

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
                    programs.add(new SanctionsProgram(ps.strip(), null, ListSource.US_TRADE_CSL));
                }
            }
        }
        String sourceListName = stringVal(entry, "source");
        if (sourceListName != null && !sourceListName.isBlank()) {
            programs.add(
                    new SanctionsProgram(
                            sourceListName.strip(), sourceListName, ListSource.US_TRADE_CSL));
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

        String remarks = stringVal(entry, "remarks");

        return new SanctionedEntity(
                "csl-" + id,
                entityType,
                ListSource.US_TRADE_CSL,
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
                null,
                Instant.now());
    }

    private static String stringVal(Map<String, Object> map, String key) {
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(byte[] data) throws Exception {
        return MAPPER.readValue(data, Map.class);
    }
}
