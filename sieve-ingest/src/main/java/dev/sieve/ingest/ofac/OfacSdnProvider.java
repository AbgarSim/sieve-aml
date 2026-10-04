package dev.sieve.ingest.ofac;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.HttpClientFactory;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
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
 * Fetches and parses the OFAC Specially Designated Nationals (SDN) list.
 *
 * <p>Uses StAX (streaming) XML parsing for memory-efficient processing of the potentially large SDN
 * XML file. Supports HTTP ETag-based delta detection to avoid unnecessary re-downloads.
 *
 * <p>Parsing is shared with the consolidated non-SDN list, which uses the same schema: see {@link
 * OfacXmlParser} for the entity an entry gives, the crypto wallet entities made from its digital
 * currency addresses and the relations resolved from the links the list states in words.
 *
 * @see <a
 *     href="https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML">OFAC
 *     SDN XML</a>
 */
public final class OfacSdnProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(OfacSdnProvider.class);

    private static final String DEFAULT_SDN_URL =
            "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private static final OfacXmlParser PARSER =
            new OfacXmlParser(ListSource.OFAC_SDN, "ofac-sdn-", "OFAC SDN");

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /** Creates a provider with the default OFAC SDN URL. */
    public OfacSdnProvider() {
        this(URI.create(DEFAULT_SDN_URL));
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the SDN XML from
     */
    public OfacSdnProvider(URI sourceUri) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = HttpClientFactory.createTrustAllClient(CONNECT_TIMEOUT);
        this.currentMetadata =
                new ListMetadata(ListSource.OFAC_SDN, null, null, null, sourceUri, 0);
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the SDN XML from
     * @param httpClient the HTTP client to use for requests
     */
    public OfacSdnProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.currentMetadata =
                new ListMetadata(ListSource.OFAC_SDN, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.OFAC_SDN;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching OFAC SDN list [uri={}]", sourceUri);
        Instant start = Instant.now();

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(sourceUri)
                            .timeout(REQUEST_TIMEOUT)
                            .header("Accept", "application/xml")
                            .header("User-Agent", "java-sanctions-screener/1.0")
                            .GET()
                            .build();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format(
                                "OFAC SDN fetch failed [status=%d, uri=%s]",
                                response.statusCode(), sourceUri),
                        ListSource.OFAC_SDN);
            }

            byte[] body = response.body();
            String contentHash = computeSha256(body);
            String etag = response.headers().firstValue("ETag").orElse(null);

            log.info(
                    "OFAC SDN downloaded [bytes={}, etag={}, hash={}]",
                    body.length,
                    etag,
                    contentHash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseXml(body);

            Instant now = Instant.now();
            Duration duration = Duration.between(start, now);
            currentMetadata =
                    new ListMetadata(
                            ListSource.OFAC_SDN,
                            now,
                            etag,
                            contentHash,
                            sourceUri,
                            entities.size());

            log.info(
                    "OFAC SDN ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    duration.toMillis());

            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching OFAC SDN list: " + e.getMessage(),
                    ListSource.OFAC_SDN,
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException("OFAC SDN fetch interrupted", ListSource.OFAC_SDN, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during OFAC SDN ingestion: " + e.getMessage(),
                    ListSource.OFAC_SDN,
                    e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        if (previousMetadata == null || previousMetadata.etag() == null) {
            return true;
        }

        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(sourceUri)
                            .timeout(CONNECT_TIMEOUT)
                            .header("If-None-Match", previousMetadata.etag())
                            .header("User-Agent", "java-sanctions-screener/1.0")
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build();

            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());

            boolean notModified = response.statusCode() == 304;
            log.debug(
                    "OFAC SDN update check [status={}, hasUpdates={}]",
                    response.statusCode(),
                    !notModified);
            return !notModified;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to check OFAC SDN for updates, assuming updates exist", e);
            return true;
        }
    }

    /**
     * Parses the OFAC SDN XML content into a list of sanctioned entities.
     *
     * @param xmlContent the raw XML bytes
     * @return parsed entities
     * @throws ListIngestionException if the XML is malformed or cannot be parsed
     */
    List<SanctionedEntity> parseXml(byte[] xmlContent) throws ListIngestionException {
        return PARSER.parse(xmlContent);
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
