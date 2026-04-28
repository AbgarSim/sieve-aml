package dev.sieve.ingest.ca;

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
 * Fetches and parses the Canadian consolidated sanctions list (SEMA, FACFOA, and Terrorists).
 *
 * <p>Published by Global Affairs Canada as XML. Covers three separate legislative lists combined
 * into one download. Typically contains ~5,400 entities.
 *
 * @see <a href="https://www.international.gc.ca/world-monde/international_relations-relations_internationales/sanctions/consolidated-consolide.aspx">
 *     Canadian Sanctions</a>
 */
public final class CanadaConsolidatedProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://www.international.gc.ca/world-monde/assets/office_docs/international_relations-relations_internationales/sanctions/sema-lmes.xml";

    public CanadaConsolidatedProvider() {
        super(ListSource.CA_CONSOLIDATED, URI.create(DEFAULT_URL), "*/*");
    }

    public CanadaConsolidatedProvider(URI sourceUri) {
        super(ListSource.CA_CONSOLIDATED, sourceUri, "*/*");
    }

    public CanadaConsolidatedProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.CA_CONSOLIDATED, sourceUri, "*/*", httpClient,
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
                        && "record".equalsIgnoreCase(reader.getLocalName())) {
                    SanctionedEntity entity = parseRecord(reader);
                    if (entity != null) entities.add(entity);
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse Canada XML: " + e.getMessage(),
                    ListSource.CA_CONSOLIDATED, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "IO error reading Canada XML: " + e.getMessage(),
                    ListSource.CA_CONSOLIDATED, e);
        }
        return entities;
    }

    private SanctionedEntity parseRecord(XMLStreamReader reader) throws XMLStreamException {
        String lastName = null;
        String givenName = null;
        String country = null;
        String schedule = null;
        String item = null;
        List<NameInfo> aliases = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        EntityType entityType = EntityType.INDIVIDUAL;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName().toLowerCase();
                switch (elem) {
                    case "lastname", "last_name", "familyname", "name" -> lastName = readText(reader);
                    case "givenname", "given_name", "firstname" -> givenName = readText(reader);
                    case "country" -> country = readText(reader);
                    case "schedule" -> schedule = readText(reader);
                    case "item" -> item = readText(reader);
                    case "dateofbirth", "date_of_birth", "dob" -> {
                        LocalDate dob = parseDateSafe(readText(reader));
                        if (dob != null) datesOfBirth.add(dob);
                    }
                    case "aliases", "alias" -> {
                        String aliasText = readText(reader);
                        if (aliasText != null && !aliasText.isBlank()) {
                            for (String a : aliasText.split(";")) {
                                String trimmed = a.strip();
                                if (!trimmed.isEmpty()) {
                                    aliases.add(new NameInfo(
                                            trimmed, null, null, null, null,
                                            NameType.AKA, null, null));
                                }
                            }
                        }
                    }
                    case "entitytype", "entity_type", "type" -> {
                        String type = readText(reader);
                        if (type != null && type.toLowerCase().contains("entit")) {
                            entityType = EntityType.ENTITY;
                        }
                    }
                    default -> { /* skip */ }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "record".equalsIgnoreCase(reader.getLocalName())) {
                break;
            }
        }

        if (lastName == null || lastName.isBlank()) return null;
        String fullName = givenName != null && !givenName.isBlank()
                ? givenName + " " + lastName : lastName;

        String id = (schedule != null ? schedule : "") + "-" + (item != null ? item : "")
                + "-" + fullName.hashCode();

        NameInfo primaryName = new NameInfo(
                fullName, givenName, lastName, null, null, NameType.PRIMARY, null, null);

        List<SanctionsProgram> programs = new ArrayList<>();
        if (schedule != null && !schedule.isBlank()) {
            programs.add(new SanctionsProgram(
                    schedule.strip(), "Canadian " + schedule, ListSource.CA_CONSOLIDATED));
        }

        List<String> nationalities = new ArrayList<>();
        if (country != null && !country.isBlank()) nationalities.add(country.strip());

        return new SanctionedEntity(
                "ca-" + id, entityType, ListSource.CA_CONSOLIDATED,
                primaryName, aliases, List.of(), List.of(),
                nationalities, List.of(), datesOfBirth, List.of(),
                null, programs, null, Instant.now());
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
        try {
            return LocalDate.parse(dateStr.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
