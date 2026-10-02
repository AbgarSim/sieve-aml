package dev.sieve.ingest.eu;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fetches and parses the EU Travel Bans list.
 *
 * <p>Complements the EU Financial Sanctions File (FSF). Published as XML. Typically contains
 * ~4,184 entities.
 *
 * @see <a href="https://webgate.ec.europa.eu/fsd/fsf">EU Financial Sanctions</a>
 */
public final class EuTravelBansProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw&type=TravelBan";

    public EuTravelBansProvider() {
        super(ListSource.EU_TRAVEL_BANS, URI.create(DEFAULT_URL), "application/xml");
    }

    public EuTravelBansProvider(URI sourceUri) {
        super(ListSource.EU_TRAVEL_BANS, sourceUri, "application/xml");
    }

    public EuTravelBansProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.EU_TRAVEL_BANS, sourceUri, "application/xml", httpClient,
                Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        // Uses the same XML schema as the EU Consolidated list
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
                        && "sanctionEntity".equals(reader.getLocalName())) {
                    SanctionedEntity entity = parseSanctionEntity(reader);
                    if (entity != null) entities.add(entity);
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse EU Travel Bans XML: " + e.getMessage(),
                    ListSource.EU_TRAVEL_BANS, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "IO error reading EU Travel Bans XML: " + e.getMessage(),
                    ListSource.EU_TRAVEL_BANS, e);
        }
        return entities;
    }

    private SanctionedEntity parseSanctionEntity(XMLStreamReader reader)
            throws XMLStreamException {
        String id = attrVal(reader, "logicalId");
        if (id == null) id = attrVal(reader, "euReferenceNumber");
        String fullName = null, givenName = null, familyName = null;
        EntityType entityType = EntityType.INDIVIDUAL;
        List<NameInfo> aliases = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> placesOfBirth = new ArrayList<>();
        List<SanctionsProgram> programs = new ArrayList<>();

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName();
                switch (elem) {
                    case "nameAlias" -> {
                        String wholeName = attrVal(reader, "wholeName");
                        String fn = attrVal(reader, "firstName");
                        String ln = attrVal(reader, "lastName");
                        if (fullName == null && wholeName != null) {
                            fullName = wholeName;
                            givenName = fn;
                            familyName = ln;
                        } else if (wholeName != null && !wholeName.isBlank()) {
                            aliases.add(new NameInfo(
                                    wholeName, fn, ln, null, null, NameType.AKA, null, null));
                        }
                        skipElement(reader);
                    }
                    case "birthdate" -> {
                        String d = attrVal(reader, "birthdate");
                        LocalDate dob = parseDateSafe(d);
                        if (dob != null) datesOfBirth.add(dob);
                        skipElement(reader);
                    }
                    case "citizenship" -> {
                        String cc = attrVal(reader, "countryIso2Code");
                        if (cc != null) nationalities.add(cc);
                        skipElement(reader);
                    }
                    case "subjectType" -> {
                        String code = attrVal(reader, "code");
                        if (code != null && code.toLowerCase().contains("enterprise"))
                            entityType = EntityType.ENTITY;
                        skipElement(reader);
                    }
                    case "regulation" -> {
                        String prog = attrVal(reader, "programme");
                        if (prog != null && !prog.isBlank())
                            programs.add(new SanctionsProgram(
                                    prog.strip(), null, ListSource.EU_TRAVEL_BANS));
                        skipElement(reader);
                    }
                    default -> skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "sanctionEntity".equals(reader.getLocalName())) {
                break;
            }
        }

        if (fullName == null) return null;
        if (id == null) id = String.valueOf(fullName.hashCode());

        NameInfo primaryName = new NameInfo(
                fullName, givenName, familyName, null, null, NameType.PRIMARY, null, null);

        return new SanctionedEntity(
                "eu-tb-" + id, entityType, ListSource.EU_TRAVEL_BANS,
                primaryName, aliases, List.of(), List.of(),
                nationalities, List.of(), datesOfBirth, placesOfBirth,
                null, programs, null, Instant.now());
    }

    private static String attrVal(XMLStreamReader reader, String name) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            if (name.equalsIgnoreCase(reader.getAttributeLocalName(i))) {
                String val = reader.getAttributeValue(i);
                return (val != null && !val.isBlank()) ? val.strip() : null;
            }
        }
        return null;
    }

    private void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) depth++;
            else if (event == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try { return LocalDate.parse(dateStr.strip()); }
        catch (DateTimeParseException e) { return null; }
    }
}
