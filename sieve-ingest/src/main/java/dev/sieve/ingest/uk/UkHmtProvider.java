package dev.sieve.ingest.uk;

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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the UK HM Treasury / OFSI Consolidated List of Financial Sanctions Targets.
 *
 * <p>The XML uses a flat structure where each {@code <FinancialSanctionsTarget>} row represents a
 * single name entry. Multiple rows share the same {@code <GroupID>} to represent aliases of the
 * same entity. This provider groups rows by {@code GroupID}, selects the "Primary name" entry as
 * the entity's primary name, and collects the rest as aliases.
 *
 * <p>Uses StAX (streaming) XML parsing for memory-efficient processing.
 *
 * @see <a href="https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml">UK
 *     HMT Consolidated XML</a>
 */
public final class UkHmtProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(UkHmtProvider.class);

    private static final String DEFAULT_URL =
            "https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final DateTimeFormatter HMT_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /** Creates a provider with the default UK HMT URL. */
    public UkHmtProvider() {
        this(URI.create(DEFAULT_URL));
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the consolidated list XML from
     */
    public UkHmtProvider(URI sourceUri) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = HttpClientFactory.createTrustAllClient(CONNECT_TIMEOUT);
        this.currentMetadata = new ListMetadata(ListSource.UK_HMT, null, null, null, sourceUri, 0);
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the consolidated list XML from
     * @param httpClient the HTTP client to use for requests
     */
    public UkHmtProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.currentMetadata = new ListMetadata(ListSource.UK_HMT, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.UK_HMT;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching UK HMT consolidated list [uri={}]", sourceUri);
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
                                "UK HMT fetch failed [status=%d, uri=%s]",
                                response.statusCode(), sourceUri),
                        ListSource.UK_HMT);
            }

            byte[] body = response.body();
            String contentHash = computeSha256(body);
            String etag = response.headers().firstValue("ETag").orElse(null);

            log.info(
                    "UK HMT downloaded [bytes={}, etag={}, hash={}]",
                    body.length,
                    etag,
                    contentHash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseXml(body);

            Instant now = Instant.now();
            Duration duration = Duration.between(start, now);
            currentMetadata =
                    new ListMetadata(
                            ListSource.UK_HMT, now, etag, contentHash, sourceUri, entities.size());

            log.info(
                    "UK HMT ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    duration.toMillis());

            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching UK HMT list: " + e.getMessage(), ListSource.UK_HMT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException("UK HMT fetch interrupted", ListSource.UK_HMT, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during UK HMT ingestion: " + e.getMessage(),
                    ListSource.UK_HMT,
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
                log.debug("UK HMT update check [etag unchanged, hasUpdates=false]");
                return false;
            }

            log.debug("UK HMT update check [status={}, hasUpdates=true]", response.statusCode());
            return true;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to check UK HMT for updates, assuming updates exist", e);
            return true;
        }
    }

    // ---- XML parsing -------------------------------------------------------

    /**
     * Parses the UK HMT consolidated list XML into sanctioned entities.
     *
     * <p>The XML is a flat list of {@code <FinancialSanctionsTarget>} rows. Rows sharing the same
     * {@code <GroupID>} are aliases of a single entity. This method groups rows by GroupID, selects
     * the "Primary name" row as the entity's primary name, and collects all others as aliases.
     *
     * @param xmlContent the raw XML bytes
     * @return the parsed entities
     * @throws ListIngestionException if the XML cannot be parsed
     */
    List<SanctionedEntity> parseXml(byte[] xmlContent) throws ListIngestionException {
        Map<String, List<HmtRow>> groups = new LinkedHashMap<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream input = new ByteArrayInputStream(xmlContent)) {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(input, StandardCharsets.UTF_8.name());

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "FinancialSanctionsTarget".equals(reader.getLocalName())) {
                    HmtRow row = parseRow(reader);
                    if (row != null && row.groupId != null) {
                        groups.computeIfAbsent(row.groupId, k -> new ArrayList<>()).add(row);
                    }
                }
            }

            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse UK HMT XML: " + e.getMessage(), ListSource.UK_HMT, e);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "IO error reading UK HMT XML: " + e.getMessage(), ListSource.UK_HMT, e);
        }

        List<SanctionedEntity> entities = new ArrayList<>(groups.size());
        for (Map.Entry<String, List<HmtRow>> entry : groups.entrySet()) {
            SanctionedEntity entity = mergeGroup(entry.getKey(), entry.getValue());
            if (entity != null) {
                entities.add(entity);
            }
        }

        return entities;
    }

    /** Merges all rows sharing a GroupID into a single {@link SanctionedEntity}. */
    private SanctionedEntity mergeGroup(String groupId, List<HmtRow> rows) {
        HmtRow primary = null;
        for (HmtRow row : rows) {
            if ("Primary name".equalsIgnoreCase(row.aliasType)) {
                primary = row;
                break;
            }
        }
        if (primary == null) {
            primary = rows.getFirst();
        }

        List<NameInfo> aliases = new ArrayList<>();
        Set<String> seenAddresses = new LinkedHashSet<>();
        List<Address> addresses = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        Set<String> nationalities = new LinkedHashSet<>();
        Set<String> citizenships = new LinkedHashSet<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        Set<String> placesOfBirth = new LinkedHashSet<>();
        Set<String> regimes = new LinkedHashSet<>();
        String remarks = primary.statementOfReasons;
        Instant listedDate = parseInstant(primary.dateListed);
        Instant lastUpdated = parseInstant(primary.lastUpdated);

        for (HmtRow row : rows) {
            // Collect aliases (skip the primary)
            if (row != primary) {
                NameInfo alias = buildNameInfo(row, mapAliasType(row.aliasType));
                if (alias != null) {
                    aliases.add(alias);
                }
            }

            // Collect non-Latin script as additional alias
            if (row.nameNonLatinScript != null && !row.nameNonLatinScript.isBlank()) {
                ScriptType script = mapNonLatinScript(row.nonLatinScriptType);
                aliases.add(
                        new NameInfo(
                                row.nameNonLatinScript.strip(),
                                null,
                                null,
                                null,
                                null,
                                NameType.AKA,
                                NameStrength.STRONG,
                                script));
            }

            // Merge addresses (deduplicate by full address string)
            Address addr = buildAddress(row);
            if (addr != null && addr.fullAddress() != null) {
                if (seenAddresses.add(addr.fullAddress())) {
                    addresses.add(addr);
                }
            }

            // Merge identifiers
            collectIdentifiers(row, identifiers);

            // Merge nationalities / citizenships / DOBs / POBs
            addIfPresent(nationalities, row.nationality);
            addIfPresent(citizenships, row.nationality);
            addIfPresent(placesOfBirth, row.townOfBirth);

            LocalDate dob = parseDateOfBirth(row.dateOfBirth);
            if (dob != null && !datesOfBirth.contains(dob)) {
                datesOfBirth.add(dob);
            }

            // Merge regimes
            if (row.regimeName != null && !row.regimeName.isBlank()) {
                regimes.add(row.regimeName.strip());
            }

            // Use latest update timestamp
            Instant rowUpdated = parseInstant(row.lastUpdated);
            if (rowUpdated != null && (lastUpdated == null || rowUpdated.isAfter(lastUpdated))) {
                lastUpdated = rowUpdated;
            }

            // Use earliest listed date
            Instant rowListed = parseInstant(row.dateListed);
            if (rowListed != null && (listedDate == null || rowListed.isBefore(listedDate))) {
                listedDate = rowListed;
            }
        }

        NameInfo primaryName = buildNameInfo(primary, NameType.PRIMARY);
        if (primaryName == null) {
            log.debug("Skipping UK HMT group with no usable primary name [groupId={}]", groupId);
            return null;
        }

        EntityType entityType = mapGroupType(primary.groupTypeDescription);

        List<SanctionsProgram> programs =
                regimes.stream().map(r -> new SanctionsProgram(r, r, ListSource.UK_HMT)).toList();

        String entityId =
                primary.ukSanctionsListRef != null ? primary.ukSanctionsListRef : "UK-" + groupId;

        return new SanctionedEntity(
                entityId,
                entityType,
                ListSource.UK_HMT,
                primaryName,
                aliases,
                addresses,
                identifiers,
                new ArrayList<>(nationalities),
                new ArrayList<>(citizenships),
                datesOfBirth,
                new ArrayList<>(placesOfBirth),
                remarks,
                programs,
                listedDate,
                lastUpdated);
    }

    // ---- Row parsing -------------------------------------------------------

    /** Intermediate holder for a single {@code <FinancialSanctionsTarget>} row before grouping. */
    static final class HmtRow {
        String name1; // given name
        String name2; // middle name parts
        String name3;
        String name4;
        String name5;
        String name6; // surname / entity name
        String title;
        String nameNonLatinScript;
        String nonLatinScriptType;
        String nonLatinScriptLanguage;
        String address1;
        String address2;
        String address3;
        String address4;
        String address5;
        String address6;
        String postCode;
        String country;
        String otherInformation;
        String groupTypeDescription;
        String aliasType;
        String aliasQuality;
        String regimeName;
        String dateListed;
        String dateDesignated;
        String ukSanctionsListRef;
        String statementOfReasons;
        String dateOfBirth;
        String townOfBirth;
        String countryOfBirth;
        String nationality;
        String passportNumber;
        String passportDetails;
        String niNumber;
        String niDetails;
        String position;
        String gender;
        String entityType;
        String businessRegNumber;
        String shipImoNumber;
        String shipFlag;
        String shipType;
        String lastUpdated;
        String groupId;
        String grpStatus;
    }

    private HmtRow parseRow(XMLStreamReader reader) throws XMLStreamException {
        HmtRow row = new HmtRow();

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String el = reader.getLocalName();
                switch (el) {
                    case "name1" -> row.name1 = readText(reader);
                    case "name2" -> row.name2 = readText(reader);
                    case "name3" -> row.name3 = readText(reader);
                    case "name4" -> row.name4 = readText(reader);
                    case "name5" -> row.name5 = readText(reader);
                    case "Name6" -> row.name6 = readText(reader);
                    case "Title" -> row.title = readText(reader);
                    case "NameNonLatinScript" -> row.nameNonLatinScript = readText(reader);
                    case "NonLatinScriptType" -> row.nonLatinScriptType = readText(reader);
                    case "NonLatinScriptLanguage" -> row.nonLatinScriptLanguage = readText(reader);
                    case "Address1" -> row.address1 = readText(reader);
                    case "Address2" -> row.address2 = readText(reader);
                    case "Address3" -> row.address3 = readText(reader);
                    case "Address4" -> row.address4 = readText(reader);
                    case "Address5" -> row.address5 = readText(reader);
                    case "Address6" -> row.address6 = readText(reader);
                    case "PostCode" -> row.postCode = readText(reader);
                    case "Country" -> row.country = readText(reader);
                    case "OtherInformation" -> row.otherInformation = readText(reader);
                    case "GroupTypeDescription" -> row.groupTypeDescription = readText(reader);
                    case "AliasType" -> row.aliasType = readText(reader);
                    case "AliasQuality" -> row.aliasQuality = readText(reader);
                    case "RegimeName" -> row.regimeName = readText(reader);
                    case "DateListed" -> row.dateListed = readText(reader);
                    case "DateDesignated" -> row.dateDesignated = readText(reader);
                    case "UKSanctionsListRef" -> row.ukSanctionsListRef = readText(reader);
                    case "UKStatementOfReasons" -> row.statementOfReasons = readText(reader);
                    case "Individual_DateOfBirth" -> row.dateOfBirth = readText(reader);
                    case "Individual_TownOfBirth" -> row.townOfBirth = readText(reader);
                    case "Individual_CountryOfBirth" -> row.countryOfBirth = readText(reader);
                    case "Individual_Nationality" -> row.nationality = readText(reader);
                    case "Individual_PassportNumber" -> row.passportNumber = readText(reader);
                    case "Individual_PassportDetails" -> row.passportDetails = readText(reader);
                    case "Individual_NINumber" -> row.niNumber = readText(reader);
                    case "Individual_NIDetails" -> row.niDetails = readText(reader);
                    case "Individual_Position" -> row.position = readText(reader);
                    case "Individual_Gender" -> row.gender = readText(reader);
                    case "Entity_Type" -> row.entityType = readText(reader);
                    case "Entity_BusinessRegNumber" -> row.businessRegNumber = readText(reader);
                    case "Ship_IMONumber" -> row.shipImoNumber = readText(reader);
                    case "Ship_Flag" -> row.shipFlag = readText(reader);
                    case "Ship_Type" -> row.shipType = readText(reader);
                    case "LastUpdated" -> row.lastUpdated = readText(reader);
                    case "GroupID" -> row.groupId = readText(reader);
                    case "GrpStatus" -> row.grpStatus = readText(reader);
                    default -> {
                        /* skip unknown elements */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "FinancialSanctionsTarget".equals(reader.getLocalName())) {
                break;
            }
        }

        return row;
    }

    // ---- Field builders ----------------------------------------------------

    private static NameInfo buildNameInfo(HmtRow row, NameType nameType) {
        String familyName = strip(row.name6);
        String givenName = strip(row.name1);
        String middleName = joinNonBlank(" ", row.name2, row.name3, row.name4, row.name5);

        String fullName = buildFullName(givenName, familyName, middleName);
        if (fullName == null || fullName.isBlank()) {
            return null;
        }

        return new NameInfo(
                fullName,
                givenName,
                familyName,
                middleName,
                strip(row.title),
                nameType,
                NameStrength.STRONG,
                ScriptType.LATIN);
    }

    private static Address buildAddress(HmtRow row) {
        String street = joinNonBlank(", ", row.address1, row.address2, row.address3);
        String city = joinNonBlank(", ", row.address4, row.address5, row.address6);
        String postCode = strip(row.postCode);
        String country = strip(row.country);

        String fullAddress = joinNonBlank(", ", street, city, postCode, country);
        if (fullAddress == null) {
            return null;
        }

        return new Address(street, city, null, postCode, country, fullAddress);
    }

    private static void collectIdentifiers(HmtRow row, List<Identifier> identifiers) {
        if (row.passportNumber != null && !row.passportNumber.isBlank()) {
            identifiers.add(
                    new Identifier(
                            IdentifierType.PASSPORT,
                            row.passportNumber.strip(),
                            null,
                            strip(row.passportDetails)));
        }
        if (row.niNumber != null && !row.niNumber.isBlank()) {
            identifiers.add(
                    new Identifier(
                            IdentifierType.NATIONAL_ID,
                            row.niNumber.strip(),
                            null,
                            strip(row.niDetails)));
        }
        if (row.businessRegNumber != null && !row.businessRegNumber.isBlank()) {
            identifiers.add(
                    new Identifier(
                            IdentifierType.BUSINESS_REGISTRATION,
                            row.businessRegNumber.strip(),
                            null,
                            null));
        }
        if (row.shipImoNumber != null && !row.shipImoNumber.isBlank()) {
            identifiers.add(
                    new Identifier(
                            IdentifierType.IMO_NUMBER, row.shipImoNumber.strip(), null, null));
        }
    }

    // ---- Mapping helpers ---------------------------------------------------

    private static EntityType mapGroupType(String groupType) {
        if (groupType == null) {
            return EntityType.ENTITY;
        }
        return switch (groupType.strip().toLowerCase()) {
            case "individual" -> EntityType.INDIVIDUAL;
            case "entity" -> EntityType.ENTITY;
            case "ship" -> EntityType.VESSEL;
            default -> EntityType.ENTITY;
        };
    }

    private static NameType mapAliasType(String aliasType) {
        if (aliasType == null) {
            return NameType.AKA;
        }
        return switch (aliasType.strip().toLowerCase()) {
            case "primary name", "primary name variation" -> NameType.AKA;
            case "aka", "a.k.a." -> NameType.AKA;
            case "fka", "f.k.a." -> NameType.FKA;
            default -> NameType.AKA;
        };
    }

    private static ScriptType mapNonLatinScript(String scriptType) {
        if (scriptType == null || scriptType.isBlank()) {
            return ScriptType.OTHER;
        }
        String lower = scriptType.strip().toLowerCase();
        if (lower.contains("cyrillic")) return ScriptType.CYRILLIC;
        if (lower.contains("arabic")) return ScriptType.ARABIC;
        if (lower.contains("chinese") || lower.contains("japanese") || lower.contains("korean")) {
            return ScriptType.CJK;
        }
        return ScriptType.OTHER;
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

    private static String buildFullName(String givenName, String familyName, String middleName) {
        if (familyName == null && givenName == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (familyName != null) {
            sb.append(familyName);
        }
        if (givenName != null) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(givenName);
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

    private static LocalDate parseDateOfBirth(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        String stripped = dateStr.strip();
        // HMT uses "dd/MM/yyyy" or ISO date or ISO datetime
        try {
            return LocalDate.parse(stripped, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(stripped);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(stripped, HMT_DATETIME_FORMAT).toLocalDate();
        } catch (DateTimeParseException ignored) {
        }
        log.debug("Unable to parse UK HMT date of birth [value={}]", dateStr);
        return null;
    }

    private static Instant parseInstant(String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateTimeStr.strip(), HMT_DATETIME_FORMAT)
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            log.debug("Unable to parse UK HMT datetime [value={}]", dateTimeStr);
            return null;
        }
    }

    private static void addIfPresent(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value.strip());
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
