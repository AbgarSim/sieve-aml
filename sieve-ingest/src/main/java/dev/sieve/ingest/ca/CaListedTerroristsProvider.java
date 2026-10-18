package dev.sieve.ingest.ca;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import dev.sieve.ingest.StableIds;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Fetches Canada's listed terrorist entities, the groups the Governor in Council lists under
 * section 83.05 of the Criminal Code, published by Public Safety Canada as an Atom feed.
 *
 * <p>This list is separate from the sanctions under SEMA and FACFOA that {@link
 * CanadaConsolidatedProvider} reads. Each feed entry is one group: its title is the name, its
 * summary lists the other names it goes by, its content describes the group and its published date
 * is the date of listing. A title ending in an acronym in brackets, such as {@code Abu Nidal
 * Organization (ANO)}, also gives the name without it and the acronym as aliases. Ids are {@code
 * ca-lte-} followed by a hash of the title, because the feed's entry numbers follow alphabetical
 * order and shift when a group is added.
 *
 * @see <a
 *     href="https://www.publicsafety.gc.ca/cnt/ntnl-scrt/cntr-trrrsm/lstd-ntts/crrnt-lstd-ntts-en.aspx">Currently
 *     listed entities</a>
 */
public final class CaListedTerroristsProvider extends AbstractListProvider {

    /** The English Atom feed of currently listed entities. */
    public static final String DEFAULT_URL =
            "https://www.publicsafety.gc.ca/cnt/_xml/lstd-ntts-eng.xml";

    private static final Pattern ACRONYM = Pattern.compile("^(.+?)\\s*\\(([^()]+)\\)$");
    private static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "Criminal Code s. 83.05",
                    "Listed terrorist entities under section 83.05 of the Criminal Code",
                    ListSource.CA_LISTED_TERRORISTS);

    /** The feed answers 406 to a bare {@code application/xml}, so any type is accepted. */
    private static final String ACCEPT = "application/atom+xml, application/xml;q=0.9, */*;q=0.8";

    /** Creates a provider that reads the published feed. */
    public CaListedTerroristsProvider() {
        super(ListSource.CA_LISTED_TERRORISTS, URI.create(DEFAULT_URL), ACCEPT);
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the feed from
     * @param httpClient the HTTP client to use for requests
     */
    public CaListedTerroristsProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.CA_LISTED_TERRORISTS,
                sourceUri,
                ACCEPT,
                httpClient,
                Duration.ofSeconds(60));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        List<SanctionedEntity> entities = new ArrayList<>();
        StableIds ids = new StableIds("ca-lte-");
        Instant now = Instant.now();
        try {
            XMLStreamReader reader =
                    factory.createXMLStreamReader(new ByteArrayInputStream(responseBody));
            Map<String, String> entry = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event != XMLStreamConstants.START_ELEMENT
                        && event != XMLStreamConstants.END_ELEMENT) {
                    continue;
                }
                String name = reader.getLocalName();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (name.equals("entry")) {
                        entry = new HashMap<>();
                    } else if (entry != null
                            && Set.of("title", "summary", "content", "published", "updated")
                                    .contains(name)) {
                        entry.put(name, text(reader));
                    }
                } else if (name.equals("entry") && entry != null) {
                    SanctionedEntity entity = toEntity(entry, ids, now);
                    if (entity != null) {
                        entities.add(entity);
                    }
                    entry = null;
                }
            }
            reader.close();
        } catch (XMLStreamException e) {
            throw new ListIngestionException(
                    "Failed to parse the listed terrorist entities feed: " + e.getMessage(),
                    ListSource.CA_LISTED_TERRORISTS,
                    e);
        }
        return entities;
    }

    /**
     * Reads all text inside the current element, including text inside child elements: some
     * entries carry their content as XHTML paragraphs rather than plain text.
     */
    private static String text(XMLStreamReader reader) throws XMLStreamException {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    text.append(' ');
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    depth--;
                    text.append(' ');
                }
                case XMLStreamConstants.CHARACTERS,
                        XMLStreamConstants.CDATA,
                        XMLStreamConstants.SPACE,
                        XMLStreamConstants.ENTITY_REFERENCE -> text.append(reader.getText());
                default -> {}
            }
        }
        return text.toString().replaceAll("\\s+", " ").replaceAll(" ([.,;:])", "$1").strip();
    }

    private static SanctionedEntity toEntity(
            Map<String, String> entry, StableIds ids, Instant now) {
        String title = blankToNull(entry.get("title"));
        if (title == null) {
            return null;
        }
        Set<String> aliasNames = new LinkedHashSet<>();
        Matcher acronym = ACRONYM.matcher(title);
        if (acronym.matches()) {
            aliasNames.add(acronym.group(1).strip());
            aliasNames.add(acronym.group(2).strip());
        }
        aliasNames.addAll(aliases(entry.get("summary")));
        aliasNames.removeIf(a -> a.equalsIgnoreCase(title));

        List<NameInfo> aliases = new ArrayList<>();
        for (String alias : aliasNames) {
            aliases.add(new NameInfo(alias, null, null, null, null, NameType.AKA, null, null));
        }
        LocalDate listed = date(entry.get("published"));
        LocalDate updated = date(entry.get("updated"));
        return new SanctionedEntity(
                ids.next(title),
                EntityType.ORGANIZATION,
                ListSource.CA_LISTED_TERRORISTS,
                new NameInfo(
                        title, null, null, null, null, NameType.PRIMARY, NameStrength.STRONG, null),
                aliases,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                blankToNull(entry.get("content")),
                List.of(PROGRAM),
                listed == null ? null : listed.atStartOfDay(ZoneOffset.UTC).toInstant(),
                updated == null ? now : updated.atStartOfDay(ZoneOffset.UTC).toInstant(),
                Set.of(RiskTopic.SANCTION),
                List.of());
    }

    /**
     * Splits the summary into names. Names are separated by semicolons, or by commas when the
     * summary has no semicolon; a leading "and" and the closing full stop are dropped.
     *
     * @param summary the entry's summary, may be {@code null}
     * @return the names in order
     */
    static List<String> aliases(String summary) {
        String text = blankToNull(summary);
        if (text == null) {
            return List.of();
        }
        String separator = text.contains(";") ? ";" : ",";
        List<String> names = new ArrayList<>();
        for (String part : text.split(separator)) {
            String name =
                    part.strip().replaceFirst("(?i)^and\\s+", "").replaceFirst("\\.$", "").strip();
            if (!name.isEmpty() && !name.toLowerCase(Locale.ROOT).equals("none")) {
                names.add(name);
            }
        }
        return names;
    }

    private static LocalDate date(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
