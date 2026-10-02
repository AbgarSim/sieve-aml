package dev.sieve.ingest.lv;

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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fetches and parses the Latvia FIU (Finanšu izlūkošanas dienests) national sanctions list.
 *
 * <p>The list is downloaded from the FIU's sanctions search page: the page sets a session cookie
 * and a form token, and the XML comes back from a form post to {@code /lejupieladet-sarakstu/lv}.
 * It holds only Latvia's national designations, a handful of entries.
 *
 * @see <a href="https://sankcijas.fid.gov.lv/lv/meklet-sankciju-sarakstos">Latvia FIU sanctions
 *     search</a>
 */
public final class LvFiuProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://sankcijas.fid.gov.lv/lv/meklet-sankciju-sarakstos";

    private static final String DOWNLOAD_PATH = "/lejupieladet-sarakstu/lv";

    private static final Pattern CSRF = Pattern.compile("name=\"csrf\"\\s+value=\"([^\"]+)\"");

    public LvFiuProvider() {
        super(ListSource.LV_FIU, URI.create(DEFAULT_URL), "application/xml");
    }

    public LvFiuProvider(URI sourceUri) {
        super(ListSource.LV_FIU, sourceUri, "application/xml");
    }

    public LvFiuProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.LV_FIU, sourceUri, "application/xml", httpClient, Duration.ofSeconds(120));
    }

    /**
     * Opens the search page for its session cookie and form token, then posts the download form.
     */
    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        HttpRequest pageRequest =
                HttpRequest.newBuilder()
                        .uri(sourceUri())
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "sieve-aml/1.0")
                        .GET()
                        .build();
        HttpResponse<String> page =
                client.send(
                        pageRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        String csrf = csrfToken(page.body());
        if (csrf == null) {
            throw new IOException(
                    "Latvia FIU search page has no download token [status="
                            + page.statusCode()
                            + "]");
        }
        String cookies =
                page.headers().allValues("Set-Cookie").stream()
                        .map(c -> c.split(";", 2)[0])
                        .collect(Collectors.joining("; "));

        String form = "csrf=" + URLEncoder.encode(csrf, StandardCharsets.UTF_8) + "&fileType=xml";
        builder.uri(sourceUri().resolve(DOWNLOAD_PATH))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies);
        }
        return builder.build();
    }

    static String csrfToken(String html) {
        Matcher m = CSRF.matcher(html == null ? "" : html);
        return m.find() ? m.group(1) : null;
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        String head =
                new String(
                        responseBody,
                        0,
                        Math.min(responseBody.length, 512),
                        StandardCharsets.UTF_8);
        if (!head.contains("<LVlist")) {
            throw new ListIngestionException(
                    "Latvia FIU returned a web page instead of the list XML", ListSource.LV_FIU);
        }

        List<SanctionedEntity> entities = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (ByteArrayInputStream bais = new ByteArrayInputStream(responseBody)) {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(bais, StandardCharsets.UTF_8.name());

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "Entity".equals(reader.getLocalName())) {
                    SanctionedEntity entity = parseEntity(reader);
                    if (entity != null) entities.add(entity);
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse Latvia FIU XML: " + e.getMessage(), ListSource.LV_FIU, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "IO error reading Latvia FIU XML: " + e.getMessage(), ListSource.LV_FIU, e);
        }
        return entities;
    }

    /** Fields of one {@code <Entity>} as they are read. */
    private static final class Fields {
        String id;
        String type;
        String wholeName;
        String firstName;
        String middleName;
        String lastName;
        String birthDate;
        String birthPlace;
        String birthCountry;
        String listedOn;
        String program;
        String remark;
        final List<NameInfo> aliases = new ArrayList<>();
        final List<String> nationalities = new ArrayList<>();
        final List<Address> addresses = new ArrayList<>();
        final List<Identifier> identifiers = new ArrayList<>();
    }

    private SanctionedEntity parseEntity(XMLStreamReader reader) throws XMLStreamException {
        Fields f = new Fields();
        String[] alias = new String[4];
        String[] address = new String[5];
        String[] document = new String[3];
        String citizen = null;
        String citizenCode = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName();
                switch (elem) {
                    case "Alias", "Citizen", "Address", "Document" -> {
                        if ("Alias".equals(elem)) alias = new String[4];
                        if ("Address".equals(elem)) address = new String[5];
                        if ("Document".equals(elem)) document = new String[3];
                        if ("Citizen".equals(elem)) {
                            citizen = null;
                            citizenCode = null;
                        }
                    }
                    case "Id" -> f.id = readText(reader);
                    case "Type" -> f.type = readText(reader);
                    case "ListedOn" -> f.listedOn = readText(reader);
                    case "Program" -> f.program = readText(reader);
                    case "Remark" -> f.remark = readText(reader);
                    case "WholeName" -> f.wholeName = readText(reader);
                    case "FirstName" -> f.firstName = readText(reader);
                    case "MiddleName" -> f.middleName = readText(reader);
                    case "LastName" -> f.lastName = readText(reader);
                    case "AliasWholeName" -> alias[0] = readText(reader);
                    case "AliasFirstName" -> alias[1] = readText(reader);
                    case "AliasMiddleName" -> alias[2] = readText(reader);
                    case "AliasLastName" -> alias[3] = readText(reader);
                    case "BirthDate" -> f.birthDate = readText(reader);
                    case "BirthPlace" -> f.birthPlace = readText(reader);
                    case "BirthCountry" -> f.birthCountry = readText(reader);
                    case "CitizenCountry" -> citizen = readText(reader);
                    case "CitizenCountryIso2Code" -> citizenCode = readText(reader);
                    case "AddressStreet" -> address[0] = readText(reader);
                    case "AddressCity" -> address[1] = readText(reader);
                    case "AddressCountryIso2Code" -> address[2] = readText(reader);
                    case "AddressWhole" -> address[3] = readText(reader);
                    case "AddressCountry" -> address[4] = readText(reader);
                    case "DocumentType" -> document[0] = readText(reader);
                    case "DocumentNumber" -> document[1] = readText(reader);
                    case "DocumentCountryIso2Code" -> document[2] = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "Entity" -> {
                        return buildEntity(f);
                    }
                    case "Alias" -> {
                        String name = buildFullName(alias[0], alias[1], alias[2], alias[3]);
                        if (name != null) {
                            f.aliases.add(
                                    new NameInfo(
                                            name,
                                            alias[1],
                                            alias[3],
                                            alias[2],
                                            null,
                                            NameType.AKA,
                                            null,
                                            null));
                        }
                    }
                    case "Citizen" -> {
                        String nationality = citizenCode != null ? citizenCode : citizen;
                        if (nationality != null) f.nationalities.add(nationality);
                    }
                    case "Address" -> {
                        String country = address[2] != null ? address[2] : address[4];
                        if (address[0] != null || address[1] != null || address[3] != null) {
                            f.addresses.add(
                                    new Address(
                                            address[0],
                                            address[1],
                                            null,
                                            null,
                                            country,
                                            address[3]));
                        }
                    }
                    case "Document" -> {
                        if (document[1] != null) {
                            f.identifiers.add(
                                    new Identifier(
                                            documentType(document[0]),
                                            document[1],
                                            document[2],
                                            document[0]));
                        }
                    }
                    default -> {
                        /* skip */
                    }
                }
            }
        }
        return null;
    }

    /**
     * Latvian document names: {@code Pase} is a passport, {@code Personas apliecība} an ID card.
     */
    private static IdentifierType documentType(String type) {
        if (type == null) return IdentifierType.OTHER;
        String t = type.toLowerCase(Locale.ROOT);
        if (t.startsWith("pase")) return IdentifierType.PASSPORT;
        if (t.contains("apliecība") || t.contains("personas kods")) {
            return IdentifierType.NATIONAL_ID;
        }
        if (t.contains("reģistrācijas")) return IdentifierType.BUSINESS_REGISTRATION;
        return IdentifierType.OTHER;
    }

    private SanctionedEntity buildEntity(Fields f) {
        // FP = natural person (fiziska persona), JP = legal person (juridiska persona)
        boolean isPerson = f.type != null && f.type.equalsIgnoreCase("fp");
        EntityType entityType = isPerson ? EntityType.INDIVIDUAL : EntityType.ENTITY;

        String fullName = buildFullName(f.wholeName, f.firstName, f.middleName, f.lastName);
        if (fullName == null) return null;

        NameInfo primaryName =
                new NameInfo(
                        fullName,
                        f.firstName,
                        f.lastName,
                        f.middleName,
                        null,
                        NameType.PRIMARY,
                        null,
                        null);

        List<LocalDate> datesOfBirth = new ArrayList<>();
        LocalDate dob = parseDateSafe(f.birthDate);
        if (dob != null) datesOfBirth.add(dob);

        List<String> placesOfBirth = new ArrayList<>();
        String pob = joinNonBlank(f.birthPlace, f.birthCountry);
        if (pob != null) placesOfBirth.add(pob);

        List<SanctionsProgram> programs = new ArrayList<>();
        if (f.program != null) {
            programs.add(new SanctionsProgram(f.program, f.program, ListSource.LV_FIU));
        } else {
            programs.add(
                    new SanctionsProgram(
                            "LV FIU", "Latvia FIU National Sanctions", ListSource.LV_FIU));
        }

        LocalDate listed = parseDateSafe(f.listedOn);
        Instant listedDate =
                listed != null ? listed.atStartOfDay(ZoneOffset.UTC).toInstant() : null;

        String entityId = f.id != null ? f.id : String.valueOf(fullName.hashCode());
        String prefix = isPerson ? "lv-person-" : "lv-org-";

        return new SanctionedEntity(
                prefix + entityId,
                entityType,
                ListSource.LV_FIU,
                primaryName,
                f.aliases,
                f.addresses,
                f.identifiers,
                f.nationalities,
                List.of(),
                datesOfBirth,
                placesOfBirth,
                f.remark,
                programs,
                listedDate,
                Instant.now());
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + ", " + b;
    }

    private static String buildFullName(
            String wholeName, String firstName, String middleName, String lastName) {
        if (wholeName != null) return wholeName;
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {firstName, middleName, lastName}) {
            if (part != null) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(part);
            }
        }
        return sb.isEmpty() ? null : sb.toString();
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
        if (dateStr == null) return null;
        String cleaned = dateStr.strip();
        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException e) {
            /* try next */
        }
        // Latvian format, often with a trailing dot: 21.04.1964.
        String withoutTrailingDot =
                cleaned.endsWith(".") ? cleaned.substring(0, cleaned.length() - 1) : cleaned;
        try {
            return LocalDate.parse(withoutTrailingDot, DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
