package dev.sieve.ingest.ca;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.geo.CountryNormalizer;
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
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fetches and parses the Canadian consolidated sanctions list (SEMA, FACFOA, and Terrorists).
 *
 * <p>Published by Global Affairs Canada as XML. Covers three separate legislative lists combined
 * into one download. Typically contains ~5,700 records.
 *
 * @see <a
 *     href="https://www.international.gc.ca/world-monde/international_relations-relations_internationales/sanctions/consolidated-consolide.aspx">
 *     Canadian Sanctions</a>
 */
public final class CanadaConsolidatedProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://www.international.gc.ca/world-monde/assets/office_docs/international_relations-relations_internationales/sanctions/sema-lmes.xml";

    private static final Pattern ALIAS_SEPARATOR = Pattern.compile(";|,\\s+|/\\s*");

    /** Script labels such as {@code "Russian: "} in front of an alias. */
    private static final Pattern ALIAS_LABEL = Pattern.compile("^\\p{L}+:\\s*");

    private static final Pattern IMO = Pattern.compile("(?i)^(?:IMO\\s*)?(\\d{7})$");

    private static final CountryNormalizer COUNTRIES = CountryNormalizer.standard();

    public CanadaConsolidatedProvider() {
        super(ListSource.CA_CONSOLIDATED, URI.create(DEFAULT_URL), "*/*");
    }

    public CanadaConsolidatedProvider(URI sourceUri) {
        super(ListSource.CA_CONSOLIDATED, sourceUri, "*/*");
    }

    public CanadaConsolidatedProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.CA_CONSOLIDATED, sourceUri, "*/*", httpClient, Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        Set<String> ids = new HashSet<>();
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
                    SanctionedEntity entity = parseRecord(reader, ids);
                    if (entity != null) entities.add(entity);
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse Canada XML: " + e.getMessage(), ListSource.CA_CONSOLIDATED, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "IO error reading Canada XML: " + e.getMessage(),
                    ListSource.CA_CONSOLIDATED,
                    e);
        }
        return entities;
    }

    /**
     * Parses one {@code <record>}. Element names are bilingual, such as {@code
     * <LastName-NomDeFamille>}, so fields are matched on the English part before the hyphen.
     */
    private SanctionedEntity parseRecord(XMLStreamReader reader, Set<String> ids)
            throws XMLStreamException {
        String lastName = null;
        String givenName = null;
        String entityOrShip = null;
        String title = null;
        String imoText = null;
        String country = null;
        String schedule = null;
        String item = null;
        String birthOrBuildDate = null;
        String listedOn = null;
        String aliasText = null;

        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (englishName(reader.getLocalName())) {
                    case "lastname" -> lastName = readText(reader);
                    case "givenname" -> givenName = readText(reader);
                    case "entityorship" -> entityOrShip = readText(reader);
                    case "titleorshiptype" -> title = englishPart(readText(reader), " | ");
                    case "shipimonumber" -> imoText = readText(reader);
                    case "country" -> country = englishPart(readText(reader), " / ");
                    case "schedule" -> schedule = readText(reader);
                    case "item" -> item = readText(reader);
                    case "dateofbirthorshipbuilddate" -> birthOrBuildDate = readText(reader);
                    case "dateoflisting" -> listedOn = readText(reader);
                    case "aliases" -> aliasText = readText(reader);
                    default -> {
                        /* skip */
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && "record".equalsIgnoreCase(reader.getLocalName())) {
                break;
            }
        }

        // A few records carry a name rather than a number in the IMO column
        Matcher imoMatch = imoText != null ? IMO.matcher(imoText) : null;
        String imo = imoMatch != null && imoMatch.matches() ? imoMatch.group(1) : null;
        if (imoText != null && imo == null) {
            aliasText = aliasText == null ? imoText : aliasText + "; " + imoText;
        }

        EntityType entityType;
        NameInfo primaryName;
        if (entityOrShip != null) {
            entityType = imo != null ? EntityType.VESSEL : EntityType.ENTITY;
            primaryName =
                    new NameInfo(
                            entityOrShip, null, null, null, null, NameType.PRIMARY, null, null);
        } else if (lastName != null || givenName != null) {
            entityType = EntityType.INDIVIDUAL;
            String fullName =
                    givenName != null && lastName != null
                            ? givenName + " " + lastName
                            : givenName != null ? givenName : lastName;
            primaryName =
                    new NameInfo(
                            fullName,
                            givenName,
                            lastName,
                            null,
                            null,
                            NameType.PRIMARY,
                            null,
                            null);
        } else {
            return null;
        }

        List<NameInfo> aliases = new ArrayList<>();
        if (aliasText != null) {
            for (String alias : ALIAS_SEPARATOR.split(aliasText)) {
                String trimmed = ALIAS_LABEL.matcher(alias.strip()).replaceFirst("").strip();
                if (!trimmed.isEmpty()) {
                    aliases.add(
                            new NameInfo(
                                    trimmed, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        List<Identifier> identifiers = new ArrayList<>();
        if (imo != null) {
            identifiers.add(new Identifier(IdentifierType.IMO_NUMBER, imo, null, null));
        }

        // The same column holds a ship's build date, which is not a date of birth
        List<LocalDate> datesOfBirth = new ArrayList<>();
        if (entityType == EntityType.INDIVIDUAL) {
            LocalDate dob = parseDateSafe(birthOrBuildDate);
            if (dob != null) datesOfBirth.add(dob);
        }

        // The "country" column names the regime: usually a country, sometimes a thematic one
        boolean isCountry = country != null && COUNTRIES.toIso2(country).isPresent();
        List<SanctionsProgram> programs = new ArrayList<>();
        if (country != null || schedule != null) {
            String regime = country != null ? country : "Canada";
            String code = schedule != null ? regime + " Schedule " + schedule : regime;
            String name = isCountry ? "Special Economic Measures (" + regime + ")" : regime;
            programs.add(new SanctionsProgram(code, name, ListSource.CA_CONSOLIDATED));
        }

        // For a country regime, the targeted country; for most records also the subject's own
        List<String> nationalities = isCountry ? List.of(country) : List.of();

        LocalDate listed = parseDateSafe(listedOn);
        Instant listedDate =
                listed != null ? listed.atStartOfDay(ZoneOffset.UTC).toInstant() : null;

        String id = uniqueId(ids, slug(country) + "-" + slug(schedule) + "-" + slug(item));

        return new SanctionedEntity(
                id,
                entityType,
                ListSource.CA_CONSOLIDATED,
                primaryName,
                aliases,
                List.of(),
                identifiers,
                nationalities,
                List.of(),
                datesOfBirth,
                List.of(),
                title,
                programs,
                listedDate,
                Instant.now());
    }

    /** Lowercased English part of a bilingual element name such as {@code Item-NumeroDarticle}. */
    static String englishName(String elementName) {
        int dash = elementName.indexOf('-');
        return (dash < 0 ? elementName : elementName.substring(0, dash)).toLowerCase(Locale.ROOT);
    }

    /** English part of a bilingual value such as {@code "Belarus / Bélarus"}. */
    static String englishPart(String value, String separator) {
        if (value == null) return null;
        int at = value.indexOf(separator);
        String english = (at < 0 ? value : value.substring(0, at)).strip();
        return english.isEmpty() ? null : english;
    }

    private static String slug(String value) {
        if (value == null) return "x";
        String slug =
                value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "x" : slug;
    }

    private static String uniqueId(Set<String> ids, String base) {
        String id = "ca-" + base;
        for (int n = 2; !ids.add(id); n++) {
            id = "ca-" + base + "-" + n;
        }
        return id;
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

    /**
     * Parses {@code yyyy-MM-dd}, {@code yyyy-MM} or {@code yyyy}; partial dates fall on the first.
     */
    static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String value = dateStr.strip();
        try {
            return switch (value.length()) {
                case 4 -> LocalDate.of(Integer.parseInt(value), 1, 1);
                case 7 -> YearMonth.parse(value).atDay(1);
                default -> LocalDate.parse(value);
            };
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }
}
