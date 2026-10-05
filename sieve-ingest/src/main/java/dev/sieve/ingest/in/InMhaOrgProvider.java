package dev.sieve.ingest.in;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.HttpClientFactory;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches the organisations India's Ministry of Home Affairs bans under the Unlawful Activities
 * (Prevention) Act, 1967 (UAPA): the terrorist organisations listed in the Act's First Schedule and
 * the associations declared unlawful under its Section 3.
 *
 * <p>The ministry publishes both only as PDF files linked from its "Banned Organisations" page, one
 * numbered entry per organisation. The provider reads the page to find the current files, extracts
 * their text with PDFBox and parses the numbered entries. Ids follow the lists' serial numbers:
 * {@code in-mha-to-<n>} for a terrorist organisation, {@code in-mha-ua-<n>} for an unlawful
 * association and {@code in-mha-ua-<n>-<k>} for the k-th organisation of an entry that groups
 * several, such as the Meitei extremist organisations.
 *
 * <p>An entry often names one organisation in several ways. Names it joins with a slash or "or" are
 * aliases of each other; the wing, fronts or factions an entry enumerates after the organisation it
 * names first become that organisation's aliases; an acronym in parentheses becomes a weak alias.
 * The First Schedule's entry that only points at the UN sanctions lists is skipped, since those
 * lists are sources of their own.
 *
 * @see <a
 *     href="https://www.mha.gov.in/en/divisionofmha/counter-terrorism-and-counter-radicalization-division/Banned-Organizations">Banned
 *     Organisations</a>
 */
