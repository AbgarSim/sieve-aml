package dev.sieve.ingest.ua;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the Ukrainian NSDC (National Security and Defence Council) sanctions list.
 *
 * <p>Very large, Russia-focused. Published as JSON via a v2 API that requires an API key.
 * Fetches both individual and legal entity endpoints. Typically contains ~22,000 entities.
 *
 * <p>Requires the {@code SIEVE_NSDC_API_KEY} environment variable to be set.
 *
 * @see <a href="https://drs.nsdc.gov.ua/">Ukraine NSDC Sanctions</a>
 */
public final class UaNsdcProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(UaNsdcProvider.class);

    private static final String DEFAULT_BASE_URL = "https://api-drs.nsdc.gov.ua";
    private static final String API_KEY_ENV = "SIEVE_NSDC_API_KEY";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter UA_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final URI baseUri;
    private final HttpClient httpClient;
    private final String apiKey;
    private volatile ListMetadata currentMetadata;

    public UaNsdcProvider() {
        this(URI.create(DEFAULT_BASE_URL),
                HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)),
                System.getenv(API_KEY_ENV));
    }

    public UaNsdcProvider(URI baseUri) {
        this(baseUri, HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)),
                System.getenv(API_KEY_ENV));
    }

    public UaNsdcProvider(URI baseUri, HttpClient httpClient, String apiKey) {
        this.baseUri = baseUri;
        this.httpClient = httpClient;
        this.apiKey = apiKey;
        this.currentMetadata = new ListMetadata(
                ListSource.UA_NSDC, null, null, null, baseUri, 0);
    }

    @Override
    public ListSource source() { return ListSource.UA_NSDC; }

    @Override
    public ListMetadata metadata() { return currentMetadata; }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ListIngestionException(
                    "Missing API key. Set environment variable " + API_KEY_ENV,
                    ListSource.UA_NSDC);
        }

        log.info("Fetching UA NSDC list [base={}]", baseUri);
        Instant start = Instant.now();

        try {
            List<SanctionedEntity> entities = new ArrayList<>();

            // Fetch individuals
            List<Map<String, Object>> individuals = fetchEndpoint(
                    "/v2/subjects?subjectType=individual");
            log.info("UA NSDC individuals: {} records", individuals.size());
            for (Map<String, Object> item : individuals) {
                SanctionedEntity entity = parseSubject(item, EntityType.INDIVIDUAL);
                if (entity != null) entities.add(entity);
            }

            // Fetch legal entities
            List<Map<String, Object>> legals = fetchEndpoint(
                    "/v2/subjects?subjectType=legal");
            log.info("UA NSDC legal entities: {} records", legals.size());
            for (Map<String, Object> item : legals) {
                SanctionedEntity entity = parseSubject(item, EntityType.ENTITY);
                if (entity != null) entities.add(entity);
            }

            String hash = computeSha256(
                    (individuals.size() + ":" + legals.size()).getBytes());
            currentMetadata = new ListMetadata(
                    ListSource.UA_NSDC, Instant.now(), null, hash, baseUri, entities.size());
            log.info("UA NSDC ingestion complete [entities={}, duration={}ms]",
                    entities.size(), Duration.between(start, Instant.now()).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching UA NSDC: " + e.getMessage(), ListSource.UA_NSDC, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException("UA NSDC fetch interrupted", ListSource.UA_NSDC, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during UA NSDC ingestion: " + e.getMessage(),
                    ListSource.UA_NSDC, e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) { return true; }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchEndpoint(String path)
            throws IOException, InterruptedException, ListIngestionException {
        URI uri = URI.create(baseUri.toString() + path);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("x-cota-public-api-key", apiKey)
                .GET()
                .build();

        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new ListIngestionException(
                    String.format("UA NSDC fetch failed [path=%s, status=%d]",
                            path, response.statusCode()),
                    ListSource.UA_NSDC);
        }

        return MAPPER.readValue(response.body(), List.class);
    }

    @SuppressWarnings("unchecked")
    private SanctionedEntity parseSubject(Map<String, Object> item, EntityType entityType) {
        Object sidObj = item.get("sid");
        String id = sidObj != null ? sidObj.toString() : null;

        String name = strVal(item, "name");
        if (name == null || name.isBlank()) return null;

        NameInfo primaryName = new NameInfo(
                name, null, null, null, null, NameType.PRIMARY, null, null);

        List<NameInfo> aliases = new ArrayList<>();

        // aliases field contains Russian/English transliterations
        Object aliasesObj = item.get("aliases");
        if (aliasesObj instanceof String aliasStr && !aliasStr.isBlank()) {
            aliases.add(new NameInfo(aliasStr, null, null, null, null, NameType.AKA, null, null));
        } else if (aliasesObj instanceof List) {
            for (Object alt : (List<Object>) aliasesObj) {
                String a = alt instanceof String s ? s : null;
                if (a != null && !a.isBlank() && !a.equals(name)) {
                    aliases.add(new NameInfo(a, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        // translit is a latin transliteration
        String translit = strVal(item, "translit");
        if (translit != null && !translit.equals(name)) {
            aliases.add(new NameInfo(translit, null, null, null, null, NameType.AKA, null, null));
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        String bd = strVal(item, "bd");
        if (bd != null) {
            LocalDate dob = parseDateSafe(bd);
            if (dob != null) datesOfBirth.add(dob);
        }

        List<String> nationalities = new ArrayList<>();
        Object citizenshipsObj = item.get("citizenships");
        if (citizenshipsObj instanceof List) {
            for (Object c : (List<Object>) citizenshipsObj) {
                if (c instanceof String s && !s.isBlank()) nationalities.add(s);
            }
        } else if (citizenshipsObj instanceof String s && !s.isBlank()) {
            nationalities.add(s);
        }

        List<SanctionsProgram> programs = List.of(
                new SanctionsProgram("NSDC Sanctions", "Ukraine NSDC", ListSource.UA_NSDC));

        String entityId = id != null ? id : String.valueOf(name.hashCode());

        return new SanctionedEntity(
                "ua-" + entityId, entityType, ListSource.UA_NSDC,
                primaryName, aliases, List.of(), List.of(),
                nationalities, List.of(), datesOfBirth, List.of(),
                null, programs, null, Instant.now());
    }

    private static String strVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip();
        try { return LocalDate.parse(cleaned, UA_DATE_FORMAT); }
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
