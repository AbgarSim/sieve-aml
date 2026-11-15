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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches the individual terrorists that India's Ministry of Home Affairs designates under the
 * Fourth Schedule of the Unlawful Activities (Prevention) Act, 1967 (UAPA).
 *
 * <p>The ministry publishes the list only as a web page. Since the site's redesign in October 2026
 * it is a paged table with one row per person: the name and its aliases joined by {@code @}, and a
 * link to the gazette notification. The table no longer shows the list's serial numbers, so the
 * provider reads it oldest first and numbers the rows itself; that order is the one the old page
 * numbered, so ids ({@code in-mha-12}) carry over, and the ministry appends new designations at the
 * end.
 *
 * @see <a
 *     href="https://www.mha.gov.in/individuals-terrorists-fourth-schedule-unlawful-activities-prevention-act">Individual
 *     terrorists under UAPA</a>
 */
public final class InMhaProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(InMhaProvider.class);

    private static final String DEFAULT_URL =
            "https://www.mha.gov.in/individuals-terrorists-fourth-schedule-unlawful-activities-prevention-act";

    private static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "UAPA-4",
                    "Unlawful Activities (Prevention) Act, 1967, Fourth Schedule",
                    ListSource.IN_MHA);

    private final URI listUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    /** Creates a provider that reads the list from the ministry's site. */
    public InMhaProvider() {
        this(
                URI.create(DEFAULT_URL),
                HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    /**
     * Creates a provider with a custom list URI and HTTP client (for testing).
     *
     * @param listUri the list's first page, without paging parameters
     * @param httpClient the HTTP client to use for requests
     */
    public InMhaProvider(URI listUri, HttpClient httpClient) {
        this.listUri = Objects.requireNonNull(listUri, "listUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.currentMetadata = new ListMetadata(ListSource.IN_MHA, null, null, null, listUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.IN_MHA;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching IN MHA list [uri={}]", listUri);
        Instant start = Instant.now();
        try {
            List<MhaDocumentList.Row> rows =
                    MhaDocumentList.all(httpClient, listUri, ListSource.IN_MHA);
            List<SanctionedEntity> entities = parse(rows);
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    rows.toString()
                                                            .getBytes(StandardCharsets.UTF_8)));
            Instant now = Instant.now();
            currentMetadata =
                    new ListMetadata(ListSource.IN_MHA, now, null, hash, listUri, entities.size());
            log.info(
                    "IN MHA ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    Duration.between(start, now).toMillis());
            return entities;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching IN MHA: " + e.getMessage(), ListSource.IN_MHA, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException("IN MHA fetch interrupted", ListSource.IN_MHA, e);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 algorithm not available", e);
        }
    }

    /** The page carries no ETag or modification date, so every check assumes a change. */
    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return true;
    }

    /**
     * Turns the list's rows, oldest first, into one person each, numbered from 1.
     *
     * @param rows the rows
     * @return the people
     * @throws ListIngestionException if no row names anyone
     */
    static List<SanctionedEntity> parse(List<MhaDocumentList.Row> rows)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            SanctionedEntity entity = person(i + 1, rows.get(i));
            if (entity != null) {
                entities.add(entity);
            }
        }
        if (entities.isEmpty()) {
            throw new ListIngestionException(
                    "India MHA list has no named entries; the page layout may have changed",
                    ListSource.IN_MHA);
        }
        return entities;
    }

    private static SanctionedEntity person(int serial, MhaDocumentList.Row row) {
        List<String> names = new ArrayList<>();
        for (String part : row.title().split("@")) {
            String name = clean(part);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return null;
        }

        NameInfo primary =
                new NameInfo(
                        names.get(0),
                        null,
                        null,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        ScriptType.LATIN);
        List<NameInfo> aliases = new ArrayList<>();
        for (String alias : names.subList(1, names.size())) {
            // One-word aliases such as "Rode" or "Doctor" are nicknames, too common to match on.
            NameStrength strength = alias.contains(" ") ? NameStrength.STRONG : NameStrength.WEAK;
            aliases.add(
                    new NameInfo(
                            alias,
                            null,
                            null,
                            null,
                            null,
                            NameType.AKA,
                            strength,
                            ScriptType.LATIN));
        }

        return new SanctionedEntity(
                "in-mha-" + serial,
                EntityType.INDIVIDUAL,
                ListSource.IN_MHA,
                primary,
                aliases,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Notification: " + row.link(),
                List.of(PROGRAM),
                null,
                Instant.now());
    }

    /** Trims a name and drops the closing period and stray quotes the page puts after it. */
    private static String clean(String name) {
        return name.strip().replaceAll("[\\s.,;\"'“”]+$", "").strip();
    }
}
