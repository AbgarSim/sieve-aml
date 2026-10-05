package dev.sieve.ingest.europol;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityLink;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.LinkKind;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.io.IOException;
import java.net.URI;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Europe's most wanted fugitives, the people EU member states' fugitive active search teams
 * publish through Europol on {@code eumostwanted.eu}.
 *
 * <p>The site has no download; its home page lists every poster as one row of labelled fields. Only
 * rows whose status is {@code Wanted} are kept, tagged {@link RiskTopic#WANTED}, with the crime as
 * program and the requesting country, case state and poster link in remarks. Ids follow the site's
 * node ids ({@code eu-mw-2102}).
 *
 * <p>Date of birth, nationality and publication date appear only on each person's poster page, so
 * those pages are read while the request is built. A poster page that fails to load leaves its
 * person without those details rather than failing the list.
 *
 * @see <a href="https://eumostwanted.eu/">Europe's Most Wanted</a>
 */
public final class EuMostWantedProvider extends AbstractListProvider {

    private static final String DEFAULT_URL = "https://eumostwanted.eu/";

    private static final Pattern ROW =
            Pattern.compile(
                    "(?s)<div class=\"views-row\">(.*?)(?=<div class=\"views-row\">|</main>|$)");
    private static final Pattern FIELD =
            Pattern.compile(
                    "(?s)<div class=\"views-field views-field-([a-z0-9-]+)\">\\s*"
                            + "<(?:span|div) class=\"field-content\">(.*?)</(?:span|div)>");
    private static final Pattern ITEM =
            Pattern.compile("(?s)<div class=\"field__item\">(.*?)</div>");
    private static final Pattern DATETIME = Pattern.compile("datetime=\"(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern PUBLISHED = Pattern.compile("on ([A-Z][a-z]+ \\d{1,2}, \\d{4})");
    private static final Pattern NICKNAME = Pattern.compile("\\s*\\(([^)]+)\\)\\s*");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final DateTimeFormatter PUBLISHED_DATE =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /** What a poster page adds to its list row. */
    record Details(List<LocalDate> datesOfBirth, List<String> nationalities, Instant published) {

        static final Details NONE = new Details(List.of(), List.of(), null);
    }

    private volatile Map<String, Details> details = Map.of();

    /** Creates a provider that reads the list from the site's home page. */
    public EuMostWantedProvider() {
        super(ListSource.EU_MOST_WANTED, URI.create(DEFAULT_URL), "text/html");
    }

    /**
     * Creates a provider with a custom page URI and HTTP client (for testing).
     *
     * @param pageUri the list page
     * @param httpClient the HTTP client to use for requests
     */
    public EuMostWantedProvider(URI pageUri, HttpClient httpClient) {
        super(ListSource.EU_MOST_WANTED, pageUri, "text/html", httpClient, Duration.ofSeconds(60));
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        Map<String, Details> found = new HashMap<>();
        Set<String> seen = new HashSet<>();
        HttpResponse<byte[]> list = client.send(get(sourceUri()), bodyHandler());
        if (list.statusCode() == 200) {
            Matcher row = ROW.matcher(new String(list.body(), StandardCharsets.UTF_8));
            while (row.find()) {
                Map<String, String> fields = fields(row.group(1));
                String path = fields.get("view-node");
                if (isWanted(fields) && path != null && path.startsWith("/") && seen.add(path)) {
                    Details poster = fetchDetails(client, path);
                    if (poster != null) {
                        found.put(path, poster);
                    }
                }
            }
        }
        details = found;
        return builder.GET().build();
    }

    private Details fetchDetails(HttpClient client, String path) throws InterruptedException {
        URI uri = sourceUri().resolve(path);
        try {
            HttpResponse<byte[]> response = client.send(get(uri), bodyHandler());
            if (response.statusCode() == 200) {
                return parseDetails(new String(response.body(), StandardCharsets.UTF_8));
            }
            log.warn(
                    "EU Most Wanted poster skipped [uri={}, status={}]",
                    uri,
                    response.statusCode());
        } catch (IOException e) {
            log.warn("EU Most Wanted poster skipped [uri={}, error={}]", uri, e.getMessage());
        }
        return null;
    }

    private static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "text/html")
                .header("User-Agent", "sieve-aml/1.0")
                .GET()
                .build();
    }

    private static HttpResponse.BodyHandler<byte[]> bodyHandler() {
        return HttpResponse.BodyHandlers.ofByteArray();
    }

    /**
     * Reads date of birth, nationality and publication date from a poster page.
     *
     * @param html the poster page
     * @return the details found, with empty lists or {@code null} for what is missing
     */
    static Details parseDetails(String html) {
        List<LocalDate> datesOfBirth = new ArrayList<>();
        Matcher datetime = DATETIME.matcher(block(html, "date-of-birth"));
        if (datetime.find()) {
            LocalDate dob = parseDate(datetime.group(1), DateTimeFormatter.ISO_LOCAL_DATE);
            if (dob != null) {
                datesOfBirth.add(dob);
            }
        }
        List<String> nationalities = new ArrayList<>();
        Matcher item = ITEM.matcher(block(html, "nationality"));
        while (item.find()) {
            String nationality = text(item.group(1));
            if (!nationality.isEmpty()) {
                nationalities.add(nationality);
            }
        }
        // The site prints "published on <date>, last modified on <date>" in its last-name field.
        Instant published = null;
        Matcher on = PUBLISHED.matcher(text(block(html, "w-last-name")));
        if (on.find()) {
            LocalDate date = parseDate(on.group(1), PUBLISHED_DATE);
            published = date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return new Details(datesOfBirth, nationalities, published);
    }

    /** Returns the markup of one field, up to the start of the next field. */
    private static String block(String html, String field) {
        String marker = "field--name-field-" + field;
        int start = html.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int end = html.indexOf("field--name-field-", start + marker.length());
        return html.substring(start, end < 0 ? Math.min(html.length(), start + 2000) : end);
    }

    private static LocalDate parseDate(String value, DateTimeFormatter format) {
        try {
            return LocalDate.parse(value, format);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        String html = new String(responseBody, StandardCharsets.UTF_8);
        Map<String, SanctionedEntity> byId = new LinkedHashMap<>();
        int rows = 0;
        Matcher row = ROW.matcher(html);
        while (row.find()) {
            rows++;
            SanctionedEntity entity = parseRow(fields(row.group(1)));
            if (entity != null) {
                byId.putIfAbsent(entity.id(), entity);
            }
        }
        if (rows == 0) {
            throw new ListIngestionException(
                    "EU Most Wanted page has no poster rows; the page layout may have changed",
                    ListSource.EU_MOST_WANTED);
        }
        return new ArrayList<>(byId.values());
    }

    private static Map<String, String> fields(String row) {
        Map<String, String> fields = new HashMap<>();
        Matcher field = FIELD.matcher(row);
        while (field.find()) {
            String value = text(field.group(2));
            if (!value.isEmpty()) {
                fields.putIfAbsent(field.group(1), value);
            }
        }
        return fields;
    }

    private static boolean isWanted(Map<String, String> fields) {
        return "Wanted".equalsIgnoreCase(fields.get("field-status"));
    }

    private SanctionedEntity parseRow(Map<String, String> fields) {
        String nid = fields.get("nid");
        String title = fields.get("title");
        if (nid == null || title == null || !isWanted(fields)) {
            return null;
        }

        // Titles read "FAMILY, Given"; the full name puts the given name first.
        String family = title;
        String given = null;
        int comma = title.indexOf(',');
        if (comma > 0) {
            family = title.substring(0, comma).strip();
            given = title.substring(comma + 1).strip();
        }
        // A nickname in brackets, as in "Joseph Johannes (Jos)", becomes a weak alias.
        List<NameInfo> aliases = new ArrayList<>();
        if (given != null) {
            Matcher nickname = NICKNAME.matcher(given);
            if (nickname.find()) {
                aliases.add(
                        new NameInfo(
                                nickname.group(1).strip() + " " + family,
                                null,
                                null,
                                null,
                                null,
                                NameType.AKA,
                                NameStrength.WEAK,
                                null));
                given = nickname.replaceAll(" ").strip();
            }
            if (given.isEmpty()) {
                given = null;
            }
        }
        String fullName = given == null ? family : given + " " + family;
        NameInfo primary =
                new NameInfo(
                        fullName,
                        given,
                        family,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        null);

        String crime = fields.get("field-crime");
        List<SanctionsProgram> programs =
                crime == null
                        ? List.of()
                        : List.of(new SanctionsProgram(crime, crime, ListSource.EU_MOST_WANTED));
        Details poster = details.getOrDefault(fields.get("view-node"), Details.NONE);

        return new SanctionedEntity(
                        "eu-mw-" + nid,
                        EntityType.INDIVIDUAL,
                        ListSource.EU_MOST_WANTED,
                        primary,
                        aliases,
                        List.of(),
                        List.of(),
                        poster.nationalities(),
                        List.of(),
                        poster.datesOfBirth(),
                        List.of(),
                        remarks(fields),
                        programs,
                        poster.published(),
                        Instant.now(),
                        Set.of(RiskTopic.WANTED),
                        List.of())
                .withLinks(posterLink(fields.get("view-node")));
    }

    /** The person's poster page on the site, from the row's link to it. */
    private List<EntityLink> posterLink(String path) {
        if (path == null || !path.startsWith("/")) {
            return List.of();
        }
        return List.of(
                new EntityLink(
                        sourceUri().resolve(path).toString(),
                        "Europe's Most Wanted poster",
                        LinkKind.SOURCE_PAGE,
                        null));
    }

    private String remarks(Map<String, String> fields) {
        StringJoiner joiner = new StringJoiner("\n");
        String country = fields.get("field-enfast-country");
        if (country != null) {
            String code = fields.get("field-country-code");
            joiner.add("Wanted by: " + country + (code == null ? "" : " (" + code + ")"));
        }
        String state = fields.get("field-state-of-case");
        if (state != null) {
            String years = fields.get("field-years-sentenced");
            if (years != null && years.matches("(?i).*(year|month).*")) {
                // "4 years 11 months" already carries its unit.
                state = state.replace("{x} years", years);
            }
            joiner.add("State of case: " + state.replace("{x}", years == null ? "?" : years));
        }
        String locations = fields.get("field-propable-locations");
        if (locations != null) {
            joiner.add("Probable locations: " + locations);
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private static String text(String html) {
        String plain = TAG.matcher(html).replaceAll(" ");
        Matcher entity = NUMERIC_ENTITY.matcher(plain);
        StringBuilder decoded = new StringBuilder();
        while (entity.find()) {
            int code = Integer.parseInt(entity.group(2), entity.group(1).isEmpty() ? 10 : 16);
            entity.appendReplacement(decoded, Matcher.quoteReplacement(Character.toString(code)));
        }
        entity.appendTail(decoded);
        plain =
                decoded.toString()
                        .replace("&nbsp;", " ")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&");
        return plain.replaceAll("\\s+", " ").strip();
    }
}
