package dev.sieve.ingest.un;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.HttpClientFactory;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the UN Security Council Consolidated List of sanctions targets.
 *
 * <p>The XML uses a no-namespace structure with two top-level sections: {@code <INDIVIDUALS>}
 * containing {@code <INDIVIDUAL>} entries, and {@code <ENTITIES>} containing {@code <ENTITY>}
 * entries. Each entry is self-contained with nested child elements for names, aliases, addresses,
 * dates of birth, places of birth, documents, nationalities, and designations. All data is stored
 * as text content within child elements (not in attributes).
 *
 * <p>Uses StAX (streaming) XML parsing for memory-efficient processing.
 *
 * @see <a href="https://scsanctions.un.org/resources/xml/en/consolidated.xml">UN Consolidated
 *     XML</a>
 */
public final class UnConsolidatedProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(UnConsolidatedProvider.class);

    private static final String DEFAULT_URL =
            "https://scsanctions.un.org/resources/xml/en/consolidated.xml";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /** Creates a provider with the default UN consolidated list URL. */
    public UnConsolidatedProvider() {
        this(URI.create(DEFAULT_URL));
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the consolidated list XML from
     */
    public UnConsolidatedProvider(URI sourceUri) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = HttpClientFactory.createTrustAllClient(CONNECT_TIMEOUT);
        this.currentMetadata =
                new ListMetadata(ListSource.UN_CONSOLIDATED, null, null, null, sourceUri, 0);
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the consolidated list XML from
     * @param httpClient the HTTP client to use for requests
     */
    public UnConsolidatedProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.currentMetadata =
                new ListMetadata(ListSource.UN_CONSOLIDATED, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.UN_CONSOLIDATED;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching UN consolidated sanctions list [uri={}]", sourceUri);
        Instant start = Instant.now();

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(sourceUri)
                            .timeout(REQUEST_TIMEOUT)
                            .header("Accept", "application/xml")
                            .GET()
                            .build();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format(
                                "UN consolidated fetch failed [status=%d, uri=%s]",
                                response.statusCode(), sourceUri),
                        ListSource.UN_CONSOLIDATED);
            }

            byte[] body = response.body();
            String contentHash = computeSha256(body);
            String etag = response.headers().firstValue("ETag").orElse(null);

            log.info(
                    "UN consolidated downloaded [bytes={}, etag={}, hash={}]",
                    body.length,
                    etag,
                    contentHash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseXml(body);

            Instant now = Instant.now();
            Duration duration = Duration.between(start, now);
            currentMetadata =
                    new ListMetadata(
                            ListSource.UN_CONSOLIDATED,
                            now,
                            etag,
                            contentHash,
                            sourceUri,
                            entities.size());

            log.info(
                    "UN consolidated ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    duration.toMillis());

            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching UN consolidated list: " + e.getMessage(),
                    ListSource.UN_CONSOLIDATED,
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException(
                    "UN consolidated fetch interrupted", ListSource.UN_CONSOLIDATED, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during UN consolidated ingestion: " + e.getMessage(),
                    ListSource.UN_CONSOLIDATED,
                    e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        if (previousMetadata == null || previousMetadata.contentHash() == null) {
            return true;
        }

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(sourceUri)
                            .timeout(CONNECT_TIMEOUT)
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            String etag = response.headers().firstValue("ETag").orElse(null);
            if (etag != null && etag.equals(previousMetadata.etag())) {
                log.debug("UN consolidated update check [etag unchanged, hasUpdates=false]");
                return false;
            }

            log.debug(
                    "UN consolidated update check [status={}, hasUpdates=true]",
                    response.statusCode());
            return true;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to check UN consolidated for updates, assuming updates exist", e);
            return true;
        }
    }

    // ---- XML parsing -------------------------------------------------------

    /**
     * Parses the UN consolidated list XML into sanctioned entities.
     *
     * <p>The XML has two sections: {@code <INDIVIDUALS>} and {@code <ENTITIES>}. Each {@code
     * <INDIVIDUAL>} or {@code <ENTITY>} element is a self-contained entry with nested child
     * elements for all data fields.
     *
     * @param xmlContent the raw XML bytes
     * @return the parsed entities
     * @throws ListIngestionException if the XML cannot be parsed
     */
    List<SanctionedEntity> parseXml(byte[] xmlContent) throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream input = new ByteArrayInputStream(xmlContent)) {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(input, StandardCharsets.UTF_8.name());

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    if ("INDIVIDUAL".equals(localName)) {
                        SanctionedEntity entity = parseIndividual(reader);
                        if (entity != null) entities.add(entity);
                    } else if ("ENTITY".equals(localName)) {
                        SanctionedEntity entity = parseEntity(reader);
                        if (entity != null) entities.add(entity);
                    }
                }
            }

            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse UN consolidated XML: " + e.getMessage(),
                    ListSource.UN_CONSOLIDATED,
                    e);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "IO error reading UN consolidated XML: " + e.getMessage(),
                    ListSource.UN_CONSOLIDATED,
                    e);
        }

        return entities;
    }

    // ---- INDIVIDUAL parsing ------------------------------------------------

    private SanctionedEntity parseIndividual(XMLStreamReader reader) throws XMLStreamException {
        String dataId = null;
        String firstName = null;
        String secondName = null;
        String thirdName = null;
        String fourthName = null;
        String unListType = null;
        String referenceNumber = null;
        String listedOn = null;
        String gender = null;
        String comments = null;
        List<String> titles = new ArrayList<>();
        List<String> designations = new ArrayList<>();
        Set<String> nationalities = new LinkedHashSet<>();
        List<AliasEntry> aliases = new ArrayList<>();
        List<Address> addresses = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        Set<String> placesOfBirth = new LinkedHashSet<>();
        List<Identifier> identifiers = new ArrayList<>();
        String lastUpdated = null;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "DATAID" -> dataId = readText(reader);
                    case "FIRST_NAME" -> firstName = readText(reader);
                    case "SECOND_NAME" -> secondName = readText(reader);
                    case "THIRD_NAME" -> thirdName = readText(reader);
                    case "FOURTH_NAME" -> fourthName = readText(reader);
                    case "UN_LIST_TYPE" -> unListType = readText(reader);
                    case "REFERENCE_NUMBER" -> referenceNumber = readText(reader);
                    case "LISTED_ON" -> listedOn = readText(reader);
                    case "GENDER" -> gender = readText(reader);
                    case "COMMENTS1" -> comments = readText(reader);
                    case "TITLE" -> collectValues(reader, "TITLE", titles);
                    case "DESIGNATION" -> collectValues(reader, "DESIGNATION", designations);
                    case "NATIONALITY" -> collectValues(reader, "NATIONALITY", nationalities);
                    case "LAST_DAY_UPDATED" -> lastUpdated = readLastValue(reader);
                    case "INDIVIDUAL_ALIAS" -> {
                        AliasEntry alias = parseAlias(reader, "INDIVIDUAL_ALIAS");
                        if (alias != null) aliases.add(alias);
                    }
                    case "INDIVIDUAL_ADDRESS" ->
                            parseAddress(reader, "INDIVIDUAL_ADDRESS", addresses);
                    case "INDIVIDUAL_DATE_OF_BIRTH" -> parseDateOfBirth(reader, datesOfBirth);
                    case "INDIVIDUAL_PLACE_OF_BIRTH" ->
                            parsePlaceOfBirth(reader, "INDIVIDUAL_PLACE_OF_BIRTH", placesOfBirth);
                    case "INDIVIDUAL_DOCUMENT" -> parseDocument(reader, identifiers);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "INDIVIDUAL".equals(reader.getLocalName())) {
                break;
            }
        }

        String fullName = buildFullName(firstName, secondName, thirdName, fourthName);
        if (fullName == null) {
            log.debug("Skipping UN individual with no usable name [dataId={}]", dataId);
            return null;
        }

        String title = titles.isEmpty() ? null : String.join(", ", titles);
        NameInfo primaryName =
                new NameInfo(
                        fullName,
                        strip(firstName),
                        strip(secondName),
                        joinNonBlank(" ", thirdName, fourthName),
                        title,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);

        List<NameInfo> aliasNames =
                aliases.stream()
                        .map(
                                a ->
                                        new NameInfo(
                                                a.name,
                                                null,
                                                null,
                                                null,
                                                null,
                                                a.nameType,
                                                a.strength,
                                                ScriptType.LATIN))
                        .toList();

        String entityId = referenceNumber != null ? referenceNumber : "UN-" + dataId;
        List<SanctionsProgram> programs =
                unListType != null
                        ? List.of(
                                new SanctionsProgram(
                                        unListType, unListType, ListSource.UN_CONSOLIDATED))
                        : List.of();

        Instant listed = parseIsoDate(listedOn);
        Instant updated = parseIsoDate(lastUpdated);

        return new SanctionedEntity(
                entityId,
                EntityType.INDIVIDUAL,
                ListSource.UN_CONSOLIDATED,
                primaryName,
                new ArrayList<>(aliasNames),
                addresses,
                identifiers,
                new ArrayList<>(nationalities),
                new ArrayList<>(nationalities),
                datesOfBirth,
                new ArrayList<>(placesOfBirth),
                comments,
                programs,
                listed,
                updated);
    }

    // ---- ENTITY parsing ----------------------------------------------------

    private SanctionedEntity parseEntity(XMLStreamReader reader) throws XMLStreamException {
        String dataId = null;
        String firstName = null;
        String unListType = null;
        String referenceNumber = null;
        String listedOn = null;
        String comments = null;
        List<AliasEntry> aliases = new ArrayList<>();
        List<Address> addresses = new ArrayList<>();
        String lastUpdated = null;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "DATAID" -> dataId = readText(reader);
                    case "FIRST_NAME" -> firstName = readText(reader);
                    case "UN_LIST_TYPE" -> unListType = readText(reader);
                    case "REFERENCE_NUMBER" -> referenceNumber = readText(reader);
                    case "LISTED_ON" -> listedOn = readText(reader);
                    case "COMMENTS1" -> comments = readText(reader);
                    case "LAST_DAY_UPDATED" -> lastUpdated = readLastValue(reader);
                    case "ENTITY_ALIAS" -> {
                        AliasEntry alias = parseAlias(reader, "ENTITY_ALIAS");
                        if (alias != null) aliases.add(alias);
                    }
                    case "ENTITY_ADDRESS" -> parseAddress(reader, "ENTITY_ADDRESS", addresses);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "ENTITY".equals(reader.getLocalName())) {
                break;
            }
        }

        String fullName = strip(firstName);
        if (fullName == null) {
            log.debug("Skipping UN entity with no name [dataId={}]", dataId);
            return null;
        }

        NameInfo primaryName =
                new NameInfo(
                        fullName,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);

        List<NameInfo> aliasNames =
                aliases.stream()
                        .map(
                                a ->
                                        new NameInfo(
                                                a.name,
                                                null,
                                                null,
                                                null,
                                                null,
                                                a.nameType,
                                                a.strength,
                                                ScriptType.LATIN))
                        .toList();

        String entityId = referenceNumber != null ? referenceNumber : "UN-" + dataId;
        List<SanctionsProgram> programs =
                unListType != null
                        ? List.of(
                                new SanctionsProgram(
                                        unListType, unListType, ListSource.UN_CONSOLIDATED))
                        : List.of();

        Instant listed = parseIsoDate(listedOn);
        Instant updated = parseIsoDate(lastUpdated);

        return new SanctionedEntity(
                entityId,
                EntityType.ENTITY,
                ListSource.UN_CONSOLIDATED,
                primaryName,
                new ArrayList<>(aliasNames),
                addresses,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                comments,
                programs,
                listed,
                updated);
    }

    // ---- Child element parsers ---------------------------------------------

    /** Intermediate holder for an alias entry. */
    static final class AliasEntry {
        final String name;
        final NameType nameType;
        final NameStrength strength;

        AliasEntry(String name, NameType nameType, NameStrength strength) {
            this.name = name;
            this.nameType = nameType;
            this.strength = strength;
        }
    }

    private AliasEntry parseAlias(XMLStreamReader reader, String endTag) throws XMLStreamException {
        String quality = null;
        String aliasName = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "QUALITY" -> quality = readText(reader);
                    case "ALIAS_NAME" -> aliasName = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && endTag.equals(reader.getLocalName())) {
                break;
            }
        }

        if (aliasName == null || aliasName.isBlank()) return null;

        NameType nameType = mapAliasQuality(quality);
        NameStrength strength =
                "Low".equalsIgnoreCase(quality) ? NameStrength.WEAK : NameStrength.STRONG;

        return new AliasEntry(aliasName.strip(), nameType, strength);
    }

    private void parseAddress(XMLStreamReader reader, String endTag, List<Address> addresses)
            throws XMLStreamException {
        String street = null;
        String city = null;
        String stateProvince = null;
        String country = null;
        String zipCode = null;
        String note = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "STREET" -> street = readText(reader);
                    case "CITY" -> city = readText(reader);
                    case "STATE_PROVINCE" -> stateProvince = readText(reader);
                    case "COUNTRY" -> country = readText(reader);
                    case "ZIP_CODE" -> zipCode = readText(reader);
                    case "NOTE" -> note = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && endTag.equals(reader.getLocalName())) {
                break;
            }
        }

        String fullAddress = joinNonBlank(", ", street, city, stateProvince, zipCode, country);
        if (fullAddress != null) {
            addresses.add(
                    new Address(
                            strip(street),
                            strip(city),
                            strip(stateProvince),
                            strip(zipCode),
                            strip(country),
                            fullAddress));
        }
    }

    private void parseDateOfBirth(XMLStreamReader reader, List<LocalDate> datesOfBirth)
            throws XMLStreamException {
        String dateStr = null;
        String yearStr = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "DATE" -> dateStr = readText(reader);
                    case "YEAR" -> yearStr = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "INDIVIDUAL_DATE_OF_BIRTH".equals(reader.getLocalName())) {
                break;
            }
        }

        LocalDate dob = parseBirthDate(dateStr, yearStr);
        if (dob != null && !datesOfBirth.contains(dob)) {
            datesOfBirth.add(dob);
        }
    }

    private void parsePlaceOfBirth(XMLStreamReader reader, String endTag, Set<String> placesOfBirth)
            throws XMLStreamException {
        String city = null;
        String stateProvince = null;
        String country = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "CITY" -> city = readText(reader);
                    case "STATE_PROVINCE" -> stateProvince = readText(reader);
                    case "COUNTRY" -> country = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && endTag.equals(reader.getLocalName())) {
                break;
            }
        }

        String pob = joinNonBlank(", ", city, stateProvince, country);
        if (pob != null) {
            placesOfBirth.add(pob);
        }
    }

    private void parseDocument(XMLStreamReader reader, List<Identifier> identifiers)
            throws XMLStreamException {
        String typeOfDocument = null;
        String number = null;
        String issuingCountry = null;
        String note = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "TYPE_OF_DOCUMENT" -> typeOfDocument = readText(reader);
                    case "NUMBER" -> number = readText(reader);
                    case "ISSUING_COUNTRY" -> issuingCountry = readText(reader);
                    case "NOTE" -> note = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "INDIVIDUAL_DOCUMENT".equals(reader.getLocalName())) {
                break;
            }
        }

        if (number == null || number.isBlank()) return;

        IdentifierType idType = mapDocumentType(typeOfDocument);
        identifiers.add(new Identifier(idType, number.strip(), strip(issuingCountry), strip(note)));
    }

    // ---- Container element parsers -----------------------------------------

    /**
     * Collects {@code <VALUE>} text from a container element (e.g., {@code <TITLE>}, {@code
     * <DESIGNATION>}, {@code <NATIONALITY>}).
     */
    private void collectValues(
            XMLStreamReader reader, String endTag, java.util.Collection<String> target)
            throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "VALUE".equals(reader.getLocalName())) {
                String val = readText(reader);
                if (val != null && !val.isBlank()) {
                    target.add(val.strip());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && endTag.equals(reader.getLocalName())) {
                break;
            }
        }
    }

    /**
     * Reads the last {@code <VALUE>} from a container element like {@code <LAST_DAY_UPDATED>} which
     * may contain multiple dated values.
     */
    private String readLastValue(XMLStreamReader reader) throws XMLStreamException {
        String last = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "VALUE".equals(reader.getLocalName())) {
                String val = readText(reader);
                if (val != null && !val.isBlank()) {
                    last = val.strip();
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "LAST_DAY_UPDATED".equals(reader.getLocalName())) {
                break;
            }
        }
        return last;
    }

    // ---- Mapping helpers ---------------------------------------------------

    private static NameType mapAliasQuality(String quality) {
        if (quality == null) return NameType.AKA;
        return switch (quality.strip().toLowerCase()) {
            case "good", "a.k.a.", "low" -> NameType.AKA;
            case "f.k.a." -> NameType.FKA;
            default -> NameType.AKA;
        };
    }

    private static IdentifierType mapDocumentType(String type) {
        if (type == null) return IdentifierType.OTHER;
        String lower = type.strip().toLowerCase();
        if (lower.contains("passport")
                || lower.contains("pasaporte")
                || lower.contains("passeport")) {
            return IdentifierType.PASSPORT;
        }
        if (lower.contains("national identification")) {
            return IdentifierType.NATIONAL_ID;
        }
        return IdentifierType.OTHER;
    }

    // ---- Text / date utilities ---------------------------------------------

    private String readText(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        String text = sb.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static String buildFullName(
            String firstName, String secondName, String thirdName, String fourthName) {
        String given = strip(firstName);
        String family = strip(secondName);
        String extra = joinNonBlank(" ", thirdName, fourthName);

        if (family == null && given == null) return null;

        StringBuilder sb = new StringBuilder();
        if (family != null) sb.append(family);
        if (given != null) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(given);
        }
        if (extra != null) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(extra);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String joinNonBlank(String delimiter, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) sb.append(delimiter);
                sb.append(part.strip());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static LocalDate parseBirthDate(String dateStr, String yearStr) {
        if (dateStr != null && !dateStr.isBlank()) {
            try {
                return LocalDate.parse(dateStr.strip());
            } catch (DateTimeParseException ignored) {
            }
        }
        if (yearStr != null && !yearStr.isBlank()) {
            try {
                int year = Integer.parseInt(yearStr.strip());
                if (year > 0) return LocalDate.of(year, 1, 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static Instant parseIsoDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr.strip()).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.debug("Unable to parse UN date [value={}]", dateStr);
            return null;
        }
    }

    private static String strip(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }
}
