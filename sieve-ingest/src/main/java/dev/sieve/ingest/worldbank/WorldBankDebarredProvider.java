package dev.sieve.ingest.worldbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the firms and individuals the World Bank Group has debarred from Bank-financed contracts.
 *
 * <p>The Bank publishes the list only as a table on its debarred-firms page. That page loads the
 * table from an API gateway, sending a key written into the page's own script. There is no official
 * download, so this provider does what the page does: it reads the page, takes the key from it, and
 * calls the same API. If the Bank changes the page or the gateway, this source stops loading until
 * the provider is updated; the key is read fresh on every fetch rather than stored here.
 *
 * <p>Every row becomes one entity tagged {@link RiskTopic#DEBARMENT}, with id {@code wb-<supplier
 * id>}, its address, the grounds for debarment as program, and the ineligibility period in remarks.
 * Rows whose ineligibility period has already ended are left out.
 *
 * @see <a href="https://www.worldbank.org/en/projects-operations/procurement/debarred-firms">World
 *     Bank debarred firms and individuals</a>
 */
public final class WorldBankDebarredProvider extends AbstractListProvider {

    private static final String DEFAULT_PAGE =
            "https://www.worldbank.org/en/projects-operations/procurement/debarred-firms";
    private static final String DEFAULT_API =
            "https://apigwext.worldbank.org/dvsvc/v1.0/json/APPLICATION/ADOBE_EXPRNCE_MGR/FIRM/SANCTIONED_FIRM";

    /** The production key the page's script assigns before calling the API. */
    private static final Pattern PAGE_KEY = Pattern.compile("propApiKey\\s*=\\s*\"([^\"]+)\"");

    /** Footnote markers such as {@code *203} that point at notes on the Bank's page. */
    private static final Pattern FOOTNOTE = Pattern.compile("\\*\\d+");

    /** Honorifics the Bank writes before individuals' names, such as {@code MR.}. */
    private static final Pattern HONORIFIC =
            Pattern.compile("(?i)^(MR|MRS|MS|MISS|DR|PROF|ENG|ENGR)\\.?\\s+");

    /** The end date the Bank uses for debarments with no end. */
    private static final LocalDate OPEN_ENDED = LocalDate.of(2999, 1, 1);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI pageUri;
    private final Clock clock;

    /** Creates a provider that reads the key from the Bank's page and the list from its API. */
    public WorldBankDebarredProvider() {
        super(ListSource.WB_DEBARRED, URI.create(DEFAULT_API), "application/json");
        this.pageUri = URI.create(DEFAULT_PAGE);
        this.clock = Clock.systemUTC();
    }

