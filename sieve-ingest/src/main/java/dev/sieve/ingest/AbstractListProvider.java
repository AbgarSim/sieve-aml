package dev.sieve.ingest;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for {@link ListProvider} implementations that fetch data over HTTP.
 *
 * <p>Provides common HTTP fetching, ETag-based delta detection, content hashing, and metadata
 * tracking. Subclasses only need to implement {@link #parseResponse(byte[])} to handle the
 * source-specific data format.
 */
public abstract class AbstractListProvider implements ListProvider {

    /** Attempts per download, including the first. */
    static final int MAX_ATTEMPTS = 3;

    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(2);

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final ListSource listSource;
    private final URI sourceUri;
    private final HttpClient httpClient;
    private final String acceptHeader;
    private final Duration requestTimeout;
    private volatile ListMetadata currentMetadata;

    /**
     * Creates a provider with the given source, URI, and accept header.
     *
     * @param listSource the list source this provider handles
     * @param sourceUri the URI to fetch data from
     * @param acceptHeader the HTTP Accept header value (e.g., "application/xml",
     *     "application/json")
     */
    protected AbstractListProvider(ListSource listSource, URI sourceUri, String acceptHeader) {
        this(
                listSource,
                sourceUri,
                acceptHeader,
                HttpClientFactory.createTrustAllClient(DEFAULT_CONNECT_TIMEOUT),
                DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Creates a provider with full configuration (for testing).
     *
     * @param listSource the list source this provider handles
     * @param sourceUri the URI to fetch data from
     * @param acceptHeader the HTTP Accept header value
     * @param httpClient the HTTP client to use
     * @param requestTimeout the request timeout
     */
    protected AbstractListProvider(
            ListSource listSource,
            URI sourceUri,
            String acceptHeader,
            HttpClient httpClient,
            Duration requestTimeout) {
        this.listSource = Objects.requireNonNull(listSource, "listSource must not be null");
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.acceptHeader = Objects.requireNonNull(acceptHeader, "acceptHeader must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.requestTimeout =
                Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        this.currentMetadata = new ListMetadata(listSource, null, null, null, sourceUri, 0);
    }

    @Override
    public final ListSource source() {
        return listSource;
    }

    @Override
    public final ListMetadata metadata() {
        return currentMetadata;
    }

    /**
     * Sends a request, retrying when the connection fails or drops mid-download. Large lists are
     * served by government sites that sometimes cut a transfer short; HTTP error statuses are not
     * retried.
     */
    private HttpResponse<byte[]> sendWithRetry(HttpRequest request)
            throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                long backoffMs = retryBackoff().toMillis() * attempt;
                log.warn(
                        "{} download failed, retrying [attempt={}, backoffMs={}, error={}]",
                        listSource.displayName(),
                        attempt,
                        backoffMs,
                        e.getMessage());
                Thread.sleep(backoffMs);
            }
        }
    }

    /**
     * Returns the wait before the first retry; later retries wait proportionally longer.
     *
     * @return the base retry backoff
     */
    Duration retryBackoff() {
        return RETRY_BACKOFF;
    }

    @Override
    public final List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching {} list [uri={}]", listSource.displayName(), sourceUri);
        Instant start = Instant.now();

        try {
            HttpRequest request =
                    buildRequest(
                            httpClient,
                            HttpRequest.newBuilder()
                                    .uri(sourceUri)
                                    .timeout(requestTimeout)
                                    .header("Accept", acceptHeader)
                                    .header("User-Agent", "sieve-aml/1.0"));

            HttpResponse<byte[]> response = sendWithRetry(request);

            if (response.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format(
                                "%s fetch failed [status=%d, uri=%s]",
                                listSource.displayName(), response.statusCode(), sourceUri),
                        listSource);
            }

            byte[] body = response.body();
            String contentHash = computeSha256(body);
            String etag = response.headers().firstValue("ETag").orElse(null);

            log.info(
                    "{} downloaded [bytes={}, etag={}, hash={}]",
                    listSource.displayName(),
                    body.length,
                    etag,
                    contentHash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseResponse(body);

            Instant now = Instant.now();
            Duration duration = Duration.between(start, now);
            currentMetadata =
                    new ListMetadata(
                            listSource, now, etag, contentHash, sourceUri, entities.size());

            log.info(
                    "{} ingestion complete [entities={}, duration={}ms]",
                    listSource.displayName(),
                    entities.size(),
                    duration.toMillis());

            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching " + listSource.displayName() + ": " + e.getMessage(),
                    listSource,
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException(
                    listSource.displayName() + " fetch interrupted", listSource, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during "
                            + listSource.displayName()
                            + " ingestion: "
                            + e.getMessage(),
                    listSource,
                    e);
        }
    }

    @Override
    public final boolean hasUpdates(ListMetadata previousMetadata) {
        if (previousMetadata == null || previousMetadata.etag() == null) {
            return true;
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(sourceUri)
                            .timeout(Duration.ofSeconds(30))
                            .header("If-None-Match", previousMetadata.etag())
                            .header("User-Agent", "sieve-aml/1.0")
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            boolean notModified = response.statusCode() == 304;
            log.debug(
                    "{} update check [status={}, hasUpdates={}]",
                    listSource.displayName(),
                    response.statusCode(),
                    !notModified);
            return !notModified;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn(
                    "Failed to check {} for updates, assuming updates exist",
                    listSource.displayName(),
                    e);
            return true;
        }
    }

    /**
     * Builds the download request from a builder that already carries the source URI, timeout and
     * headers. The default is a plain GET; override it when a source needs more, such as a form
     * post with a token read from another page first.
     *
     * @param client the HTTP client, for any requests needed before the download
     * @param builder the request builder to complete
     * @return the download request
     * @throws IOException if a preliminary request fails
     * @throws InterruptedException if a preliminary request is interrupted
     */
    protected HttpRequest buildRequest(HttpClient client, HttpRequest.Builder builder)
            throws IOException, InterruptedException {
        return builder.GET().build();
    }

    /**
     * Parses the raw HTTP response body into sanctioned entities.
     *
     * <p>Subclasses implement this method to handle the source-specific data format (XML, JSON,
     * CSV, etc.).
     *
     * @param responseBody the raw bytes from the HTTP response
     * @return the parsed entities, never {@code null}
     * @throws ListIngestionException if parsing fails
     */
    protected abstract List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException;

    /** Returns the source URI this provider fetches from. */
    protected URI sourceUri() {
        return sourceUri;
    }

    private static String computeSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }
}
