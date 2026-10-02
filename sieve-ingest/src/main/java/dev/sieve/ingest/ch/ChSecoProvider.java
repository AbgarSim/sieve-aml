package dev.sieve.ingest.ch;

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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fetches and parses the Swiss SECO sanctions list.
 *
 * <p>Published by the State Secretariat for Economic Affairs (SECO) as XML. Very comprehensive,
 * covering ~30 sanctions programs with ~8,500 entities.
 *
 * @see <a
 *     href="https://www.seco.admin.ch/seco/en/home/Aussenwirtschaftspolitik_Wirtschaftliche_Zusammenarbeit/Wirtschaftsbeziehungen/Exportkontrollen-und-Sanktionen/Sanktionen-Embargos.html">
 *     SECO Sanctions</a>
 */
public final class ChSecoProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://www.sesam.search.admin.ch/sesam-search-web/pages/downloadXmlGesamtliste.xhtml?lang=en&action=downloadXmlGesamtlisteAction";

    public ChSecoProvider() {
        super(ListSource.CH_SECO, URI.create(DEFAULT_URL), "application/xml");
    }

    public ChSecoProvider(URI sourceUri) {
        super(ListSource.CH_SECO, sourceUri, "application/xml");
    }

    public ChSecoProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.CH_SECO,
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

            // Track current sanctions-program info
            String currentProgram = null;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String elem = reader.getLocalName();
                    if ("sanctions-program".equals(elem)) {
                        currentProgram = null;
                    } else if ("program-key".equals(elem)) {
                        String lang = attrVal(reader, "lang");
                        String key = readText(reader);
                        if ("eng".equals(lang) && key != null) {
                            currentProgram = key;
                        }
                    } else if ("target".equals(elem)) {
                        SanctionedEntity entity = parseTarget(reader, currentProgram);
                        if (entity != null) entities.add(entity);
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse Swiss SECO XML: " + e.getMessage(), ListSource.CH_SECO, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "IO error reading Swiss SECO XML: " + e.getMessage(), ListSource.CH_SECO, e);
        }
        return entities;
    }

    private SanctionedEntity parseTarget(XMLStreamReader reader, String program)
            throws XMLStreamException {
        String ssid = attrVal(reader, "ssid");
        EntityType entityType = EntityType.INDIVIDUAL;
        String familyName = null;
        String givenName = null;
        String wholeName = null;
        List<NameInfo> aliases = new ArrayList<>();
        List<Address> addresses = new ArrayList<>();
        List<Identifier> identifiers = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> placesOfBirth = new ArrayList<>();
        boolean inMainIdentity = false;
        boolean inModification = false;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName();
                switch (elem) {
                    case "entity" -> entityType = EntityType.ENTITY;
                    case "individual" -> entityType = EntityType.INDIVIDUAL;
                    case "modification" -> inModification = true;
                    case "identity" -> {
                        if (!inModification && "true".equals(attrVal(reader, "main"))) {
                            inMainIdentity = true;
                        } else {
                            skipElement(reader);
                        }
                    }
                    case "name" -> {
                        if (inMainIdentity && !inModification) {
                            String nameType = attrVal(reader, "name-type");
                            NameParts np = parseName(reader, aliases);
                            if ("primary-name".equals(nameType)) {
                                familyName = np.family;
                                givenName = np.given;
                                wholeName = np.whole;
                            } else {
                                String aName =
                                        np.whole != null
                                                ? np.whole
                                                : (np.given != null && np.family != null
                                                        ? np.given + " " + np.family
                                                        : np.family);
                                if (aName != null && !aName.isBlank()) {
                                    aliases.add(
                                            new NameInfo(
                                                    aName,
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    NameType.AKA,
                                                    null,
                                                    null));
                                }
                            }
                        } else {
                            skipElement(reader);
                        }
                    }
                    case "day-month-year" -> {
                        if (inMainIdentity && !inModification) {
                            String day = attrVal(reader, "day");
                            String month = attrVal(reader, "month");
                            String year = attrVal(reader, "year");
                            LocalDate dob = parseDmy(day, month, year);
                            if (dob != null) datesOfBirth.add(dob);
                        }
                    }
                    case "place-of-birth" -> {
                        if (inMainIdentity && !inModification) {
                            String pob = readNestedText(reader, "place-of-birth");
                            if (pob != null && !pob.isBlank()) placesOfBirth.add(pob.strip());
                        } else {
                            skipElement(reader);
                        }
                    }
                    case "nationality" -> {
                        if (inMainIdentity && !inModification) {
                            String nat = readNestedText(reader, "nationality");
                            if (nat != null && !nat.isBlank()) nationalities.add(nat.strip());
                        } else {
                            skipElement(reader);
                        }
                    }
                    case "address" -> {
                        if (inMainIdentity && !inModification) {
                            Address addr = parseAddress(reader);
                            if (addr != null) addresses.add(addr);
                        } else {
                            skipElement(reader);
                        }
                    }
                    case "identification-document" -> {
                        if (inMainIdentity && !inModification) {
                            Identifier ident = parseIdentification(reader);
                            if (ident != null) identifiers.add(ident);
                        } else {
                            skipElement(reader);
                        }
                    }
                    default -> {
                        /* let it continue */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String elem = reader.getLocalName();
                if ("identity".equals(elem)) {
                    inMainIdentity = false;
                } else if ("modification".equals(elem)) {
                    inModification = false;
                } else if ("target".equals(elem)) {
                    break;
                }
            }
        }

        // Build full name
        String fullName = wholeName;
        if (fullName == null) {
            if (familyName != null) {
                fullName = givenName != null ? givenName + " " + familyName : familyName;
            } else {
                return null;
            }
        }
        if (ssid == null) ssid = String.valueOf(fullName.hashCode());

        NameInfo primaryName =
                new NameInfo(
                        fullName, givenName, familyName, null, null, NameType.PRIMARY, null, null);

        List<SanctionsProgram> programs = new ArrayList<>();
        if (program != null && !program.isBlank()) {
            programs.add(new SanctionsProgram(program, null, ListSource.CH_SECO));
        }

        return new SanctionedEntity(
                "ch-" + ssid,
                entityType,
                ListSource.CH_SECO,
                primaryName,
                aliases,
                addresses,
                identifiers,
                nationalities,
                List.of(),
                datesOfBirth,
                placesOfBirth,
                null,
                programs,
                null,
                Instant.now());
    }

    private static class NameParts {
        String family;
        String given;
        String whole;
    }

    private NameParts parseName(XMLStreamReader reader, List<NameInfo> aliases)
            throws XMLStreamException {
        NameParts parts = new NameParts();
        List<String> spellingVariants = new ArrayList<>();

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName();
                if ("name-part".equals(elem)) {
                    String partType = attrVal(reader, "name-part-type");
                    parseNamePart(reader, partType, parts, spellingVariants);
                } else {
                    skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "name".equals(reader.getLocalName())) {
                break;
            }
        }

        // Add spelling variants as aliases
        for (String sv : spellingVariants) {
            if (sv != null && !sv.isBlank()) {
                aliases.add(new NameInfo(sv, null, null, null, null, NameType.AKA, null, null));
            }
        }
        return parts;
    }

    private void parseNamePart(
            XMLStreamReader reader, String partType, NameParts parts, List<String> spellingVariants)
            throws XMLStreamException {
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String elem = reader.getLocalName();
                if ("value".equals(elem)) {
                    String val = readText(reader);
                    if (val != null && partType != null) {
                        switch (partType) {
                            case "family-name" -> parts.family = val;
                            case "given-name" -> parts.given = val;
                            case "whole-name" -> parts.whole = val;
                            case "father-name" -> {
                                /* skip */
                            }
                        }
                    }
                } else if ("spelling-variant".equals(elem)) {
                    String script = attrVal(reader, "script");
                    String sv = readText(reader);
                    // Only take Latin-script spelling variants as aliases
                    if ("LATN".equals(script) && sv != null && !sv.isBlank()) {
                        spellingVariants.add(sv);
                    }
                } else {
                    skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "name-part".equals(reader.getLocalName())) {
                break;
            }
        }
    }

    private Address parseAddress(XMLStreamReader reader) throws XMLStreamException {
        String street = null, city = null, country = null, zip = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "address-details" -> street = readText(reader);
                    case "location" -> city = readText(reader);
                    case "country" -> country = readText(reader);
                    case "zip-code" -> zip = readText(reader);
                    default -> skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "address".equals(reader.getLocalName())) {
                break;
            }
        }
        if (street == null && city == null && country == null) return null;
        StringBuilder full = new StringBuilder();
        if (street != null) full.append(street);
        if (city != null) {
            if (!full.isEmpty()) full.append(", ");
            full.append(city);
        }
        if (country != null) {
            if (!full.isEmpty()) full.append(", ");
            full.append(country);
        }
        return new Address(
                street, city, null, zip, country, full.isEmpty() ? null : full.toString());
    }

    private Identifier parseIdentification(XMLStreamReader reader) throws XMLStreamException {
        String type = null;
        String number = null;
        String country = null;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (reader.getLocalName()) {
                    case "type" -> type = readText(reader);
                    case "number" -> number = readText(reader);
                    case "issuer" -> country = readText(reader);
                    default -> skipElement(reader);
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "identification-document".equals(reader.getLocalName())) {
                break;
            }
        }
        if (number == null || number.isBlank()) return null;
        return new Identifier(mapIdType(type), number.strip(), country, null);
    }

    private static IdentifierType mapIdType(String type) {
        if (type == null) return IdentifierType.OTHER;
        String n = type.strip().toLowerCase();
        if (n.contains("passport")) return IdentifierType.PASSPORT;
        if (n.contains("national")) return IdentifierType.NATIONAL_ID;
        if (n.contains("tax")) return IdentifierType.TAX_ID;
        return IdentifierType.OTHER;
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

    /** Reads all text content inside nested elements until the named end element. */
    private String readNestedText(XMLStreamReader reader, String endElement)
            throws XMLStreamException {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                sb.append(reader.getText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        String text = sb.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private void skipElement(XMLStreamReader reader) throws XMLStreamException {
        int depth = 1;
        while (reader.hasNext() && depth > 0) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) depth++;
            else if (event == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    private static LocalDate parseDmy(String day, String month, String year) {
        if (year == null) return null;
        try {
            int y = Integer.parseInt(year);
            int m = month != null ? Integer.parseInt(month) : 1;
            int d = day != null ? Integer.parseInt(day) : 1;
            return LocalDate.of(y, m, d);
        } catch (Exception e) {
            return null;
        }
    }
}
