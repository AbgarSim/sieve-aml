package dev.sieve.ingest.iq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.AbstractListProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Fetches Iraq's local list of terrorists whose funds are frozen, kept by the Committee for
 * Freezing Terrorist Funds and published through the Anti-Money Laundering and Counter-Terrorism
 * Financing Office's API.
 *
 * <p>The API pages the list; this provider asks for 1,000 rows a page and reads every page. Names
 * are in Arabic. Each row gives the decision that listed the person and its year, a birth year, the
 * mother's name and whether the person has since been removed; removed rows are left out. Ids are
 * {@code iq-} followed by the API's own record id. A birth year alone is stored as 1 January of
 * that year.
 *
 * @see <a href="https://aml.iq/">Iraq AML/CFT Office</a>
 */
public final class IqAmlProvider extends AbstractListProvider {

    /** The API path; the page number is appended. */
    public static final String DEFAULT_URL = "https://api.aml.iq/api/localsanctionslist/getall/";

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "IQ terrorist funds freezing",
                    "Iraq's local list of terrorists whose funds are frozen",
                    ListSource.IQ_AML);

    private volatile List<byte[]> laterPages = List.of();

    /** Creates a provider that reads the published API. */
    public IqAmlProvider() {
        super(ListSource.IQ_AML, URI.create(DEFAULT_URL), "application/json");
    }

    /**
     * Creates a provider with a custom API path and HTTP client (for testing).
     *
     * @param apiUri the API path, to which the page number is appended
     * @param httpClient the HTTP client to use for requests
     */
    public IqAmlProvider(URI apiUri, HttpClient httpClient) {
        super(ListSource.IQ_AML, apiUri, "application/json", httpClient, Duration.ofSeconds(60));
    }

    @Override
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        List<byte[]> pages = new ArrayList<>();
        for (int page = 2; page <= MAX_PAGES; page++) {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(pageUri(page))
                            .timeout(Duration.ofSeconds(60))
                            .header("Accept", "application/json")
                            .header("User-Agent", "sieve-aml/1.0")
                            .GET()
                            .build();
            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Iraq list page "
                                + page
                                + " download failed [status="
                                + response.statusCode()
                                + "]");
            }
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            if (!data.path("data").elements().hasNext()) {
                break;
            }
            pages.add(response.body());
            if (page >= data.path("pageCount").asInt(MAX_PAGES)) {
                break;
            }
        }
        laterPages = pages;
        return builder.uri(pageUri(1)).GET().build();
    }

    private URI pageUri(int page) {
        return URI.create(sourceUri() + String.valueOf(page) + "?pageSize=" + PAGE_SIZE);
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<byte[]> pages = new ArrayList<>();
        pages.add(responseBody);
        pages.addAll(laterPages);
        return parse(pages);
    }

    /**
     * Parses API pages into the people and entities still listed.
     *
     * @param pages the raw JSON pages, in order
     * @return the listed records, each once
     * @throws ListIngestionException if a page is not the expected JSON
     */
    List<SanctionedEntity> parse(List<byte[]> pages) throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Instant now = Instant.now();
        for (byte[] page : pages) {
            JsonNode rows;
            try {
                rows = MAPPER.readTree(page).path("data").path("data");
            } catch (IOException e) {
                throw new ListIngestionException(
                        "Failed to parse the Iraq list JSON: " + e.getMessage(),
                        ListSource.IQ_AML,
                        e);
            }
            if (!rows.isArray()) {
                throw new ListIngestionException(
                        "Iraq list response has no data array", ListSource.IQ_AML);
            }
            for (JsonNode row : rows) {
                SanctionedEntity entity = toEntity(row, now);
                if (entity != null && seen.add(entity.id())) {
                    entities.add(entity);
                }
            }
        }
        return entities;
    }

    private static SanctionedEntity toEntity(JsonNode row, Instant now) {
        String id = text(row, "id");
        String name = text(row, "name");
        if (id == null || name == null || row.path("isExcluded").asBoolean(false)) {
            return null;
        }
        EntityType type =
                row.path("type").asInt(0) == 0 ? EntityType.INDIVIDUAL : EntityType.ORGANIZATION;
        List<NameInfo> aliases = new ArrayList<>();
        String alias = text(row, "alias");
        if (alias != null) {
            for (String a : alias.split("[;،؛\\n]")) {
                if (!a.isBlank() && !a.strip().equals(name)) {
                    aliases.add(
                            new NameInfo(
                                    a.strip(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    NameType.AKA,
                                    null,
                                    script(a)));
                }
            }
        }
        List<LocalDate> dobs = new ArrayList<>();
        int year = row.path("birthYear").asInt(0);
        if (year > 1800) {
            dobs.add(LocalDate.of(year, 1, 1));
        }
        StringJoiner remarks = new StringJoiner("\n");
        String decision = text(row, "decisionNumber");
        int decisionYear = row.path("decisionYear").asInt(0);
        if (decision != null) {
            remarks.add(
                    "Listed by decision "
                            + decision
                            + (decisionYear > 0 ? " of " + decisionYear : ""));
        }
        if (year > 1800) {
            remarks.add("Born in " + year);
        }
        String mother = text(row, "motherName");
        if (mother != null) {
            remarks.add("Mother's name: " + mother);
        }
        return new SanctionedEntity(
                "iq-" + id,
                type,
                ListSource.IQ_AML,
                new NameInfo(
                        name,
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        script(name)),
                aliases,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                dobs,
                List.of(),
                remarks.length() == 0 ? null : remarks.toString(),
                List.of(PROGRAM),
                created(text(row, "createdAt")),
                now,
                Set.of(RiskTopic.SANCTION),
                List.of());
    }

    private static ScriptType script(String text) {
        return text.codePoints()
                        .anyMatch(
                                cp ->
                                        Character.UnicodeScript.of(cp)
                                                == Character.UnicodeScript.ARABIC)
                ? ScriptType.ARABIC
                : ScriptType.LATIN;
    }

    private static Instant created(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
