package dev.sieve.ingest.za;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.HttpClientFactory;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import java.io.ByteArrayInputStream;
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
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the South Africa FIC (Financial Intelligence Centre) targeted financial
 * sanctions list.
 *
 * <p>Published by the FIC as XML via a POST request. Covers UN Security Council sanctions plus
 * South African additions. Typically contains ~900 entities (persons and organizations).
 *
 * <p>Note: This provider uses HTTP POST (with {@code fileType=xml}) instead of GET, so it does not
 * extend {@link dev.sieve.ingest.AbstractListProvider}.
 *
 * @see <a href="https://www.fic.gov.za/International/sanctions/SitePages/Home.aspx">SA FIC
 *     Sanctions</a>
 */
public final class ZaFicProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(ZaFicProvider.class);
    private static final String DEFAULT_URL = "https://tfs.fic.gov.za/Pages/TFSListDownload";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final DateTimeFormatter ZA_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    public ZaFicProvider() {
        this(
                URI.create(DEFAULT_URL),
                HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public ZaFicProvider(URI sourceUri) {
        this(sourceUri, HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public ZaFicProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = sourceUri;
        this.httpClient = httpClient;
        this.currentMetadata = new ListMetadata(ListSource.ZA_FIC, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.ZA_FIC;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching ZA FIC list via POST [uri={}]", sourceUri);
        Instant start = Instant.now();

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(sourceUri)
                            .timeout(REQUEST_TIMEOUT)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("User-Agent", "sieve-aml/1.0")
                            .POST(HttpRequest.BodyPublishers.ofString("fileType=xml"))
                            .build();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format("ZA FIC fetch failed [status=%d]", response.statusCode()),
                        ListSource.ZA_FIC);
            }

            byte[] body = response.body();
            String contentHash = computeSha256(body);
            log.info(
                    "ZA FIC downloaded [bytes={}, hash={}]",
                    body.length,
                    contentHash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseXml(body);

            Instant now = Instant.now();
            currentMetadata =
                    new ListMetadata(
                            ListSource.ZA_FIC, now, null, contentHash, sourceUri, entities.size());

            log.info(
                    "ZA FIC ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    Duration.between(start, now).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Error fetching ZA FIC: " + e.getMessage(), ListSource.ZA_FIC, e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return true;
    }

    private List<SanctionedEntity> parseXml(byte[] body) throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(body)) {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(bais, StandardCharsets.UTF_8.name());

            // The XML contains <Table> and <Table1> elements with row data
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    if ("Table".equals(localName) || "Table1".equals(localName)) {
                        SanctionedEntity entity = parseRow(reader, localName);
                        if (entity != null) entities.add(entity);
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse ZA FIC XML: " + e.getMessage(), ListSource.ZA_FIC, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "IO error reading ZA FIC XML: " + e.getMessage(), ListSource.ZA_FIC, e);
        }
        return entities;
    }

    private SanctionedEntity parseRow(XMLStreamReader reader, String tableName)
            throws XMLStreamException {
        // Fields for individuals (Table)
        String fullName = null;
        String individualId = null;
        String nationality = null;
        String title = null;
        String designation = null;
        String birthPlace = null;
        String dob = null;
        String alias = null;
        String address = null;
        String comments = null;
        String referenceNumber = null;
        String listedOn = null;

        // Fields for entities (Table1)
        String entityId = null;
        String firstName = null;
        String entityAlias = null;
        String entityAddress = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName();
                switch (elem) {
                    case "FullName" -> fullName = readText(reader);
                    case "IndividualID" -> individualId = readText(reader);
                    case "EntityID" -> entityId = readText(reader);
                    case "FirstName" -> firstName = readText(reader);
                    case "Nationality" -> nationality = readText(reader);
                    case "Title" -> title = readText(reader);
                    case "Designation" -> designation = readText(reader);
                    case "IndividualPlaceOfBirth" -> birthPlace = readText(reader);
                    case "IndividualDateOfBirth" -> dob = readText(reader);
                    case "IndividualAlias" -> alias = readText(reader);
                    case "IndividualAddress" -> address = readText(reader);
                    case "EntityAlias" -> entityAlias = readText(reader);
                    case "EntityAddress" -> entityAddress = readText(reader);
                    case "Comments" -> comments = readText(reader);
                    case "ReferenceNumber" -> referenceNumber = readText(reader);
                    case "ListedOn" -> listedOn = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && tableName.equals(reader.getLocalName())) {
                break;
            }
        }

        // Determine if this is an individual or entity row
        boolean isIndividual = fullName != null;
        String name = isIndividual ? fullName : firstName;
        String id = isIndividual ? individualId : entityId;
        if (name == null || name.isBlank()) return null;
        if ("NA".equals(name)) return null;

        EntityType entityType = isIndividual ? EntityType.INDIVIDUAL : EntityType.ENTITY;

        NameInfo primaryName =
                new NameInfo(name, null, null, null, null, NameType.PRIMARY, null, null);

        List<NameInfo> aliases = new ArrayList<>();
        String aliasStr = isIndividual ? alias : entityAlias;
        if (aliasStr != null && !aliasStr.isBlank() && !"NA".equals(aliasStr)) {
            for (String a : aliasStr.split("[;,]")) {
                String trimmed = a.strip();
                if (!trimmed.isEmpty() && !trimmed.equals(name)) {
                    aliases.add(
                            new NameInfo(
                                    trimmed, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        if (dob != null && !dob.isBlank() && !"NA".equals(dob)) {
            LocalDate d = parseDateSafe(dob);
            if (d != null) datesOfBirth.add(d);
        }

        List<String> nationalities = new ArrayList<>();
        if (nationality != null && !nationality.isBlank() && !"NA".equals(nationality)) {
            nationalities.add(nationality.strip());
        }

        List<String> placesOfBirth = new ArrayList<>();
        if (birthPlace != null && !birthPlace.isBlank() && !"NA".equals(birthPlace)) {
            placesOfBirth.add(birthPlace.strip());
        }

        List<SanctionsProgram> programs =
                List.of(
                        new SanctionsProgram(
                                "ZA FIC TFS",
                                "South Africa Targeted Financial Sanctions",
                                ListSource.ZA_FIC));

        Instant listedDate = null;
        if (listedOn != null && !listedOn.isBlank()) {
            LocalDate ld = parseDateSafe(listedOn);
            if (ld != null) {
                listedDate = ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            }
        }

        String entityId2 = id != null ? id : String.valueOf(name.hashCode());

        return new SanctionedEntity(
                "za-" + entityId2,
                entityType,
                ListSource.ZA_FIC,
                primaryName,
                aliases,
                List.of(),
                List.of(),
                nationalities,
                List.of(),
                datesOfBirth,
                placesOfBirth,
                comments,
                programs,
                listedDate,
                Instant.now());
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

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip();
        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException e) {
            /* try next */
        }
        try {
            return LocalDate.parse(cleaned, ZA_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
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
