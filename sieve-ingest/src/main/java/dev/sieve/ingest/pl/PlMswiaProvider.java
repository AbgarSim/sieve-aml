package dev.sieve.ingest.pl;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the Polish MSWiA national sanctions list.
 *
 * <p>Published as an HTML page with two tables: "Osoby" (persons) and "Podmioty" (companies).
 * Typically contains ~560 entities combined.
 *
 * @see <a href="https://www.gov.pl/web/mswia/lista-osob-i-podmiotow-objetych-sankcjami">
 *     Poland MSWiA Sanctions</a>
 */
public final class PlMswiaProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(PlMswiaProvider.class);

    private static final String DEFAULT_URL =
            "https://www.gov.pl/web/mswia/lista-osob-i-podmiotow-objetych-sankcjami";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final DateTimeFormatter PL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final Pattern TR_PATTERN = Pattern.compile(
            "<tr>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern TD_PATTERN = Pattern.compile(
            "<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    public PlMswiaProvider() {
        this(URI.create(DEFAULT_URL),
                HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public PlMswiaProvider(URI sourceUri) {
        this(sourceUri, HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public PlMswiaProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = sourceUri;
        this.httpClient = httpClient;
        this.currentMetadata = new ListMetadata(
                ListSource.PL_MSWIA, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() { return ListSource.PL_MSWIA; }

    @Override
    public ListMetadata metadata() { return currentMetadata; }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching PL MSWiA list [uri={}]", sourceUri);
        Instant start = Instant.now();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(sourceUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "text/html")
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format("PL MSWiA fetch failed [status=%d]", response.statusCode()),
                        ListSource.PL_MSWIA);
            }

            String html = response.body();
            String hash = computeSha256(html.getBytes(StandardCharsets.UTF_8));
            log.info("PL MSWiA downloaded [chars={}, hash={}]",
                    html.length(), hash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseHtml(html);

            currentMetadata = new ListMetadata(
                    ListSource.PL_MSWIA, Instant.now(), null, hash, sourceUri, entities.size());
            log.info("PL MSWiA ingestion complete [entities={}, duration={}ms]",
                    entities.size(), Duration.between(start, Instant.now()).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching PL MSWiA: " + e.getMessage(), ListSource.PL_MSWIA, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException("PL MSWiA fetch interrupted", ListSource.PL_MSWIA, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during PL MSWiA ingestion: " + e.getMessage(),
                    ListSource.PL_MSWIA, e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) { return true; }

    private List<SanctionedEntity> parseHtml(String html) {
        List<SanctionedEntity> entities = new ArrayList<>();

        // Find the "Osoby" (Persons) section and its table
        int osobyIdx = html.indexOf("<h3>Osoby</h3>");
        int podmiotyIdx = html.indexOf("<h3>Podmioty</h3>");

        if (osobyIdx >= 0 && podmiotyIdx > osobyIdx) {
            String osobySection = html.substring(osobyIdx, podmiotyIdx);
            parseTable(osobySection, EntityType.INDIVIDUAL, "osoby", entities);
        }

        // Find the "Podmioty" (Companies) section and its table
        if (podmiotyIdx >= 0) {
            // Find the next major section or end
            int endIdx = html.indexOf("<h3>Materiały</h3>", podmiotyIdx);
            if (endIdx < 0) endIdx = html.length();
            String podmiotySection = html.substring(podmiotyIdx, endIdx);
            parseTable(podmiotySection, EntityType.ENTITY, "podmioty", entities);
        }

        return entities;
    }

    private void parseTable(String section, EntityType entityType, String tableType,
                            List<SanctionedEntity> entities) {
        Matcher rowMatcher = TR_PATTERN.matcher(section);
        boolean headerSkipped = false;
        int idx = 0;

        while (rowMatcher.find()) {
            String rowHtml = rowMatcher.group(1);
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = TD_PATTERN.matcher(rowHtml);
            while (cellMatcher.find()) {
                String cellContent = cellMatcher.group(1);
                // Strip HTML tags and decode entities
                cellContent = TAG_PATTERN.matcher(cellContent).replaceAll("");
                cellContent = decodeHtmlEntities(cellContent).strip();
                cells.add(cellContent);
            }

            if (cells.isEmpty()) continue;

            // Skip header row
            if (!headerSkipped) {
                headerSkipped = true;
                continue;
            }

            if (cells.size() < 5) continue;

            try {
                SanctionedEntity entity = buildEntity(cells, entityType, tableType, idx++);
                if (entity != null) entities.add(entity);
            } catch (Exception e) {
                log.debug("Skipping malformed row {} in PL MSWiA {}: {}", idx, tableType, e.getMessage());
            }
        }
    }

    private SanctionedEntity buildEntity(List<String> cells, EntityType entityType,
                                          String tableType, int idx) {
        // Column 0: Name
        String name = cells.get(0);
        if (name == null || name.isBlank()) return null;

        NameInfo primaryName = new NameInfo(
                name.strip(), null, null, null, null, NameType.PRIMARY, null, null);

        // Column 4: Listing date (dd.MM.yyyy)
        List<LocalDate> listingDates = new ArrayList<>();
        if (cells.size() > 4) {
            LocalDate listDate = parseDateSafe(cells.get(4));
            if (listDate != null) listingDates.add(listDate);
        }

        List<SanctionsProgram> programs = List.of(new SanctionsProgram(
                "PL MSWiA", null, ListSource.PL_MSWIA));

        String id = tableType + "-" + idx;

        return new SanctionedEntity(
                "pl-" + id, entityType, ListSource.PL_MSWIA,
                primaryName, List.of(), List.of(), List.of(),
                List.of(), List.of(), listingDates, List.of(),
                null, programs, null, Instant.now());
    }

    private static String decodeHtmlEntities(String text) {
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&oacute;", "ó")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ");
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip();
        try { return LocalDate.parse(cleaned, PL_DATE_FORMAT); }
        catch (DateTimeParseException e) {
            try { return LocalDate.parse(cleaned); }
            catch (DateTimeParseException e2) { return null; }
        }
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