public final class InMhaOrgProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(InMhaOrgProvider.class);

    private static final String DEFAULT_PAGE =
            "https://www.mha.gov.in/en/divisionofmha/counter-terrorism-and-counter-radicalization-division/Banned-Organizations";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** The two lists the page links to, each a PDF of its own. */
    enum Schedule {
        TERRORIST(
                "in-mha-to-",
                "UAPA-1",
                "Unlawful Activities (Prevention) Act, 1967, First Schedule: terrorist organisation",
                Pattern.compile("(?i)terrorist\\s+organi[sz]ations"),
                "terrorist organisations (First Schedule)"),
        UNLAWFUL(
                "in-mha-ua-",
                "UAPA-3",
                "Unlawful Activities (Prevention) Act, 1967, Section 3: unlawful association",
                Pattern.compile("(?i)unlawful\\s+associations"),
                "unlawful associations (Section 3)");

        final String idPrefix;
        final SanctionsProgram program;
        final Pattern title;
        final String description;

        Schedule(
                String idPrefix,
                String programCode,
                String programName,
                Pattern title,
                String description) {
            this.idPrefix = idPrefix;
            this.program = new SanctionsProgram(programCode, programName, ListSource.IN_MHA_ORG);
            this.title = title;
            this.description = description;
        }
    }

    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern PDF_LINK = Pattern.compile("(?i)href=\"([^\"]+\\.pdf)\"");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private static final Pattern ENTRY = Pattern.compile("^\\s*(\\d{1,3})\\.\\s+(\\S.*)$");
    private static final Pattern SUB_ITEM = Pattern.compile("^\\s*\\(([ivxl]+)\\)\\s+(\\S.*)$");
    private static final Pattern END = Pattern.compile("^\\s*\\*{3,}\\s*$");
    private static final Pattern POINTER =
            Pattern.compile("(?i)^organi[sz]ations listed in the schedule to the UN\\b.*");
    private static final Pattern SUFFIX =
            Pattern.compile(
                    "(?i)[\\s,]*(?:and\\s+)?(?:all\\s+)?(?:its\\s+)?(?:manifestations|formations)"
                            + "(?:\\s+and\\s+front\\s+organi[sz]ations)?\\s*$");
    private static final Pattern WING =
            Pattern.compile(
                    "(?i)^(.+?)\\s+and its (?:political|armed) wing,\\s*(?:also called\\s+)?the\\s+(.+)$");
    private static final Pattern FRONTS =
            Pattern.compile(
                    "(?i)^(.+?)\\s+and its associates or affiliates or fronts including\\s+(.+)$");
    private static final Pattern NAMELY =
            Pattern.compile(
                    "(?i)^(?:\\w+\\s+factions?\\s+of\\s+)?(.+?),\\s+namely[:\\-]?\\s*(.+)$");
    private static final Pattern ALTERNATIVES = Pattern.compile("\\s*/\\s*|\\s+or\\s+");
    private static final Pattern ENUMERATION = Pattern.compile(",\\s*|\\s+and\\s+");
    private static final Pattern LED_BY = Pattern.compile("(?i)\\s+led by\\s+.*$");
    private static final Pattern ACRONYM = Pattern.compile("\\(([A-Za-z][A-Za-z0-9\\-]{1,11})\\)");
    private static final Pattern BARE_ACRONYM =
            Pattern.compile("^\\(([A-Za-z][A-Za-z0-9\\-]{1,11})\\)$");
    private static final Pattern QUOTES = Pattern.compile("^[\"']+|[\"']+$");
    private static final Pattern TRAILING = Pattern.compile("(?:[.,;:]+|\\s+and)\\s*$");

    /** A hyphen the PDF sets off from the word that follows it: "Sham- Khorasan". */
    private static final Pattern SPACED_HYPHEN = Pattern.compile("(?<=\\w)- (?=\\w)");

    private static final Map<String, Integer> ROMAN =
            Map.ofEntries(
                    Map.entry("i", 1),
                    Map.entry("ii", 2),
                    Map.entry("iii", 3),
                    Map.entry("iv", 4),
                    Map.entry("v", 5),
                    Map.entry("vi", 6),
                    Map.entry("vii", 7),
                    Map.entry("viii", 8),
                    Map.entry("ix", 9),
                    Map.entry("x", 10),
                    Map.entry("xi", 11),
                    Map.entry("xii", 12));

    private final URI pageUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /** Creates a provider that reads the lists from the ministry's site. */
    public InMhaOrgProvider() {
        this(URI.create(DEFAULT_PAGE), HttpClientFactory.createTrustAllClient(CONNECT_TIMEOUT));
    }

    /**
     * Creates a provider with a custom page URI and HTTP client (for testing).
     *
     * @param pageUri the "Banned Organisations" page that links to the PDF files
     * @param httpClient the HTTP client to use for requests
     */
    public InMhaOrgProvider(URI pageUri, HttpClient httpClient) {
        this.pageUri = Objects.requireNonNull(pageUri, "pageUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.currentMetadata =
                new ListMetadata(ListSource.IN_MHA_ORG, null, null, null, pageUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.IN_MHA_ORG;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching India MHA organisations [uri={}]", pageUri);
        Instant start = Instant.now();
        try {
            String page = new String(download(pageUri, "text/html"), StandardCharsets.UTF_8);
            Map<Schedule, URI> files = fileLinks(page, pageUri);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<SanctionedEntity> entities = new ArrayList<>();
            for (Schedule schedule : Schedule.values()) {
                URI file = files.get(schedule);
                byte[] pdf = download(file, "application/pdf");
                digest.update(pdf);
                List<SanctionedEntity> parsed = parse(extractText(pdf), schedule, file);
                log.info(
                        "India MHA {}: {} entities [uri={}]",
                        schedule.description,
                        parsed.size(),
                        file);
                entities.addAll(parsed);
            }
            Instant now = Instant.now();
            currentMetadata =
                    new ListMetadata(
                            ListSource.IN_MHA_ORG,
                            now,
                            null,
                            HexFormat.of().formatHex(digest.digest()),
                            pageUri,
                            entities.size());
            log.info(
                    "India MHA organisations ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    Duration.between(start, now).toMillis());
            return entities;
        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching India MHA organisations: " + e.getMessage(),
                    ListSource.IN_MHA_ORG,
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException(
                    "India MHA organisations fetch interrupted", ListSource.IN_MHA_ORG, e);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }

    /** The page carries no ETag or modification date, so every check assumes a change. */
    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return true;
    }

    private byte[] download(URI uri, String accept)
            throws IOException, InterruptedException, ListIngestionException {
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", accept)
                        .header("User-Agent", "sieve-aml/1.0")
                        .GET()
                        .build();
        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new ListIngestionException(
                    String.format(
                            "India MHA organisations fetch failed [status=%d, uri=%s]",
                            response.statusCode(), uri),
                    ListSource.IN_MHA_ORG);
        }
        return response.body();
    }

    /**
     * Finds the PDF each list is published as: the table on the page has one row per list, with the
     * list's title and a download link.
     *
     * @param html the "Banned Organisations" page
     * @param page the page's URI, to resolve the links against
     * @return the file of each schedule
     * @throws ListIngestionException if a schedule's row or link is missing
     */
    static Map<Schedule, URI> fileLinks(String html, URI page) throws ListIngestionException {
        Map<Schedule, URI> links = new EnumMap<>(Schedule.class);
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String row = rows.group(1);
            Matcher link = PDF_LINK.matcher(row);
            if (!link.find()) {
                continue;
            }
            String title = text(row);
            for (Schedule schedule : Schedule.values()) {
                if (schedule.title.matcher(title).find()) {
                    links.putIfAbsent(schedule, page.resolve(link.group(1).replace(" ", "%20")));
                }
            }
        }
        for (Schedule schedule : Schedule.values()) {
            if (!links.containsKey(schedule)) {
                throw new ListIngestionException(
                        "India MHA organisations page has no PDF link for the "
                                + schedule.description
                                + "; the page layout may have changed",
                        ListSource.IN_MHA_ORG);
            }
        }
        return links;
    }

    /** Extracts the text of a PDF, page by page, in the order the file stores it. */
    static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /**
     * Parses the text of one list into entities: one per numbered entry, or one per organisation of
     * an entry that enumerates several under a heading, in list order.
     *
     * @param text the PDF's text
     * @param schedule the list the text is
     * @param file the PDF's URI, named in the remarks
     * @return the entities
     * @throws ListIngestionException if the text has no numbered entries
     */
    static List<SanctionedEntity> parse(String text, Schedule schedule, URI file)
            throws ListIngestionException {
        List<Entry> entries = entries(text);
        if (entries.isEmpty()) {
            throw new ListIngestionException(
                    "India MHA list of "
                            + schedule.description
                            + " has no numbered entries; the PDF layout may have changed",
                    ListSource.IN_MHA_ORG);
        }
        List<SanctionedEntity> entities = new ArrayList<>();
        for (Entry entry : entries) {
            if (POINTER.matcher(entry.text()).matches()) {
                log.debug("Skipping entry {} of {}: {}", entry.serial(), schedule, entry.text());
                continue;
            }
            if (entry.items().isEmpty()) {
                entities.add(
                        entity(
                                schedule,
                                String.valueOf(entry.serial()),
                                String.valueOf(entry.serial()),
                                entry.text(),
                                null,
                                file));
                continue;
            }
            String heading = clean(entry.text()).replaceAll("(?i),?\\s*namely[:\\-]?$", "");
            for (int i = 0; i < entry.items().size(); i++) {
                String label = entry.serial() + " (" + roman(i + 1) + ")";
                entities.add(
                        entity(
                                schedule,
                                entry.serial() + "-" + (i + 1),
                                label,
                                entry.items().get(i),
                                heading,
                                file));
            }
        }
        return entities;
    }

    /** One numbered entry: its text and, when it groups organisations, their items. */
    record Entry(int serial, String text, List<String> items) {}

    /**
     * Splits the text into numbered entries. A line that does not start an entry or an item
     * continues the one before it; a word broken at a line end with a hyphen is joined back.
     */
    static List<Entry> entries(String text) {
        List<Entry> entries = new ArrayList<>();
        int serial = -1;
        StringBuilder current = null;
        List<StringBuilder> items = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            if (END.matcher(line).matches()) {
                break;
            }
            if (line.isEmpty()) {
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (entry.matches()) {
                if (current != null) {
                    entries.add(entry(serial, current, items));
                }
                serial = Integer.parseInt(entry.group(1));
                current = new StringBuilder(entry.group(2).strip());
                items = new ArrayList<>();
                continue;
            }
            if (current == null) {
                continue; // the heading above the first entry
            }
            Matcher item = SUB_ITEM.matcher(line);
            if (item.matches() && ROMAN.containsKey(item.group(1))) {
                items.add(new StringBuilder(item.group(2).strip()));
                continue;
            }
            StringBuilder target = items.isEmpty() ? current : items.get(items.size() - 1);
            if (target.length() > 0 && target.charAt(target.length() - 1) == '-') {
                target.append(line);
            } else {
                target.append(' ').append(line);
            }
        }
        if (current != null) {
            entries.add(entry(serial, current, items));
        }
        return entries;
    }

    private static Entry entry(int serial, StringBuilder text, List<StringBuilder> items) {
        return new Entry(serial, text.toString(), items.stream().map(Object::toString).toList());
    }

    private static SanctionedEntity entity(
            Schedule schedule,
            String idSuffix,
            String label,
            String text,
            String heading,
            URI file) {
        Names names = names(text);
        String remarks =
                "Entry "
                        + label
                        + " of the ministry's list of "
                        + schedule.description
                        + ": "
                        + clean(text)
                        + (heading == null ? "" : " (under \"" + heading + "\")")
                        + ". Source: "
                        + file;
        return new SanctionedEntity(
                schedule.idPrefix + idSuffix,
                EntityType.ENTITY,
                ListSource.IN_MHA_ORG,
                new NameInfo(
                        names.primary(),
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN),
                names.aliases(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                remarks,
                List.of(schedule.program),
                null,
                Instant.now());
    }

    /** The names an entry gives: the organisation's own first, then its aliases. */
    record Names(String primary, List<NameInfo> aliases) {}

    /**
     * Reads the names out of an entry's text: the wing, fronts or factions it enumerates after the
     * organisation it names first, or else the alternatives it joins with a slash or "or". Acronyms
     * in parentheses become weak aliases; the "and all its manifestations and front organisations"
     * tail is dropped.
     */
    static Names names(String entryText) {
        String text = SUFFIX.matcher(clean(entryText)).replaceFirst("");
        List<String> parts = new ArrayList<>();
        Matcher wing = WING.matcher(text);
        Matcher fronts = FRONTS.matcher(text);
        Matcher namely = NAMELY.matcher(text);
        if (wing.matches()) {
            parts.add(wing.group(1));
            parts.add(wing.group(2));
        } else if (fronts.matches()) {
            parts.add(fronts.group(1));
            parts.addAll(enumeration(fronts.group(2)));
        } else if (namely.matches()) {
            parts.add(namely.group(1));
            parts.addAll(enumeration(namely.group(2)));
        } else {
            parts.addAll(List.of(ALTERNATIVES.split(text)));
        }

        List<String> names = new ArrayList<>();
        Set<String> acronyms = new LinkedHashSet<>();
        for (String part : parts) {
            String name = closeParentheses(clean(part));
            if (name.isEmpty()) {
                continue;
            }
            Matcher bare = BARE_ACRONYM.matcher(name);
            if (bare.matches()) {
                acronyms.add(bare.group(1));
            } else {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            names.add(closeParentheses(clean(text)));
        }
        for (String name : names) {
            Matcher acronym = ACRONYM.matcher(name);
            while (acronym.find()) {
                if (acronym.group(1).chars().filter(Character::isUpperCase).count() >= 2) {
                    acronyms.add(acronym.group(1));
                }
            }
        }

        List<NameInfo> aliases = new ArrayList<>();
        for (String alias : names.subList(1, names.size())) {
            aliases.add(alias(alias, NameStrength.STRONG));
        }
        for (String acronym : acronyms) {
            if (!names.contains(acronym)) {
                aliases.add(alias(acronym, NameStrength.WEAK));
            }
        }
        return new Names(names.get(0), aliases);
    }

    /** Splits an enumeration on commas and "and", keeping a one-word place after a comma. */
    private static List<String> enumeration(String list) {
        List<String> items = new ArrayList<>();
        for (String piece : ENUMERATION.split(list)) {
            String item = clean(LED_BY.matcher(piece).replaceFirst(""));
            if (item.isEmpty()) {
                continue;
            }
            if (!item.contains(" ") && !items.isEmpty()) {
                int last = items.size() - 1;
                items.set(last, items.get(last) + ", " + item);
            } else {
                items.add(item);
            }
        }
        return items;
    }

    private static NameInfo alias(String name, NameStrength strength) {
        return new NameInfo(name, null, null, null, null, NameType.AKA, strength, ScriptType.LATIN);
    }

    /** Normalises quotes and spaces and drops the punctuation and dangling "and" at the end. */
    static String clean(String text) {
        String s =
                text.replace('‘', '\'')
                        .replace('’', '\'')
                        .replace('“', '"')
                        .replace('”', '"')
                        .replaceAll("\\s+", " ")
                        .strip();
        s = TRAILING.matcher(s).replaceFirst("");
        s = QUOTES.matcher(s).replaceAll("");
        return SPACED_HYPHEN.matcher(TRAILING.matcher(s).replaceFirst("")).replaceAll("-").strip();
    }

    /** The list writes "(NSCN(K)" for NSCN (K): closes the parentheses an entry leaves open. */
    private static String closeParentheses(String s) {
        int open = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                open++;
            } else if (c == ')' && open > 0) {
                open--;
            }
        }
        return open == 0 ? s : s + ")".repeat(open);
    }

    private static String roman(int n) {
        for (Map.Entry<String, Integer> e : ROMAN.entrySet()) {
            if (e.getValue() == n) {
                return e.getKey();
            }
        }
        return String.valueOf(n);
    }

    private static String text(String html) {
        return TAG.matcher(html)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
