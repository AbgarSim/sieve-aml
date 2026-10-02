package dev.sieve.ingest.ofac;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.AbstractListProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fetches and parses the OFAC Consolidated non-SDN XML list.
 *
 * <p>Covers SSI, NS-CMIC, NS-MBS, CAPTA, and PLC lists. Uses the same XML schema as the SDN list.
 * Typically contains ~1,200 entities.
 *
 * @see <a
 *     href="https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/CONS_PRIM.XML">
 *     OFAC Consolidated non-SDN XML</a>
 */
public final class OfacNonSdnProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/CONSOLIDATED.XML";
    private static final DateTimeFormatter OFAC_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy");

    public OfacNonSdnProvider() {
        super(ListSource.OFAC_NONSDN, URI.create(DEFAULT_URL), "application/xml");
    }

    public OfacNonSdnProvider(URI sourceUri) {
        super(ListSource.OFAC_NONSDN, sourceUri, "application/xml");
    }

    public OfacNonSdnProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.OFAC_NONSDN,
                sourceUri,
                "application/xml",
                httpClient,
                Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream input = new ByteArrayInputStream(responseBody)) {
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
                    "Failed to parse OFAC non-SDN XML: " + e.getMessage(),
                    ListSource.OFAC_NONSDN,
                    e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "IO error reading OFAC non-SDN XML: " + e.getMessage(),
                    ListSource.OFAC_NONSDN,
                    e);
        }
        return entities;
    }

    private SanctionedEntity parseSdnEntry(XMLStreamReader reader) throws XMLStreamException {
        String uid = null;
        String firstName = null;
        String lastName = null;
        String sdnType = null;
        String remarks = null;
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
                switch (reader.getLocalName()) {
                    case "uid" -> uid = readText(reader);
                    case "firstName" -> firstName = readText(reader);
                    case "lastName" -> lastName = readText(reader);
                    case "sdnType" -> sdnType = readText(reader);
                    case "remarks" -> remarks = readText(reader);
                    case "programList" -> programs = parseProgramList(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "sdnEntry".equals(reader.getLocalName())) {
                break;
            }
        }

        if (uid == null || lastName == null) return null;

        String fullName = firstName != null ? lastName + ", " + firstName : lastName;
        EntityType entityType = mapSdnType(sdnType);
        NameInfo primaryName =
                new NameInfo(
                        fullName,
                        firstName,
                        lastName,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);

        return new SanctionedEntity(
                "ofac-nonsdn-" + uid,
                entityType,
                ListSource.OFAC_NONSDN,
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
                String code = readText(reader);
                if (code != null && !code.isBlank()) {
                    programs.add(new SanctionsProgram(code.strip(), null, ListSource.OFAC_NONSDN));
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "programList".equals(reader.getLocalName())) {
                break;
            }
        }
        return programs;
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

    private static EntityType mapSdnType(String sdnType) {
        if (sdnType == null) return EntityType.INDIVIDUAL;
        return switch (sdnType.strip().toLowerCase()) {
            case "individual" -> EntityType.INDIVIDUAL;
            case "entity" -> EntityType.ENTITY;
            case "vessel" -> EntityType.VESSEL;
            case "aircraft" -> EntityType.AIRCRAFT;
            default -> EntityType.ENTITY;
        };
    }
}