    /**
     * Creates a provider with custom URIs, HTTP client and clock (for testing).
     *
     * @param pageUri the page whose script holds the API key
     * @param apiUri the API that returns the list
     * @param httpClient the HTTP client to use for requests
     * @param clock the clock that decides which debarments have ended
     */
    public WorldBankDebarredProvider(URI pageUri, URI apiUri, HttpClient httpClient, Clock clock) {
        super(
                ListSource.WB_DEBARRED,
                apiUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(120));
        this.pageUri = pageUri;
        this.clock = clock;
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        HttpRequest pageRequest =
                HttpRequest.newBuilder()
                        .uri(pageUri)
                        .timeout(Duration.ofSeconds(60))
                        .header("Accept", "text/html")
                        .header("User-Agent", "sieve-aml/1.0")
                        .GET()
                        .build();
        HttpResponse<byte[]> page =
                client.send(pageRequest, HttpResponse.BodyHandlers.ofByteArray());
        if (page.statusCode() != 200) {
            throw new IOException(
                    "World Bank debarred-firms page failed [status=" + page.statusCode() + "]");
        }
        Matcher key = PAGE_KEY.matcher(new String(page.body(), StandardCharsets.UTF_8));
        if (!key.find()) {
            throw new IOException(
                    "World Bank debarred-firms page no longer contains its API key;"
                            + " the page may have changed");
        }
        return builder.header("apikey", key.group(1)).GET().build();
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        JsonNode rows;
        try {
            rows = MAPPER.readTree(responseBody).path("response").path("ZPROCSUPP");
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Failed to parse World Bank debarred JSON: " + e.getMessage(),
                    ListSource.WB_DEBARRED,
                    e);
        }
        if (!rows.isArray()) {
            throw new ListIngestionException(
                    "World Bank debarred response has no 'response.ZPROCSUPP' array",
                    ListSource.WB_DEBARRED);
        }
        LocalDate today = LocalDate.now(clock);
        List<SanctionedEntity> entities = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            SanctionedEntity entity = parseRow(row, today);
            if (entity != null && seen.add(entity.id())) {
                entities.add(entity);
            }
        }
        return entities;
    }

    private SanctionedEntity parseRow(JsonNode row, LocalDate today) {
        String id = text(row, "SUPP_ID");
        String name = text(row, "SUPP_NAME");
        if (id == null || name == null) {
            return null;
        }
        LocalDate from = date(text(row, "DEBAR_FROM_DATE"));
        LocalDate to = date(text(row, "DEBAR_TO_DATE"));
        if (to != null && to.isBefore(today)) {
            return null;
        }

        EntityType type =
                "I".equals(text(row, "SUPP_TYPE_CODE")) ? EntityType.INDIVIDUAL : EntityType.ENTITY;
        String title = null;
        if (type == EntityType.INDIVIDUAL) {
            Matcher honorific = HONORIFIC.matcher(name);
            if (honorific.find() && honorific.end() < name.length()) {
                title = honorific.group().strip();
                name = name.substring(honorific.end()).strip();
            }
        }
        NameInfo primary =
                new NameInfo(
                        name, null, null, null, title, NameType.PRIMARY, NameStrength.STRONG, null);
        List<NameInfo> aliases = new ArrayList<>();
        for (String field : List.of("SUPP_PRE_ACRN", "SUPP_POST_ACRN")) {
            String acronym = text(row, field);
            if (acronym != null && !acronym.equalsIgnoreCase(name)) {
                aliases.add(
                        new NameInfo(
                                acronym,
                                null,
                                null,
                                null,
                                null,
                                NameType.AKA,
                                NameStrength.WEAK,
                                null));
            }
        }

        String country = text(row, "COUNTRY_NAME");
        List<Address> addresses = new ArrayList<>();
        String street = text(row, "SUPP_ADDR");
        String city = text(row, "SUPP_CITY");
        String state = first(text(row, "SUPP_STATE_CODE"), text(row, "SUPP_PROV_NAME"));
        String postal = first(text(row, "SUPP_ZIP_CODE"), text(row, "SUPP_POST_CODE"));
        if (street != null || city != null || state != null || postal != null || country != null) {
            StringJoiner full = new StringJoiner(", ");
            for (String part : new String[] {street, city, state, postal, country}) {
                if (part != null) {
                    full.add(part);
                }
            }
            addresses.add(new Address(street, city, state, postal, country, full.toString()));
        }

        String reason = text(row, "DEBAR_REASON");
        List<SanctionsProgram> programs =
                reason == null
                        ? List.of()
                        : List.of(new SanctionsProgram(reason, reason, ListSource.WB_DEBARRED));

        return new SanctionedEntity(
                        "wb-" + id,
                        type,
                        ListSource.WB_DEBARRED,
                        primary,
                        aliases,
                        addresses,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        remarks(row, from, to),
                        programs,
                        from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        Instant.now(),
                        Set.of(RiskTopic.DEBARMENT),
                        List.of())
                .withListingReasons(reason == null ? List.of() : List.of(reason));
    }

    private static String remarks(JsonNode row, LocalDate from, LocalDate to) {
        StringJoiner joiner = new StringJoiner("\n");
        if (from != null) {
            boolean openEnded = to == null || !to.isBefore(OPEN_ENDED);
            joiner.add(
                    "Ineligible from " + from + (openEnded ? ", with no end date" : " to " + to));
        }
        String status = text(row, "INELIGIBLY_STATUS");
        if (status != null) {
            joiner.add("Ineligibility: " + status);
        }
        String info = text(row, "ADD_SUPP_INFO");
        if (info != null) {
            String extra = FOOTNOTE.matcher(info).replaceAll(" ").replaceAll("\\s+", " ").strip();
            if (!extra.isEmpty()) {
                joiner.add("Additional information: " + extra);
            }
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static String first(String a, String b) {
        return a != null ? a : b;
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
}
