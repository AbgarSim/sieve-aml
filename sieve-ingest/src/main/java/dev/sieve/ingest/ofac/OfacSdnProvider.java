package dev.sieve.ingest.ofac;

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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the OFAC Specially Designated Nationals (SDN) list.
 *
 * <p>Uses StAX (streaming) XML parsing for memory-efficient processing of the potentially large SDN
 * XML file. Supports HTTP ETag-based delta detection to avoid unnecessary re-downloads.
 *
 * @see <a href="https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML">OFAC
 *     SDN XML</a>
 */
public final class OfacSdnProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(OfacSdnProvider.class);

    private static final String DEFAULT_SDN_URL =
            "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final DateTimeFormatter OFAC_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /**
     * Creates a provider with the default OFAC SDN URL.
     */
    public OfacSdnProvider() {
        this(URI.create(DEFAULT_SDN_URL));
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the SDN XML from
     */
    public OfacSdnProvider(URI sourceUri) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
        this.currentMetadata =
                new ListMetadata(ListSource.OFAC_SDN, null, null, null, sourceUri, 0);
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the SDN XML from
     * @param httpClient the HTTP client to use for requests
     */
    public OfacSdnProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.currentMetadata =
                new ListMetadata(ListSource.OFAC_SDN, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.OFAC_SDN;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching OFAC SDN list [uri={}]", sourceUri);
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
                                "OFAC SDN fetch failed [status=%d, uri=%s]",
                                response.statusCode(), sourceUri),
                        ListSource.OFAC_SDN);
            }

            byte[] body = response.body();
            String contentHash = computeSha256(body);
            String etag = response.headers().firstValue("ETag").orElse(null);

            log.info(
                    "OFAC SDN downloaded [bytes={}, etag={}, hash={}]",
                    body.length,
                    etag,
                    contentHash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseXml(body);

            Instant now = Instant.now();
            Duration duration = Duration.between(start, now);
            currentMetadata =
                    new ListMetadata(
                            ListSource.OFAC_SDN, now, etag, contentHash, sourceUri, entities.size());

            log.info(
                    "OFAC SDN ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    duration.toMillis());

            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching OFAC SDN list: " + e.getMessage(),
                    ListSource.OFAC_SDN,
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException(
                    "OFAC SDN fetch interrupted", ListSource.OFAC_SDN, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during OFAC SDN ingestion: " + e.getMessage(),
                    ListSource.OFAC_SDN,
                    e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        if (previousMetadata == null || previousMetadata.etag() == null) {
            return true;
        }

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(sourceUri)
                            .timeout(CONNECT_TIMEOUT)
                            .header("If-None-Match", previousMetadata.etag())
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            boolean notModified = response.statusCode() == 304;
            log.debug(
                    "OFAC SDN update check [status={}, hasUpdates={}]",
                    response.statusCode(),
                    !notModified);
            return !notModified;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to check OFAC SDN for updates, assuming updates exist", e);
            return true;
        }
    }

    /**
     * Parses the OFAC SDN XML content into a list of sanctioned entities using StAX streaming.
     *
     * @param xmlContent the raw XML bytes
     * @return parsed entities
     * @throws ListIngestionException if the XML is malformed or cannot be parsed
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
                if (event == XMLStreamConstants.START_ELEMENT
                        && "sdnEntry".equals(reader.getLocalName())) {
                    SanctionedEntity entity = parseSdnEntry(reader);
                    if (entity != null) {
                        entities.add(entity);
                    }
                }
            }

            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse OFAC SDN XML: " + e.getMessage(), ListSource.OFAC_SDN, e);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "IO error reading OFAC SDN XML: " + e.getMessage(), ListSource.OFAC_SDN, e);
        }

        return entities;
    }

    private SanctionedEntity parseSdnEntry(XMLStreamReader reader) throws XMLStreamException {
        String uid = null;
        String firstName = null;
        String lastName = null;
        String sdnType = null;
        String remarks = null;
        String title = null;
        List<SanctionsProgram> programs = new ArrayList<>();
        List<NameInfo> aliases = new ArrayList<>();
        List<Address> addresses = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<String> citizenships = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> placesOfBirth = new ArrayList<>();

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "uid" -> uid = readText(reader);
                    case "firstName" -> firstName = readText(reader);
                    case "lastName" -> lastName = readText(reader);
                    case "sdnType" -> sdnType = readText(reader);
                    case "remarks" -> remarks = readText(reader);
                    case "title" -> title = readText(reader);
                    case "programList" -> programs = parseProgramList(reader);
                    case "akaList" -> aliases = parseAkaList(reader);
                    case "addressList" -> addresses = parseAddressList(reader);
                    case "idList" -> identifiers = parseIdList(reader);
                    case "nationalityList" -> nationalities = parseNationalityList(reader);
                    case "citizenshipList" -> citizenships = parseCitizenshipList(reader);
                    case "dateOfBirthList" -> datesOfBirth = parseDateOfBirthList(reader);
                    case "placeOfBirthList" -> placesOfBirth = parsePlaceOfBirthList(reader);
                    default -> { /* skip unknown elements */ }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "sdnEntry".equals(reader.getLocalName())) {
                break;
            }
        }

        if (uid == null || lastName == null) {
            log.debug("Skipping SDN entry with missing uid or lastName");
            return null;
        }

        String fullName = buildFullName(firstName, lastName);
        EntityType entityType = mapSdnType(sdnType);
        NameInfo primaryName =
                new NameInfo(
                        fullName,
                        firstName,
                        lastName,
                        null,
                        title,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);

        return new SanctionedEntity(
                uid,
                entityType,
                ListSource.OFAC_SDN,
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
                null,
                Instant.now());
    }

    private List<SanctionsProgram> parseProgramList(XMLStreamReader reader)
            throws XMLStreamException {
        List<SanctionsProgram> programs = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "program".equals(reader.getLocalName())) {
                String programCode = readText(reader);
                if (programCode != null && !programCode.isBlank()) {
                    programs.add(
                            new SanctionsProgram(programCode.strip(), null, ListSource.OFAC_SDN));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "programList".equals(reader.getLocalName())) {
                break;
            }
        }
        return programs;
    }

    private List<NameInfo> parseAkaList(XMLStreamReader reader) throws XMLStreamException {
        List<NameInfo> aliases = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "aka".equals(reader.getLocalName())) {
                NameInfo alias = parseAka(reader);
                if (alias != null) {
                    aliases.add(alias);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "akaList".equals(reader.getLocalName())) {
                break;
            }
        }
        return aliases;
    }

    private NameInfo parseAka(XMLStreamReader reader) throws XMLStreamException {
        String type = null;
        String category = null;
        String firstName = null;
        String lastName = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "type" -> type = readText(reader);
                    case "category" -> category = readText(reader);
                    case "firstName" -> firstName = readText(reader);
                    case "lastName" -> lastName = readText(reader);
                    default -> { /* skip */ }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "aka".equals(reader.getLocalName())) {
                break;
            }
        }

        if (lastName == null || lastName.isBlank()) {
            return null;
        }

        String fullName = buildFullName(firstName, lastName);
        NameType nameType = mapAkaType(type);
        NameStrength strength = mapAkaStrength(category);

        return new NameInfo(
                fullName, firstName, lastName, null, null, nameType, strength, ScriptType.LATIN);
    }

    private List<Address> parseAddressList(XMLStreamReader reader) throws XMLStreamException {
        List<Address> addresses = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "address".equals(reader.getLocalName())) {
                Address address = parseAddress(reader);
                if (address != null) {
                    addresses.add(address);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "addressList".equals(reader.getLocalName())) {
                break;
            }
        }
        return addresses;
    }

    private Address parseAddress(XMLStreamReader reader) throws XMLStreamException {
        String address1 = null;
        String city = null;
        String stateOrProvince = null;
        String postalCode = null;
        String country = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "address1" -> address1 = readText(reader);
                    case "city" -> city = readText(reader);
                    case "stateOrProvince" -> stateOrProvince = readText(reader);
                    case "postalCode" -> postalCode = readText(reader);
                    case "country" -> country = readText(reader);
                    default -> { /* skip */ }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "address".equals(reader.getLocalName())) {
                break;
            }
        }

        String fullAddress = buildFullAddress(address1, city, stateOrProvince, postalCode, country);
        return new Address(address1, city, stateOrProvince, postalCode, country, fullAddress);
    }

    private List<Identifier> parseIdList(XMLStreamReader reader) throws XMLStreamException {
        List<Identifier> identifiers = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "id".equals(reader.getLocalName())) {
                Identifier identifier = parseId(reader);
                if (identifier != null) {
                    identifiers.add(identifier);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "idList".equals(reader.getLocalName())) {
                break;
            }
        }
        return identifiers;
    }

    private Identifier parseId(XMLStreamReader reader) throws XMLStreamException {
        String idType = null;
        String idNumber = null;
        String idCountry = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elementName = reader.getLocalName();
                switch (elementName) {
                    case "idType" -> idType = readText(reader);
                    case "idNumber" -> idNumber = readText(reader);
                    case "idCountry" -> idCountry = readText(reader);
                    default -> { /* skip */ }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "id".equals(reader.getLocalName())) {
                break;
            }
        }

        if (idNumber == null || idNumber.isBlank()) {
            return null;
        }

        IdentifierType type = mapIdType(idType);
        return new Identifier(type, idNumber.strip(), idCountry, null);
    }

    private List<String> parseNationalityList(XMLStreamReader reader) throws XMLStreamException {
        return parseSimpleItemList(reader, "nationalityList", "nationality", "country");
    }

    private List<String> parseCitizenshipList(XMLStreamReader reader) throws XMLStreamException {
        return parseSimpleItemList(reader, "citizenshipList", "citizenship", "country");
    }

    private List<String> parsePlaceOfBirthList(XMLStreamReader reader) throws XMLStreamException {
        return parseSimpleItemList(reader, "placeOfBirthList", "placeOfBirthItem", "placeOfBirth");
    }

    private List<String> parseSimpleItemList(
            XMLStreamReader reader, String listElement, String itemElement, String valueElement)
            throws XMLStreamException {
        List<String> values = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && itemElement.equals(reader.getLocalName())) {
                String value = parseSimpleItem(reader, itemElement, valueElement);
                if (value != null && !value.isBlank()) {
                    values.add(value.strip());
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && listElement.equals(reader.getLocalName())) {
                break;
            }
        }
        return values;
    }

    private String parseSimpleItem(
            XMLStreamReader reader, String itemElement, String valueElement)
            throws XMLStreamException {
        String value = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && valueElement.equals(reader.getLocalName())) {
                value = readText(reader);
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && itemElement.equals(reader.getLocalName())) {
                break;
            }
        }
        return value;
    }

    private List<LocalDate> parseDateOfBirthList(XMLStreamReader reader)
            throws XMLStreamException {
        List<LocalDate> dates = new ArrayList<>();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "dateOfBirthItem".equals(reader.getLocalName())) {
                LocalDate date = parseDateOfBirthItem(reader);
                if (date != null) {
                    dates.add(date);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "dateOfBirthList".equals(reader.getLocalName())) {
                break;
            }
        }
        return dates;
    }

    private LocalDate parseDateOfBirthItem(XMLStreamReader reader) throws XMLStreamException {
        String dateStr = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && "dateOfBirth".equals(reader.getLocalName())) {
                dateStr = readText(reader);
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "dateOfBirthItem".equals(reader.getLocalName())) {
                break;
            }
        }
        return parseDateSafe(dateStr);
    }

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

    private static String buildFullName(String firstName, String lastName) {
        if (firstName == null || firstName.isBlank()) {
            return lastName;
        }
        return lastName + ", " + firstName;
    }

    private static String buildFullAddress(
            String street, String city, String state, String postalCode, String country) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, street);
        appendIfPresent(sb, city);
        appendIfPresent(sb, state);
        appendIfPresent(sb, postalCode);
        appendIfPresent(sb, country);
        return sb.isEmpty() ? null : sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(value.strip());
        }
    }

    private static EntityType mapSdnType(String sdnType) {
        if (sdnType == null) {
            return EntityType.INDIVIDUAL;
        }
        return switch (sdnType.strip().toLowerCase()) {
            case "individual" -> EntityType.INDIVIDUAL;
            case "entity" -> EntityType.ENTITY;
            case "vessel" -> EntityType.VESSEL;
            case "aircraft" -> EntityType.AIRCRAFT;
            default -> EntityType.ENTITY;
        };
    }

    private static NameType mapAkaType(String type) {
        if (type == null) {
            return NameType.AKA;
        }
        return switch (type.strip().toLowerCase()) {
            case "a.k.a.", "aka" -> NameType.AKA;
            case "f.k.a.", "fka" -> NameType.FKA;
            default -> NameType.AKA;
        };
    }

    private static NameStrength mapAkaStrength(String category) {
        if (category == null) {
            return NameStrength.WEAK;
        }
        return switch (category.strip().toLowerCase()) {
            case "strong" -> NameStrength.STRONG;
            case "weak" -> NameStrength.WEAK;
            default -> NameStrength.WEAK;
        };
    }

    @SuppressWarnings("PatternValidation") // identifier type mapping is best-effort
    private static IdentifierType mapIdType(String idType) {
        if (idType == null) {
            return IdentifierType.OTHER;
        }
        String normalized = idType.strip().toLowerCase();
        if (normalized.contains("passport")) {
            return IdentifierType.PASSPORT;
        } else if (normalized.contains("national") || normalized.contains("cedula")) {
            return IdentifierType.NATIONAL_ID;
        } else if (normalized.contains("tax") || normalized.contains("ssn")) {
            return IdentifierType.TAX_ID;
        } else if (normalized.contains("imo")) {
            return IdentifierType.IMO_NUMBER;
        } else if (normalized.contains("mmsi")) {
            return IdentifierType.MMSI;
        } else if (normalized.contains("swift") || normalized.contains("bic")) {
            return IdentifierType.SWIFT_BIC;
        } else if (normalized.contains("registration")) {
            return IdentifierType.REGISTRATION_NUMBER;
        }
        return IdentifierType.OTHER;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.strip(), OFAC_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(dateStr.strip());
            } catch (DateTimeParseException e2) {
                log.debug("Unable to parse date of birth [value={}]", dateStr);
                return null;
            }
        }
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
