package dev.sieve.ingest.eu;

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
 * Fetches and parses the EU Consolidated List of sanctions targets.
 *
 * <p>The XML is hierarchical: each {@code <sanctionEntity>} element contains nested child elements
 * for names ({@code <nameAlias>}), addresses ({@code <address>}), identifications ({@code
 * <identification>}), birthdates ({@code <birthdate>}), citizenships ({@code <citizenship>}),
 * regulations ({@code <regulation>}), and subject type ({@code <subjectType>}). Most data is stored
 * in XML attributes rather than text content.
 *
 * <p>Uses StAX (streaming) XML parsing for memory-efficient processing.
 *
 * @see <a
 *     href="https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw">EU
 *     Consolidated XML</a>
 */
public final class EuConsolidatedProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(EuConsolidatedProvider.class);

    private static final String DEFAULT_URL =
            "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /** Creates a provider with the default EU consolidated list URL. */
    public EuConsolidatedProvider() {
        this(URI.create(DEFAULT_URL));
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the consolidated list XML from
     */
    public EuConsolidatedProvider(URI sourceUri) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
        this.currentMetadata =
                new ListMetadata(ListSource.EU_CONSOLIDATED, null, null, null, sourceUri, 0);
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the consolidated list XML from
     * @param httpClient the HTTP client to use for requests
     */
    public EuConsolidatedProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.currentMetadata =
                new ListMetadata(ListSource.EU_CONSOLIDATED, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.EU_CONSOLIDATED;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching EU consolidated sanctions list [uri={}]", sourceUri);
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
                                "EU consolidated fetch failed [status=%d, uri=%s]",
                                response.statusCode(), sourceUri),
                        ListSource.EU_CONSOLIDATED);
            }

            byte[] body = response.body();
            String contentHash = computeSha256(body);
            String etag = response.headers().firstValue("ETag").orElse(null);

            log.info(
                    "EU consolidated downloaded [bytes={}, etag={}, hash={}]",
                    body.length,
                    etag,
                    contentHash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseXml(body);

            Instant now = Instant.now();
            Duration duration = Duration.between(start, now);
            currentMetadata =
                    new ListMetadata(
                            ListSource.EU_CONSOLIDATED,
                            now,
                            etag,
                            contentHash,
                            sourceUri,
                            entities.size());

            log.info(
                    "EU consolidated ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    duration.toMillis());

            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching EU consolidated list: " + e.getMessage(),
                    ListSource.EU_CONSOLIDATED,
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException(
                    "EU consolidated fetch interrupted", ListSource.EU_CONSOLIDATED, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during EU consolidated ingestion: " + e.getMessage(),
                    ListSource.EU_CONSOLIDATED,
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
                log.debug("EU consolidated update check [etag unchanged, hasUpdates=false]");
                return false;
            }

            log.debug(
                    "EU consolidated update check [status={}, hasUpdates=true]",
                    response.statusCode());
            return true;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to check EU consolidated for updates, assuming updates exist", e);
            return true;
        }
    }

    // ---- XML parsing -------------------------------------------------------

    /**
     * Parses the EU consolidated list XML into sanctioned entities.
     *
     * <p>Each {@code <sanctionEntity>} is a self-contained entity with nested child elements for
     * names, addresses, identifications, birthdates, citizenships, and regulations. The first
     * English-language {@code <nameAlias>} becomes the primary name; others become aliases.
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
                if (event == XMLStreamConstants.START_ELEMENT
                        && "sanctionEntity".equals(reader.getLocalName())) {
                    SanctionedEntity entity = parseSanctionEntity(reader);
                    if (entity != null) {
                        entities.add(entity);
                    }
                }
            }

            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse EU consolidated XML: " + e.getMessage(),
                    ListSource.EU_CONSOLIDATED,
                    e);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "IO error reading EU consolidated XML: " + e.getMessage(),
                    ListSource.EU_CONSOLIDATED,
                    e);
        }

        return entities;
    }

    // ---- Entity parsing ----------------------------------------------------

    private SanctionedEntity parseSanctionEntity(XMLStreamReader reader) throws XMLStreamException {
        String euRefNumber = attr(reader, "euReferenceNumber");
        String logicalId = attr(reader, "logicalId");
        String entityId =
                (euRefNumber != null && !euRefNumber.isBlank()) ? euRefNumber : "EU-" + logicalId;

        EntityParseContext ctx = new EntityParseContext();
        parseEntityElements(reader, ctx);
        return assembleEntity(entityId, ctx);
    }

    /** Mutable accumulator for data collected while parsing a {@code <sanctionEntity>}. */
    private static final class EntityParseContext {
        EntityType entityType = EntityType.ENTITY;
        final List<NameAlias> nameAliases = new ArrayList<>();
        final List<Address> addresses = new ArrayList<>();
        final List<Identifier> identifiers = new ArrayList<>();
        final Set<String> citizenships = new LinkedHashSet<>();
        final List<LocalDate> datesOfBirth = new ArrayList<>();
        final Set<String> placesOfBirth = new LinkedHashSet<>();
        final Set<String> programs = new LinkedHashSet<>();
        final StringBuilder remarks = new StringBuilder();
        Instant entryIntoForceDate = null;
    }

    private void parseEntityElements(XMLStreamReader reader, EntityParseContext ctx)
            throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                handleEntityChild(reader, ctx);
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "sanctionEntity".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private void handleEntityChild(XMLStreamReader reader, EntityParseContext ctx)
            throws XMLStreamException {
        String el = reader.getLocalName();
        switch (el) {
            case "subjectType" -> ctx.entityType = parseSubjectType(reader);
            case "nameAlias" -> addIfNonNull(ctx.nameAliases, parseNameAlias(reader));
            case "address" -> addIfNonNull(ctx.addresses, parseAddress(reader));
            case "identification" -> addIfNonNull(ctx.identifiers, parseIdentification(reader));
            case "citizenship" -> parseCitizenship(reader, ctx.citizenships);
            case "birthdate" -> parseBirthdate(reader, ctx.datesOfBirth, ctx.placesOfBirth);
            case "regulation" ->
                    ctx.entryIntoForceDate =
                            parseRegulation(reader, ctx.programs, ctx.entryIntoForceDate);
            case "remark" -> appendRemark(reader, ctx.remarks);
            default -> {
                /* skip */
            }
        }
    }

    private static <T> void addIfNonNull(List<T> list, T item) {
        if (item != null) {
            list.add(item);
        }
    }

    private void parseCitizenship(XMLStreamReader reader, Set<String> citizenships) {
        String country = attr(reader, "countryDescription");
        String code = attr(reader, "countryIso2Code");
        if (country != null && !country.isBlank() && !"UNKNOWN".equalsIgnoreCase(country)) {
            citizenships.add(country);
        } else if (code != null && !code.isBlank() && !"00".equals(code)) {
            citizenships.add(code);
        }
    }

    private Instant parseRegulation(
            XMLStreamReader reader, Set<String> programs, Instant currentEarliest) {
        String programme = attr(reader, "programme");
        if (programme != null && !programme.isBlank()) {
            programs.add(programme);
        }
        String dateStr = attr(reader, "entryIntoForceDate");
        Instant parsed = parseDate(dateStr);
        if (parsed != null && (currentEarliest == null || parsed.isBefore(currentEarliest))) {
            return parsed;
        }
        return currentEarliest;
    }

    private void appendRemark(XMLStreamReader reader, StringBuilder remarks)
            throws XMLStreamException {
        String text = readText(reader);
        if (text != null) {
            if (!remarks.isEmpty()) remarks.append("; ");
            remarks.append(text);
        }
    }

    private SanctionedEntity assembleEntity(String entityId, EntityParseContext ctx) {
        // Select primary name: first English-language alias, or first alias overall
        NameAlias primaryAlias = selectPrimary(ctx.nameAliases);
        if (primaryAlias == null) {
            log.debug("Skipping EU entity with no usable name [id={}]", entityId);
            return null;
        }

        NameInfo primaryName = toNameInfo(primaryAlias, NameType.PRIMARY);
        List<NameInfo> aliases = new ArrayList<>();
        for (NameAlias na : ctx.nameAliases) {
            if (na != primaryAlias) {
                aliases.add(toNameInfo(na, NameType.AKA));
            }
        }

        List<SanctionsProgram> sanctionsPrograms =
                ctx.programs.stream()
                        .map(p -> new SanctionsProgram(p, p, ListSource.EU_CONSOLIDATED))
                        .toList();

        // EU list doesn't have separate nationality field — use citizenships for both
        return new SanctionedEntity(
                entityId,
                ctx.entityType,
                ListSource.EU_CONSOLIDATED,
                primaryName,
                aliases,
                ctx.addresses,
                ctx.identifiers,
                new ArrayList<>(ctx.citizenships),
                new ArrayList<>(ctx.citizenships),
                ctx.datesOfBirth,
                new ArrayList<>(ctx.placesOfBirth),
                ctx.remarks.isEmpty() ? null : ctx.remarks.toString(),
                sanctionsPrograms,
                ctx.entryIntoForceDate,
                null);
    }

    // ---- Child element parsers ---------------------------------------------

    /** Intermediate holder for a {@code <nameAlias>} element's attributes. */
    static final class NameAlias {
        String firstName;
        String middleName;
        String lastName;
        String wholeName;
        String title;
        String gender;
        String nameLanguage;
        boolean strong;
        String regulationLanguage;
    }

    private NameAlias parseNameAlias(XMLStreamReader reader) {
        NameAlias na = new NameAlias();
        na.firstName = attr(reader, "firstName");
        na.middleName = attr(reader, "middleName");
        na.lastName = attr(reader, "lastName");
        na.wholeName = attr(reader, "wholeName");
        na.title = attr(reader, "title");
        na.gender = attr(reader, "gender");
        na.nameLanguage = attr(reader, "nameLanguage");
        na.strong = "true".equalsIgnoreCase(attr(reader, "strong"));
        na.regulationLanguage = attr(reader, "regulationLanguage");

        String fullName = strip(na.wholeName);
        if (fullName == null) {
            fullName = buildFullName(strip(na.firstName), strip(na.lastName), strip(na.middleName));
        }
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        return na;
    }

    private static EntityType parseSubjectType(XMLStreamReader reader) {
        String code = attr(reader, "code");
        if (code == null) return EntityType.ENTITY;
        return switch (code.toLowerCase()) {
            case "person" -> EntityType.INDIVIDUAL;
            case "enterprise" -> EntityType.ENTITY;
            default -> EntityType.ENTITY;
        };
    }

    private static Address parseAddress(XMLStreamReader reader) {
        String street = strip(attr(reader, "street"));
        String city = strip(attr(reader, "city"));
        String zipCode = strip(attr(reader, "zipCode"));
        String region = strip(attr(reader, "region"));
        String place = strip(attr(reader, "place"));
        String countryDesc = strip(attr(reader, "countryDescription"));
        String countryCode = strip(attr(reader, "countryIso2Code"));
        String poBox = strip(attr(reader, "poBox"));

        // Build street from parts
        String streetFull = joinNonBlank(", ", street, poBox != null ? "P.O. Box " + poBox : null);
        String cityFull = joinNonBlank(", ", city, region, place);
        String country =
                (countryDesc != null && !"UNKNOWN".equalsIgnoreCase(countryDesc))
                        ? countryDesc
                        : (countryCode != null && !"00".equals(countryCode) ? countryCode : null);

        String fullAddress = joinNonBlank(", ", streetFull, cityFull, zipCode, country);
        if (fullAddress == null) {
            return null;
        }

        return new Address(streetFull, cityFull, null, zipCode, country, fullAddress);
    }

    private static Identifier parseIdentification(XMLStreamReader reader) {
        String number = strip(attr(reader, "number"));
        if (number == null) return null;

        String typeCode = attr(reader, "identificationTypeCode");
        String countryCode = strip(attr(reader, "countryIso2Code"));
        String country = (countryCode != null && !"00".equals(countryCode)) ? countryCode : null;

        IdentifierType idType = mapIdentificationTypeCode(typeCode);

        return new Identifier(idType, number, country, null);
    }

    private static void parseBirthdate(
            XMLStreamReader reader, List<LocalDate> datesOfBirth, Set<String> placesOfBirth) {
        String dateStr = attr(reader, "birthdate");
        String yearStr = attr(reader, "year");
        String monthStr = attr(reader, "monthOfYear");
        String dayStr = attr(reader, "dayOfMonth");
        String city = strip(attr(reader, "city"));
        String place = strip(attr(reader, "place"));

        // Parse date
        LocalDate dob = parseBirthDate(dateStr, yearStr, monthStr, dayStr);
        if (dob != null && !datesOfBirth.contains(dob)) {
            datesOfBirth.add(dob);
        }

        // Collect place of birth
        String pob = joinNonBlank(", ", city, place);
        if (pob != null) {
            placesOfBirth.add(pob);
        }
    }

    // ---- Name selection ----------------------------------------------------

    /**
     * Selects the primary name from a list of aliases: prefers the first alias with {@code
     * regulationLanguage="en"} and empty or English {@code nameLanguage}.
     */
    private static NameAlias selectPrimary(List<NameAlias> aliases) {
        // First pass: English regulation language + no specific nameLanguage (default/English)
        for (NameAlias na : aliases) {
            if ("en".equalsIgnoreCase(na.regulationLanguage)
                    && (na.nameLanguage == null || na.nameLanguage.isBlank())) {
                return na;
            }
        }
        // Second pass: any English regulation language
        for (NameAlias na : aliases) {
            if ("en".equalsIgnoreCase(na.regulationLanguage)) {
                return na;
            }
        }
        // Fallback: first alias
        return aliases.isEmpty() ? null : aliases.getFirst();
    }

    private static NameInfo toNameInfo(NameAlias na, NameType nameType) {
        String fullName = strip(na.wholeName);
        if (fullName == null) {
            fullName = buildFullName(strip(na.firstName), strip(na.lastName), strip(na.middleName));
        }
        if (fullName == null) {
            return new NameInfo(
                    "UNKNOWN",
                    null,
                    null,
                    null,
                    null,
                    nameType,
                    NameStrength.WEAK,
                    ScriptType.LATIN);
        }

        NameStrength strength = na.strong ? NameStrength.STRONG : NameStrength.WEAK;
        ScriptType script = guessScript(na.nameLanguage);

        return new NameInfo(
                fullName,
                strip(na.firstName),
                strip(na.lastName),
                strip(na.middleName),
                strip(na.title),
                nameType,
                strength,
                script);
    }

    // ---- Mapping helpers ---------------------------------------------------

    private static IdentifierType mapIdentificationTypeCode(String code) {
        if (code == null) return IdentifierType.OTHER;
        return switch (code.toLowerCase()) {
            case "passport" -> IdentifierType.PASSPORT;
            case "id" -> IdentifierType.NATIONAL_ID;
            case "fiscalcode", "taxid" -> IdentifierType.TAX_ID;
            case "regnumber" -> IdentifierType.BUSINESS_REGISTRATION;
            case "imo" -> IdentifierType.IMO_NUMBER;
            case "swiftbic" -> IdentifierType.SWIFT_BIC;
            case "ssn" -> IdentifierType.TAX_ID;
            default -> IdentifierType.OTHER;
        };
    }

    private static ScriptType guessScript(String nameLanguage) {
        if (nameLanguage == null || nameLanguage.isBlank()) {
            return ScriptType.LATIN;
        }
        return switch (nameLanguage.toUpperCase()) {
            case "AR" -> ScriptType.ARABIC;
            case "RU", "UK", "BG", "SR" -> ScriptType.CYRILLIC;
            case "ZH", "JA", "KO" -> ScriptType.CJK;
            default -> ScriptType.LATIN;
        };
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

    private static String attr(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        return (value == null || value.isBlank()) ? null : value.strip();
    }

    private static String buildFullName(String firstName, String lastName, String middleName) {
        if (lastName == null && firstName == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (lastName != null) {
            sb.append(lastName);
        }
        if (firstName != null) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(firstName);
        }
        if (middleName != null) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(middleName);
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

    private static LocalDate parseBirthDate(
            String dateStr, String yearStr, String monthStr, String dayStr) {
        // Try the full date attribute first
        if (dateStr != null && !dateStr.isBlank()) {
            try {
                return LocalDate.parse(dateStr.strip());
            } catch (DateTimeParseException ignored) {
            }
        }
        // Try year/month/day components
        try {
            if (yearStr != null && !yearStr.isBlank()) {
                int year = Integer.parseInt(yearStr.strip());
                int month =
                        (monthStr != null && !monthStr.isBlank())
                                ? Integer.parseInt(monthStr.strip())
                                : 1;
                int day =
                        (dayStr != null && !dayStr.isBlank())
                                ? Integer.parseInt(dayStr.strip())
                                : 1;
                if (year > 0) {
                    return LocalDate.of(year, month, day);
                }
            }
        } catch (NumberFormatException | java.time.DateTimeException ignored) {
        }
        return null;
    }

    private static Instant parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr.strip())
                    .atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.debug("Unable to parse EU date [value={}]", dateStr);
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
