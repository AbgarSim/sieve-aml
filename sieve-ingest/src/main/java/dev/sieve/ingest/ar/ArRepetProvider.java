package dev.sieve.ingest.ar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.AbstractListProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Fetches Argentina's Public Registry of Persons and Entities linked to Terrorism (RePET), kept by
 * the Ministry of Justice.
 *
 * <p>The registry holds the UN Security Council's Al-Qaida and Taliban designations plus
 * Argentina's own listings, such as the people charged over the 1994 AMIA bombing and people under
 * an INTERPOL red notice, who are also tagged {@link RiskTopic#WANTED}. It is published as two JSON
 * files in the UN list's field layout, one for people and one for entities. The people file is the
 * main download; the entities file is read first, while the request is built, and both are parsed
 * together.
 *
 * @see <a href="https://repet.jus.gob.ar/">RePET</a>
 */
public final class ArRepetProvider extends AbstractListProvider {

    private static final String DEFAULT_URL = "https://repet.jus.gob.ar/xml/";
    private static final String PERSONS_FILE = "personas.json";
    private static final String ENTITIES_FILE = "entidades.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** The entities file, downloaded while the request for the people file is built. */
    private volatile byte[] entitiesBody;

    /** Creates a provider that reads the registry from its official site. */
    public ArRepetProvider() {
        super(ListSource.AR_REPET, URI.create(DEFAULT_URL), "application/json");
    }

    /**
     * Creates a provider with a custom base URI and HTTP client (for testing).
     *
     * @param baseUri the directory that holds {@code personas.json} and {@code entidades.json}
     * @param httpClient the HTTP client to use for requests
     */
    public ArRepetProvider(URI baseUri, HttpClient httpClient) {
        super(ListSource.AR_REPET, baseUri, "application/json", httpClient, Duration.ofSeconds(60));
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        HttpRequest entitiesRequest =
                HttpRequest.newBuilder()
                        .uri(sourceUri().resolve(ENTITIES_FILE))
                        .timeout(Duration.ofSeconds(60))
                        .header("Accept", "application/json")
                        .header("User-Agent", "sieve-aml/1.0")
                        .GET()
                        .build();
        HttpResponse<byte[]> response =
                client.send(entitiesRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(
                    "Argentina RePET entities download failed [status="
                            + response.statusCode()
                            + "]");
        }
        entitiesBody = response.body();
        return builder.uri(sourceUri().resolve(PERSONS_FILE)).GET().build();
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        byte[] entities = entitiesBody;
        if (entities == null) {
            throw new ListIngestionException(
                    "Argentina RePET entities file was not downloaded", ListSource.AR_REPET);
        }
        return parse(responseBody, entities);
    }

    /**
     * Parses the people and entities files.
     *
     * @param personsBody the {@code personas.json} content
     * @param entitiesBody the {@code entidades.json} content
     * @return every listed person and entity
     * @throws ListIngestionException if either file is not the expected JSON array
     */
    List<SanctionedEntity> parse(byte[] personsBody, byte[] entitiesBody)
            throws ListIngestionException {
        List<SanctionedEntity> result = new ArrayList<>();
        for (JsonNode node : readArray(personsBody, PERSONS_FILE)) {
            addIfListed(result, node, EntityType.INDIVIDUAL);
        }
        for (JsonNode node : readArray(entitiesBody, ENTITIES_FILE)) {
            addIfListed(result, node, EntityType.ENTITY);
        }
        return result;
    }

    private static JsonNode readArray(byte[] body, String file) throws ListIngestionException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Failed to parse Argentina RePET " + file + ": " + e.getMessage(),
                    ListSource.AR_REPET,
                    e);
        }
        if (root == null || !root.isArray()) {
            throw new ListIngestionException(
                    "Argentina RePET " + file + " is not a JSON array", ListSource.AR_REPET);
        }
        return root;
    }

    private static void addIfListed(List<SanctionedEntity> result, JsonNode node, EntityType type) {
        String dataId = text(node, "DATAID");
        String name =
                join(
                        " ",
                        text(node, "FIRST_NAME"),
                        text(node, "SECOND_NAME"),
                        text(node, "THIRD_NAME"),
                        text(node, "FOURTH_NAME"));
        if (dataId == null || name == null || text(node, "DELISTED_ON") != null) {
            return;
        }
        boolean person = type == EntityType.INDIVIDUAL;
        String prefix = person ? "INDIVIDUAL_" : "ENTITY_";

        NameInfo primaryName =
                person
                        ? new NameInfo(
                                name,
                                text(node, "FIRST_NAME"),
                                text(node, "SECOND_NAME"),
                                join(" ", text(node, "THIRD_NAME"), text(node, "FOURTH_NAME")),
                                null,
                                NameType.PRIMARY,
                                NameStrength.STRONG,
                                ScriptType.LATIN)
                        : new NameInfo(
                                name,
                                null,
                                null,
                                null,
                                null,
                                NameType.PRIMARY,
                                NameStrength.STRONG,
                                ScriptType.LATIN);

        List<NameInfo> aliases = new ArrayList<>();
        for (JsonNode alias : node.path(prefix + "ALIAS")) {
            String aliasName = text(alias, "ALIAS_NAME");
            if (aliasName != null) {
                String quality = text(alias, "QUALITY");
                aliases.add(
                        new NameInfo(
                                aliasName,
                                null,
                                null,
                                null,
                                null,
                                "f.k.a.".equalsIgnoreCase(quality) ? NameType.FKA : NameType.AKA,
                                "Low".equalsIgnoreCase(quality)
                                        ? NameStrength.WEAK
                                        : NameStrength.STRONG,
                                ScriptType.LATIN));
            }
        }
        String originalScript = text(node, "NAME_ORIGINAL_SCRIPT");
        if (originalScript != null) {
            aliases.add(
                    new NameInfo(
                            originalScript,
                            null,
                            null,
                            null,
                            null,
                            NameType.AKA,
                            NameStrength.STRONG,
                            null));
        }

        List<Address> addresses = new ArrayList<>();
        for (JsonNode a : node.path(prefix + "ADDRESS")) {
            String street = text(a, "STREET");
            String city = text(a, "CITY");
            String state = text(a, "STATE_PROVINCE");
            String zip = text(a, "ZIP_CODE");
            String country = text(a, "COUNTRY");
            String full = join(", ", street, city, state, zip, country);
            if (full != null) {
                addresses.add(new Address(street, city, state, zip, country, full));
            }
        }

        List<Identifier> identifiers = new ArrayList<>();
        for (JsonNode doc : node.path("INDIVIDUAL_DOCUMENT")) {
            String number = text(doc, "NUMBER");
            if (number != null) {
                String country = text(doc, "ISSUING_COUNTRY");
                identifiers.add(
                        new Identifier(
                                documentType(text(doc, "TYPE_OF_DOCUMENT")),
                                number,
                                country != null ? country : text(doc, "COUNTRY_OF_ISSUE"),
                                text(doc, "NOTE")));
            }
        }

        List<String> nationalities = new ArrayList<>();
        for (JsonNode n : node.path("NATIONALITY")) {
            String value = text(n, "VALUE");
            if (value != null) {
                nationalities.add(value);
            }
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        for (JsonNode d : node.path("INDIVIDUAL_DATE_OF_BIRTH")) {
            LocalDate date = date(text(d, "DATE"));
            if (date != null) {
                datesOfBirth.add(date);
            }
        }

        List<String> placesOfBirth = new ArrayList<>();
        for (JsonNode p : node.path("INDIVIDUAL_PLACE_OF_BIRTH")) {
            String place =
                    join(", ", text(p, "CITY"), text(p, "STATE_PROVINCE"), text(p, "COUNTRY"));
            if (place != null) {
                placesOfBirth.add(place);
            }
        }

        List<SanctionsProgram> programs = new ArrayList<>();
        String program = text(node, "UN_LIST_TYPE");
        if (program != null) {
            programs.add(
                    new SanctionsProgram(program, text(node, "LIST_TYPE"), ListSource.AR_REPET));
        }
        String reference = text(node, "REFERENCE_NUMBER");
        if (reference != null) {
            identifiers.add(new Identifier(IdentifierType.OTHER, reference, null, "UN reference"));
        }

        LocalDate listedOn = date(text(node, "LISTED_ON"));
        LocalDate updatedOn = null;
        for (JsonNode u : node.path("LAST_DAY_UPDATED")) {
            LocalDate d = date(text(u, "VALUE"));
            if (d != null && (updatedOn == null || d.isAfter(updatedOn))) {
                updatedOn = d;
            }
        }

        result.add(
                new SanctionedEntity(
                        (person ? "ar-person-" : "ar-entity-") + dataId,
                        type,
                        ListSource.AR_REPET,
                        primaryName,
                        aliases,
                        addresses,
                        identifiers,
                        nationalities,
                        List.of(),
                        datesOfBirth,
                        placesOfBirth,
                        text(node, "COMMENTS1"),
                        programs,
                        instant(listedOn),
                        updatedOn != null ? instant(updatedOn) : instant(listedOn),
                        topics(program),
                        List.of()));
    }

    /**
     * Every entry is a terrorism listing; Argentina also lists people under an INTERPOL red notice
     * ("Notificación Roja de INTERPOL"), who are wanted as well.
     */
    private static Set<RiskTopic> topics(String program) {
        if (program != null && program.toUpperCase(Locale.ROOT).contains("INTERPOL")) {
            return EnumSet.of(RiskTopic.SANCTION, RiskTopic.WANTED);
        }
        return Set.of(RiskTopic.SANCTION);
    }

    private static IdentifierType documentType(String type) {
        if (type == null) {
            return IdentifierType.OTHER;
        }
        String lower = type.toLowerCase();
        if (lower.contains("passport") || lower.contains("pasaporte")) {
            return IdentifierType.PASSPORT;
        }
        if (lower.contains("national identification") || lower.contains("documento nacional")) {
            return IdentifierType.NATIONAL_ID;
        }
        return IdentifierType.OTHER;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String s = value.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private static String join(String separator, String... parts) {
        StringJoiner joiner = new StringJoiner(separator);
        for (String part : parts) {
            if (part != null) {
                joiner.add(part);
            }
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
