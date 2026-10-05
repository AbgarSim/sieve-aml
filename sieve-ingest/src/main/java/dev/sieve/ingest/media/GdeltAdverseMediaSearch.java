package dev.sieve.ingest.media;

import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.ingest.HttpClientFactory;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds candidate adverse media in the GDELT DOC 2.0 API, an open index of online news in many
 * languages that covers a rolling three months.
 *
 * <p>Each search is one request: the name as an exact phrase together with any of the {@link
 * AdverseTerms}. GDELT asks callers to send no more than one request every five seconds, so this
 * class spaces requests out and answers {@link AdverseMediaReport.Status#UNAVAILABLE} rather than
 * queueing a caller for longer than {@code maxWait}. Reports are kept for {@code cacheTtl}, so
 * screening the same name twice asks GDELT once.
 *
 * <p>Nothing here touches the entity index: articles are returned to the caller and forgotten.
 */
public final class GdeltAdverseMediaSearch implements AdverseMediaSearch {

    /** The public DOC 2.0 API endpoint. */
    public static final URI DEFAULT_ENDPOINT =
            URI.create("https://api.gdeltproject.org/api/v2/doc/doc");

    /** The index name reported on every result. */
    public static final String INDEX_NAME = "GDELT";

    /** GDELT's DOC API searches at most the last three months. */
    static final Duration MAX_LOOKBACK = Duration.ofDays(90);

    private static final Logger log = LoggerFactory.getLogger(GdeltAdverseMediaSearch.class);
    private static final String USER_AGENT =
            "sieve-aml/1.0 (https://github.com/AbgarSim/sieve-aml; sanctions and PEP screening)";
    private static final int MAX_CACHED = 1_000;

    private final URI endpoint;
    private final Fetcher fetcher;
    private final Clock clock;
    private final List<String> terms;
    private final Duration minInterval;
    private final Duration maxWait;
    private final Duration cacheTtl;
    private final Map<AdverseMediaQuery, AdverseMediaReport> cache = new LinkedHashMap<>();
    private Instant nextSlot = Instant.EPOCH;

    /**
     * Creates a search against the public API with the default terms, one request every five
     * seconds, callers waiting at most 20 seconds and results kept for an hour.
     */
    public GdeltAdverseMediaSearch() {
        this(DEFAULT_ENDPOINT, Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofHours(1));
    }

    /**
     * Creates a search against the given endpoint with the default terms.
     *
     * @param endpoint the DOC API endpoint
     * @param minInterval the least time between two requests
     * @param maxWait the longest a caller waits for its turn before the search gives up
     * @param cacheTtl how long a report is reused for the same query; zero turns caching off
     */
    public GdeltAdverseMediaSearch(
            URI endpoint, Duration minInterval, Duration maxWait, Duration cacheTtl) {
        this(
                endpoint,
                httpFetcher(HttpClientFactory.createTrustAllClient(Duration.ofSeconds(10))),
                Clock.systemUTC(),
                AdverseTerms.DEFAULT,
                minInterval,
                maxWait,
                cacheTtl);
    }

    GdeltAdverseMediaSearch(
            URI endpoint,
            Fetcher fetcher,
            Clock clock,
            List<String> terms,
            Duration minInterval,
            Duration maxWait,
            Duration cacheTtl) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.terms = List.copyOf(Objects.requireNonNull(terms, "terms must not be null"));
        this.minInterval = Objects.requireNonNull(minInterval, "minInterval must not be null");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait must not be null");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
        if (this.terms.isEmpty()) {
            throw new IllegalArgumentException("terms must not be empty");
        }
    }

    @Override
    public AdverseMediaReport search(AdverseMediaQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Optional<AdverseMediaReport> cached = cached(query);
        if (cached.isPresent()) {
            return cached.get();
        }

        String indexQuery = indexQuery(query.name(), terms);
        Instant searchedAt = clock.instant();
        Optional<Duration> wait = reserveSlot();
        if (wait.isEmpty()) {
            log.info("Adverse media search skipped, rate limit [name={}]", query.name());
            return unavailable(
                    query,
                    indexQuery,
                    searchedAt,
                    "Too many searches: GDELT allows one request every "
                            + minInterval.toSeconds()
                            + " seconds");
        }

        try {
            if (!wait.get().isZero()) {
                Thread.sleep(wait.get().toMillis());
            }
            URI uri = requestUri(indexQuery, query);
            Fetcher.Response response = fetcher.get(uri);
            if (response.status() != 200) {
                return unavailable(
                        query, indexQuery, searchedAt, "GDELT answered HTTP " + response.status());
            }
            List<MediaArticle> articles = GdeltDocParser.parse(response.body(), terms);
            List<MediaArticle> limited =
                    articles.size() > query.maxArticles()
                            ? articles.subList(0, query.maxArticles())
                            : articles;
            AdverseMediaReport report =
                    new AdverseMediaReport(
                            query.name(),
                            INDEX_NAME,
                            indexQuery,
                            AdverseMediaReport.Status.OK,
                            limited,
                            searchedAt,
                            Optional.empty());
            remember(query, report);
            log.info(
                    "Adverse media search done [name={}, articles={}]",
                    query.name(),
                    limited.size());
            return report;
        } catch (GdeltDocParser.GdeltRejection e) {
            return unavailable(query, indexQuery, searchedAt, "GDELT refused: " + e.getMessage());
        } catch (IOException e) {
            return unavailable(query, indexQuery, searchedAt, "GDELT unreachable: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(query, indexQuery, searchedAt, "Search interrupted");
        }
    }

    /**
     * Builds the GDELT query: the name as an exact phrase and any one of the terms, for example
     * {@code "Viktor Bout" (fraud OR "money laundering")}.
     *
     * <p>Quotes, brackets and other characters GDELT reads as operators are removed from the name,
     * so a name cannot change the meaning of the query.
     *
     * @param name the name
     * @param terms the adverse terms
     * @return the query text
     */
    static String indexQuery(String name, List<String> terms) {
        String cleaned = name.replaceAll("[\"()<>:#\\[\\]{}]", " ").replaceAll("\\s+", " ").strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("name has no searchable characters: " + name);
        }
        String anyTerm =
                terms.stream()
                        .map(term -> term.contains(" ") ? "\"" + term + "\"" : term)
                        .collect(Collectors.joining(" OR "));
        return "\"" + cleaned + "\" (" + anyTerm + ")";
    }

    private URI requestUri(String indexQuery, AdverseMediaQuery query) {
        long days = Math.min(query.lookback().toDays(), MAX_LOOKBACK.toDays());
        String params =
                "query="
                        + encode(indexQuery)
                        + "&mode=artlist&format=json&sort=datedesc"
                        + "&maxrecords="
                        + query.maxArticles()
                        + "&timespan="
                        + days
                        + "d";
        return URI.create(endpoint + "?" + params);
    }

    /**
     * Claims the next free request slot and returns how long to wait for it, or empty when that
     * wait would be longer than {@code maxWait}.
     */
    private synchronized Optional<Duration> reserveSlot() {
        Instant now = clock.instant();
        Instant slot = nextSlot.isAfter(now) ? nextSlot : now;
        Duration wait = Duration.between(now, slot);
        if (wait.compareTo(maxWait) > 0) {
            return Optional.empty();
        }
        nextSlot = slot.plus(minInterval);
        return Optional.of(wait);
    }

    private synchronized Optional<AdverseMediaReport> cached(AdverseMediaQuery query) {
        AdverseMediaReport report = cache.get(query);
        if (report == null) {
            return Optional.empty();
        }
        if (report.searchedAt().plus(cacheTtl).isAfter(clock.instant())) {
            return Optional.of(report);
        }
        cache.remove(query);
        return Optional.empty();
    }

    private synchronized void remember(AdverseMediaQuery query, AdverseMediaReport report) {
        if (cacheTtl.isZero()) {
            return;
        }
        if (cache.size() >= MAX_CACHED) {
            cache.remove(cache.keySet().iterator().next());
        }
        cache.put(query, report);
    }

    private static AdverseMediaReport unavailable(
            AdverseMediaQuery query, String indexQuery, Instant searchedAt, String detail) {
        log.warn("Adverse media search failed [name={}, reason={}]", query.name(), detail);
        return new AdverseMediaReport(
                query.name(),
                INDEX_NAME,
                indexQuery,
                AdverseMediaReport.Status.UNAVAILABLE,
                List.of(),
                searchedAt,
                Optional.of(detail));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Fetcher httpFetcher(HttpClient client) {
        return uri -> {
            HttpRequest request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", USER_AGENT)
                            .GET()
                            .build();
            HttpResponse<String> response =
                    client.send(
                            request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Fetcher.Response(response.statusCode(), response.body());
        };
    }

    /** Sends one GET request; replaced in tests. */
    @FunctionalInterface
    interface Fetcher {

        Response get(URI uri) throws IOException, InterruptedException;

        record Response(int status, String body) {}
    }
}
