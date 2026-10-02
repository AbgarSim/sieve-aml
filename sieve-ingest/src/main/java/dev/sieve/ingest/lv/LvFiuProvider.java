package dev.sieve.ingest.lv;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.io.ByteArrayInputStream;
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
 * Fetches and parses the Latvia FIU (Finanšu izlūkošanas dienests) national sanctions list.
 *
 * <p>Published by the Latvian Financial Intelligence Unit as XML. Contains national sanctions
 * designations expanding on EU regulations. Typically contains ~200 entities.
 *
 * @see <a href="https://sankcijas.fid.gov.lv">Latvia FIU Sanctions</a>
 */
public final class LvFiuProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://sankcijas.fid.gov.lv/files/LV_national_v2.xml";

    public LvFiuProvider() {
        super(ListSource.LV_FIU, URI.create(DEFAULT_URL), "application/xml");
    }

    public LvFiuProvider(URI sourceUri) {
        super(ListSource.LV_FIU, sourceUri, "application/xml");
    }

    public LvFiuProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.LV_FIU, sourceUri, "application/xml", httpClient, Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
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

    private SanctionedEntity parseEntity(XMLStreamReader reader) throws XMLStreamException {
        String id = null;
        String type = null;
        String wholeName = null;
        String firstName = null;
        String middleName = null;
        String lastName = null;
        String birthDate = null;
        String birthCountry = null;
        String birthCountryCode = null;
        String nationality = null;
        String listedOn = null;
        String program = null;
        String reason = null;
        String sourceUrl = null;
        List<NameInfo> aliases = new ArrayList<>();

        // Track which sub-element we're in
        boolean inName = false;
        boolean inBirth = false;
        boolean inCitizen = false;
        boolean inAlias = false;

        String aliasWholeName = null;
        String aliasFirstName = null;
        String aliasMiddleName = null;
        String aliasLastName = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName();
                switch (elem) {
                    case "Id" -> {
                        if (!inName && !inBirth && !inCitizen && !inAlias) id = readText(reader);
                    }
                    case "Type" -> type = readText(reader);
                    case "Name" -> inName = true;
                    case "WholeName" -> {
                        if (inName && !inAlias) wholeName = readText(reader);
                    }
                    case "FirstName" -> {
                        if (inName && !inAlias) firstName = readText(reader);
                    }
                    case "MiddleName" -> {
                        if (inName && !inAlias) middleName = readText(reader);
                    }
                    case "LastName" -> {
                        if (inName && !inAlias) lastName = readText(reader);
                    }
                    case "Birth" -> inBirth = true;
                    case "BirthDate" -> {
                        if (inBirth) birthDate = readText(reader);
                    }
                    case "BirthCountry" -> {
                        if (inBirth) birthCountry = readText(reader);
                    }
                    case "BirthCountryIso2Code" -> {
                        if (inBirth) birthCountryCode = readText(reader);
                    }
                    case "Citizen" -> inCitizen = true;
                    case "CitizenCountry" -> {
                        if (inCitizen) nationality = readText(reader);
                    }
                    case "Alias" -> {
                        inAlias = true;
                        aliasWholeName = null;
                        aliasFirstName = null;
                        aliasMiddleName = null;
                        aliasLastName = null;
                    }
                    case "AliasWholeName" -> {
                        if (inAlias) aliasWholeName = readText(reader);
                    }
                    case "AliasFirstName" -> {
                        if (inAlias) aliasFirstName = readText(reader);
                    }
                    case "AliasMiddleName" -> {
                        if (inAlias) aliasMiddleName = readText(reader);
                    }
                    case "AliasLastName" -> {
                        if (inAlias) aliasLastName = readText(reader);
                    }
                    case "ListedOn" -> listedOn = readText(reader);
                    case "Program" -> program = readText(reader);
                    case "Remark" -> reason = readText(reader);
                    case "Link" -> sourceUrl = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String elem = reader.getLocalName();
                switch (elem) {
                    case "Entity" -> {
                        // End of this entity
                        return buildEntity(
                                id,
                                type,
                                wholeName,
                                firstName,
                                middleName,
                                lastName,
                                birthDate,
                                birthCountry,
                                birthCountryCode,
                                nationality,
                                listedOn,
                                program,
                                reason,
                                aliases);
                    }
                    case "Name" -> inName = false;
                    case "Birth" -> inBirth = false;
                    case "Citizen" -> inCitizen = false;
                    case "Alias" -> {
                        inAlias = false;
                        String aliasFullName =
                                buildFullName(
                                        aliasWholeName,
                                        aliasFirstName,
                                        aliasMiddleName,
                                        aliasLastName);
                        if (aliasFullName != null && !aliasFullName.isBlank()) {
                            aliases.add(
                                    new NameInfo(
                                            aliasFullName,
                                            aliasFirstName,
                                            aliasLastName,
                                            null,
                                            null,
                                            NameType.AKA,
                                            null,
                                            null));
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

    private SanctionedEntity buildEntity(
            String id,
            String type,
            String wholeName,
            String firstName,
            String middleName,
            String lastName,
            String birthDate,
            String birthCountry,
            String birthCountryCode,
            String nationality,
            String listedOn,
            String program,
            String reason,
            List<NameInfo> aliases) {

        // fp = natural person, jp = legal person
        boolean isPerson = type != null && type.equalsIgnoreCase("fp");
        EntityType entityType = isPerson ? EntityType.INDIVIDUAL : EntityType.ENTITY;

        String fullName = buildFullName(wholeName, firstName, middleName, lastName);
        if (fullName == null || fullName.isBlank()) return null;

        NameInfo primaryName =
                new NameInfo(
                        fullName, firstName, lastName, null, null, NameType.PRIMARY, null, null);

        List<LocalDate> datesOfBirth = new ArrayList<>();
        if (birthDate != null && !birthDate.isBlank()) {
            LocalDate dob = parseDateSafe(birthDate);
            if (dob != null) datesOfBirth.add(dob);
        }

        List<String> nationalities = new ArrayList<>();
        if (nationality != null && !nationality.isBlank()) nationalities.add(nationality.strip());

        List<String> placesOfBirth = new ArrayList<>();
        if (birthCountry != null && !birthCountry.isBlank()) {
            placesOfBirth.add(birthCountry.strip());
        }

        List<SanctionsProgram> programs = new ArrayList<>();
        if (program != null && !program.isBlank()) {
            programs.add(new SanctionsProgram(program.strip(), program.strip(), ListSource.LV_FIU));
        } else {
            programs.add(
                    new SanctionsProgram(
                            "LV FIU", "Latvia FIU National Sanctions", ListSource.LV_FIU));
        }

        Instant listedDate = null;
        if (listedOn != null && !listedOn.isBlank()) {
            LocalDate ld = parseDateSafe(listedOn);
            if (ld != null) {
                listedDate = ld.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            }
        }

        String entityId = id != null ? id : String.valueOf(fullName.hashCode());
        String prefix = isPerson ? "lv-person-" : "lv-org-";

        return new SanctionedEntity(
                prefix + entityId,
                entityType,
                ListSource.LV_FIU,
                primaryName,
                aliases,
                List.of(),
                List.of(),
                nationalities,
                List.of(),
                datesOfBirth,
                placesOfBirth,
                reason,
                programs,
                listedDate,
                Instant.now());
    }

    private static String buildFullName(
            String wholeName, String firstName, String middleName, String lastName) {
        if (wholeName != null && !wholeName.isBlank()) return wholeName.strip();
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.isBlank()) sb.append(firstName.strip());
        if (middleName != null && !middleName.isBlank()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(middleName.strip());
        }
        if (lastName != null && !lastName.isBlank()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(lastName.strip());
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
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip();
        // Try ISO format
        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException e) {
            /* try next */
        }
        // Try dd.MM.yyyy. (Latvian format with trailing dot)
        String withoutTrailingDot =
                cleaned.endsWith(".") ? cleaned.substring(0, cleaned.length() - 1) : cleaned;
        try {
            return LocalDate.parse(
                    withoutTrailingDot, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } catch (DateTimeParseException e) {
            /* try next */
        }
        // Try dd/MM/yy
        try {
            return LocalDate.parse(
                    cleaned, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
