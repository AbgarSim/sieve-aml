package dev.sieve.ingest.uk;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.core.model.VesselDetails;
import dev.sieve.ingest.HttpClientFactory;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.relations.RemarkRelations;
import dev.sieve.ingest.remarks.Deceased;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the UK Sanctions List published by the Foreign, Commonwealth and Development
 * Office (FCDO).
 *
 * <p>Since 28 January 2026 the UK Sanctions List is the only source of UK sanctions designations;
 * the former OFSI Consolidated List is closed and no longer updated. Each {@code <Designation>}
 * holds one target with all its names, addresses and identifiers. Entity ids are {@code uk-}
 * followed by the list's unique id (such as {@code RUS0001}), the same ids the OFSI list used, so
 * records keep their ids across the switch.
 *
 * <p>A row's other information often names another target ("Associate of ...", "Owned by ...");
 * each such name that matches exactly one target, written either as the list writes it or given
 * names first, becomes a relation to that target (see {@link RemarkRelations}).
 *
 * <p>Uses StAX (streaming) XML parsing for memory-efficient processing.
 *
 * @see <a href="https://www.gov.uk/government/publications/the-uk-sanctions-list">The UK Sanctions
 *     List</a>
 */
public final class UkHmtProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(UkHmtProvider.class);

    private static final String DEFAULT_URL =
            "https://sanctionslist.fcdo.gov.uk/docs/UK-Sanctions-List.xml";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final DateTimeFormatter UK_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /** Creates a provider with the default UK Sanctions List URL. */
    public UkHmtProvider() {
        this(URI.create(DEFAULT_URL));
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the UK Sanctions List XML from
     */
    public UkHmtProvider(URI sourceUri) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = HttpClientFactory.createTrustAllClient(CONNECT_TIMEOUT);
        this.currentMetadata = new ListMetadata(ListSource.UK_HMT, null, null, null, sourceUri, 0);
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the UK Sanctions List XML from
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
        log.info("Fetching UK Sanctions List [uri={}]", sourceUri);
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
     * Parses the UK Sanctions List XML into sanctioned entities.
     *
     * <p>Each {@code <Designation>} element becomes one entity. Its first "Primary name" is the
     * primary name, every other name and every non-Latin name an alias.
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
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "Designation".equals(reader.getLocalName())) {
                    SanctionedEntity entity = toEntity(readNode(reader));
                    if (entity != null) {
                        entities.add(entity);
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse UK Sanctions List XML: " + e.getMessage(),
                    ListSource.UK_HMT,
                    e);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "IO error reading UK Sanctions List XML: " + e.getMessage(),
                    ListSource.UK_HMT,
                    e);
        }

        return RemarkRelations.byName(entities, "UK HMT", RemarkRelations::givenNameFirst);
    }

    /** Builds one entity from a {@code <Designation>}, or null when it has no usable name. */
    private static SanctionedEntity toEntity(Node designation) {
        String uniqueId = designation.text("UniqueID");
        if (uniqueId == null) {
            log.debug("Skipping UK designation without a unique id");
            return null;
        }
        EntityType entityType = mapType(designation.text("IndividualEntityShip"));
        boolean individual = entityType == EntityType.INDIVIDUAL;
        String title = designation.child("Titles").map(t -> t.text("Title")).orElse(null);

        NameInfo primaryName = null;
        List<NameInfo> aliases = new ArrayList<>();
        for (Node name : designation.all("Names", "Name")) {
            String nameType = name.text("NameType");
            boolean primary =
                    primaryName == null
                            && nameType != null
                            && nameType.equalsIgnoreCase("primary name");
            NameInfo info =
                    buildName(
                            name,
                            primary ? NameType.PRIMARY : mapAliasType(nameType),
                            primary ? title : null,
                            strengthOf(name.text("AliasStrength")));
            if (info == null) {
                continue;
            }
            if (primary) {
                primaryName = info;
            } else {
                aliases.add(info);
            }
        }
        if (primaryName == null && !aliases.isEmpty()) {
            NameInfo first = aliases.removeFirst();
            primaryName =
                    new NameInfo(
                            first.fullName(),
                            first.givenName(),
                            first.familyName(),
                            first.middleName(),
                            title,
                            NameType.PRIMARY,
                            NameStrength.STRONG,
                            first.script());
        }
        if (primaryName == null) {
            log.debug("Skipping UK designation with no usable name [id={}]", uniqueId);
            return null;
        }
        for (Node nonLatin : designation.all("NonLatinNames", "NonLatinName")) {
            String text = nonLatin.text("NameNonLatinScript");
            if (text != null) {
                aliases.add(
                        new NameInfo(
                                text,
                                null,
                                null,
                                null,
                                null,
                                NameType.AKA,
                                NameStrength.STRONG,
                                mapNonLatinScript(nonLatin.text("NonLatinScriptType"))));
            }
        }

        Set<String> seenAddresses = new LinkedHashSet<>();
        List<Address> addresses = new ArrayList<>();
        for (Node address : designation.all("Addresses", "Address")) {
            Address built = buildAddress(address);
            if (built != null && seenAddresses.add(built.fullAddress())) {
                addresses.add(built);
            }
        }

        Node person =
                designation
                        .child("IndividualDetails")
                        .flatMap(d -> d.child("Individual"))
                        .orElse(Node.EMPTY);
        Node company =
                designation
                        .child("EntityDetails")
                        .flatMap(d -> d.child("Entity"))
                        .orElse(Node.EMPTY);
        Node ship =
                designation.child("ShipDetails").flatMap(d -> d.child("Ship")).orElse(Node.EMPTY);

        List<Identifier> identifiers = new ArrayList<>();
        Set<String> seenIdentifiers = new LinkedHashSet<>();
        for (Node passport : person.all("PassportDetails", "Passport")) {
            addIdentifier(
                    identifiers,
                    seenIdentifiers,
                    IdentifierType.PASSPORT,
                    passport.text("PassportNumber"),
                    passport.text("PassportAdditionalInformation"));
        }
        for (Node national : person.all("NationalIdentifierDetails", "NationalIdentifier")) {
            addIdentifier(
                    identifiers,
                    seenIdentifiers,
                    IdentifierType.NATIONAL_ID,
                    national.text("NationalIdentifierNumber"),
                    national.text("NationalIdentifierAdditionalInformation"));
        }
        for (String number :
                company.texts("BusinessRegistrationNumbers", "BusinessRegistrationNumber")) {
            addIdentifier(
                    identifiers,
                    seenIdentifiers,
                    IdentifierType.BUSINESS_REGISTRATION,
                    number,
                    null);
        }
        for (String imo : ship.texts("IMONumbers", "IMONumber")) {
            addIdentifier(
                    identifiers, seenIdentifiers, IdentifierType.IMO_NUMBER, imoDigits(imo), null);
        }

        List<String> nationalities =
                new ArrayList<>(new LinkedHashSet<>(person.texts("Nationalities", "Nationality")));

        List<LocalDate> datesOfBirth = new ArrayList<>();
        for (String dob : person.texts("DOBs", "DOB")) {
            LocalDate date = parseDateOfBirth(dob);
            if (date != null && !datesOfBirth.contains(date)) {
                datesOfBirth.add(date);
            }
        }

        Set<String> placesOfBirth = new LinkedHashSet<>();
        for (Node location : person.all("BirthDetails", "Location")) {
            String place =
                    joinNonBlank(
                            ", ", location.text("TownOfBirth"), location.text("CountryOfBirth"));
            if (place != null) {
                placesOfBirth.add(place);
            }
        }

        String regime = designation.text("RegimeName");
        List<SanctionsProgram> programs =
                regime == null
                        ? List.of()
                        : List.of(new SanctionsProgram(regime, regime, ListSource.UK_HMT));

        String remarks = designation.text("OtherInformation");
        String reasons = designation.text("UKStatementofReasons");
        Gender gender =
                individual
                        ? Gender.parse(
                                        person.child("Genders")
                                                .map(g -> g.text("Gender"))
                                                .orElse(null))
                                .orElse(null)
                        : null;

        return new SanctionedEntity(
                        "uk-" + uniqueId,
                        entityType,
                        ListSource.UK_HMT,
                        primaryName,
                        aliases,
                        addresses,
                        identifiers,
                        nationalities,
                        new ArrayList<>(nationalities),
                        datesOfBirth,
                        new ArrayList<>(placesOfBirth),
                        remarks,
                        programs,
                        parseDate(designation.text("DateDesignated")),
                        parseDate(designation.text("LastUpdated")))
                .withGender(gender)
                .withDeceased(individual ? Deceased.statedIn(remarks) : null)
                .withListingReasons(reasons == null ? List.of() : List.of(reasons))
                .withVessel(
                        entityType == EntityType.VESSEL
                                ? VesselDetails.of(
                                        first(
                                                ship.texts(
                                                        "CurrentBelievedFlagOfShips",
                                                        "CurrentBelievedFlagOfShip")),
                                        first(ship.texts("TypeOfShipDetails", "TypeOfShip")),
                                        null,
                                        VesselDetails.tons(
                                                first(
                                                        ship.texts(
                                                                "TonnageOfShipDetails",
                                                                "TonnageOfShip"))),
                                        null)
                                : null);
    }

    // ---- XML tree ------------------------------------------------------------

    /** A small element tree for one designation, so fields can be read by name. */
    static final class Node {
        static final Node EMPTY = new Node("", null, List.of());

        final String name;
        final String text;
        final List<Node> children;

        Node(String name, String text, List<Node> children) {
            this.name = name;
            this.text = text;
            this.children = children;
        }

        Optional<Node> child(String childName) {
            for (Node c : children) {
                if (c.name.equals(childName)) {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }

        /** The stripped text of the first child with this name, or null when absent or blank. */
        String text(String childName) {
            return child(childName).map(c -> c.text).orElse(null);
        }

        /** Every grandchild named {@code item} under every child named {@code group}. */
        List<Node> all(String group, String item) {
            List<Node> out = new ArrayList<>();
            for (Node g : children) {
                if (g.name.equals(group)) {
                    for (Node c : g.children) {
                        if (c.name.equals(item)) {
                            out.add(c);
                        }
                    }
                }
            }
            return out;
        }

        /** The non-blank texts of {@link #all(String, String)}. */
        List<String> texts(String group, String item) {
            List<String> out = new ArrayList<>();
            for (Node n : all(group, item)) {
                if (n.text != null) {
                    out.add(n.text);
                }
            }
            return out;
        }
    }

    /** Reads the element the reader is on, with everything inside it. */
    private static Node readNode(XMLStreamReader reader) throws XMLStreamException {
        String name = reader.getLocalName();
        List<Node> children = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                children.add(readNode(reader));
            } else if (event == XMLStreamConstants.CHARACTERS
                    || event == XMLStreamConstants.CDATA) {
                text.append(reader.getText());
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                break;
            }
        }
        return new Node(name, strip(text.toString()), children);
    }

    // ---- Field builders ----------------------------------------------------

    private static NameInfo buildName(
            Node name, NameType nameType, String title, NameStrength strength) {
        String familyName = name.text("Name6");
        String givenName = name.text("Name1");
        String middleName =
                joinNonBlank(
                        " ",
                        name.text("Name2"),
                        name.text("Name3"),
                        name.text("Name4"),
                        name.text("Name5"));
        String fullName = buildFullName(givenName, familyName, middleName);
        if (fullName == null) {
            return null;
        }
        return new NameInfo(
                fullName,
                givenName,
                familyName,
                middleName,
                title,
                nameType,
                strength,
                ScriptType.LATIN);
    }

    private static Address buildAddress(Node address) {
        String street =
                joinNonBlank(
                        ", ",
                        address.text("AddressLine1"),
                        address.text("AddressLine2"),
                        address.text("AddressLine3"));
        String city =
                joinNonBlank(
                        ", ",
                        address.text("AddressLine4"),
                        address.text("AddressLine5"),
                        address.text("AddressLine6"));
        String postCode = address.text("AddressPostalCode");
        String country = address.text("AddressCountry");
        String fullAddress = joinNonBlank(", ", street, city, postCode, country);
        if (fullAddress == null) {
            return null;
        }
        return new Address(street, city, null, postCode, country, fullAddress);
    }

    private static void addIdentifier(
            List<Identifier> identifiers,
            Set<String> seen,
            IdentifierType type,
            String value,
            String note) {
        if (value == null || value.isBlank()) {
            return;
        }
        String stripped = value.strip();
        if (seen.add(type + ":" + stripped)) {
            identifiers.add(new Identifier(type, stripped, null, strip(note)));
        }
    }

    /** The list writes IMO numbers both as {@code 9562233} and {@code IMO9562233}. */
    private static String imoDigits(String imo) {
        String stripped = imo.strip();
        return stripped.regionMatches(true, 0, "IMO", 0, 3)
                ? stripped.substring(3).strip()
                : stripped;
    }

    // ---- Mapping helpers ---------------------------------------------------

    private static EntityType mapType(String type) {
        if (type == null) {
            return EntityType.ENTITY;
        }
        return switch (type.strip().toLowerCase()) {
            case "individual" -> EntityType.INDIVIDUAL;
            case "ship" -> EntityType.VESSEL;
            default -> EntityType.ENTITY;
        };
    }

    private static NameType mapAliasType(String aliasType) {
        if (aliasType == null) {
            return NameType.AKA;
        }
        String lower = aliasType.strip().toLowerCase();
        return lower.equals("fka") || lower.equals("f.k.a.") || lower.startsWith("formerly")
                ? NameType.FKA
                : NameType.AKA;
    }

    /** "Low quality a.k.a" names are weak; every other name is strong. */
    private static NameStrength strengthOf(String aliasStrength) {
        return aliasStrength != null && aliasStrength.toLowerCase().startsWith("low")
                ? NameStrength.WEAK
                : NameStrength.STRONG;
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
        return sb.toString();
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

    private static String first(List<String> values) {
        return values.isEmpty() ? null : values.getFirst();
    }

    /**
     * Parses a date of birth. The list writes {@code dd/MM/yyyy} and marks unknown parts with
     * {@code dd} or {@code mm}, as in {@code dd/mm/1945}; a date with only the year known becomes 1
     * January of that year, and one with the month known the first of that month.
     */
    static LocalDate parseDateOfBirth(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.strip().split("/");
        if (parts.length != 3) {
            return null;
        }
        try {
            int year = Integer.parseInt(parts[2].strip());
            boolean monthKnown = parts[1].chars().allMatch(Character::isDigit);
            boolean dayKnown = parts[0].chars().allMatch(Character::isDigit);
            int month = monthKnown ? Integer.parseInt(parts[1]) : 1;
            int day = monthKnown && dayKnown ? Integer.parseInt(parts[0]) : 1;
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            log.debug("Unable to parse UK date of birth [value={}]", value);
            return null;
        }
    }

    private static Instant parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip(), UK_DATE_FORMAT)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException e) {
            log.debug("Unable to parse UK date [value={}]", value);
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
