package dev.sieve.ingest.eu;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the EU Sanctions Map — entities not present in the EU FSF consolidated list.
 *
 * <p>Uses the regime API to iterate over all sanctions regimes and extract members that have
 * no FSD_ID (i.e., not already in the EU Financial Sanctions Files). The provider makes
 * multiple HTTP requests: one for the regime index, then one per regime for detail data.
 *
 * @see <a href="https://www.sanctionsmap.eu/api/v1/regime">EU Sanctions Map API</a>
 */
public final class EuSanctionsMapProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(EuSanctionsMapProvider.class);

    private static final String DEFAULT_REGIME_URL =
            "https://www.sanctionsmap.eu/api/v1/regime";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final URI regimeUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    public EuSanctionsMapProvider() {
        this(URI.create(DEFAULT_REGIME_URL),
                HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public EuSanctionsMapProvider(URI regimeUri) {
        this(regimeUri, HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public EuSanctionsMapProvider(URI regimeUri, HttpClient httpClient) {
        this.regimeUri = regimeUri;
        this.httpClient = httpClient;
        this.currentMetadata = new ListMetadata(
                ListSource.EU_SANCTIONS_MAP, null, null, null, regimeUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.EU_SANCTIONS_MAP;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching EU Sanctions Map [regimeUrl={}]", regimeUri);
        Instant start = Instant.now();

        try {
            // Step 1: Fetch regime index
            Map<String, Object> regimeIndex = fetchJson(regimeUri);
            List<Map<String, Object>> regimes =
                    (List<Map<String, Object>>) regimeIndex.get("data");
            if (regimes == null) {
                throw new ListIngestionException(
                        "No 'data' array in regime index", ListSource.EU_SANCTIONS_MAP);
            }
            log.info("EU Sanctions Map found {} regimes", regimes.size());

            // Step 2: Fetch each regime and extract non-FSD members
            List<SanctionedEntity> entities = new ArrayList<>();
            for (Map<String, Object> regime : regimes) {
                Object regimeIdObj = regime.get("id");
                if (regimeIdObj == null) continue;
                int regimeId = ((Number) regimeIdObj).intValue();

                List<Object> programme = (List<Object>) regime.get("programme");
                String progName = programme != null && !programme.isEmpty()
                        ? programme.get(0).toString() : "EU";

                URI regimeDetailUri = URI.create(regimeUri + "/" + regimeId);
                Map<String, Object> regimeDetail;
                try {
                    regimeDetail = fetchJson(regimeDetailUri);
                } catch (Exception e) {
                    log.warn("Failed to fetch regime {}: {}", regimeId, e.getMessage());
                    continue;
                }

                Map<String, Object> data = (Map<String, Object>) regimeDetail.get("data");
                if (data == null) continue;

                Map<String, Object> measuresObj = (Map<String, Object>) data.get("measures");
                if (measuresObj == null) continue;
                List<Map<String, Object>> measures =
                        (List<Map<String, Object>>) measuresObj.get("data");
                if (measures == null) continue;

                for (Map<String, Object> measure : measures) {
                    Map<String, Object> listsObj = (Map<String, Object>) measure.get("lists");
                    if (listsObj == null) continue;
                    List<Map<String, Object>> lists =
                            (List<Map<String, Object>>) listsObj.get("data");
                    if (lists == null) continue;

                    for (Map<String, Object> list : lists) {
                        Map<String, Object> membersObj =
                                (Map<String, Object>) list.get("members");
                        if (membersObj == null) continue;
                        List<Object> members = (List<Object>) membersObj.get("data");
                        if (members == null) continue;

                        for (Object memberObj : members) {
                            Map<String, Object> member = (Map<String, Object>) memberObj;
                            if (member.containsKey("data")) {
                                member = (Map<String, Object>) member.get("data");
                            }
                            // Skip entries already in EU FSF
                            if (member.get("FSD_ID") != null) continue;

                            SanctionedEntity entity = parseMember(member, progName);
                            if (entity != null) entities.add(entity);
                        }
                    }
                }
            }

            Instant now = Instant.now();
            String hash = computeSha256(
                    String.valueOf(entities.size()).getBytes());
            currentMetadata = new ListMetadata(
                    ListSource.EU_SANCTIONS_MAP, now, null, hash, regimeUri, entities.size());

            log.info("EU Sanctions Map ingestion complete [entities={}, duration={}ms]",
                    entities.size(), Duration.between(start, now).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Error fetching EU Sanctions Map: " + e.getMessage(),
                    ListSource.EU_SANCTIONS_MAP, e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJson(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "sieve-aml/1.0")
                .GET()
                .build();

        HttpResponse<byte[]> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new ListIngestionException(
                    String.format("EU Sanctions Map fetch failed [status=%d, uri=%s]",
                            response.statusCode(), uri),
                    ListSource.EU_SANCTIONS_MAP);
        }
        return (Map<String, Object>) MAPPER.readValue(response.body(), Object.class);
    }

    private SanctionedEntity parseMember(Map<String, Object> member, String programme) {
        String name = stringVal(member, "name");
        if (name == null || name.isBlank()) return null;

        // Strip alias info from name like "Name (alias Other Name)"
        String cleanName = name;
        List<NameInfo> aliases = new ArrayList<>();
        if (name.contains("(alias")) {
            String[] parts = name.split("\\(alias", 2);
            cleanName = parts[0].strip();
            if (parts.length > 1) {
                String aliasStr = parts[1].replaceAll("\\)$", "").strip();
                if (!aliasStr.isEmpty()) {
                    aliases.add(new NameInfo(
                            aliasStr, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        String typeStr = stringVal(member, "type");
        EntityType entityType = "P".equals(typeStr)
                ? EntityType.INDIVIDUAL : EntityType.ENTITY;

        String creationDate = stringVal(member, "creation_date");
        String id = cleanName.hashCode() + "-" + (creationDate != null ? creationDate : "0");

        NameInfo primaryName = new NameInfo(
                cleanName, null, null, null, null, NameType.PRIMARY, null, null);

        List<SanctionsProgram> programs = new ArrayList<>();
        programs.add(new SanctionsProgram(
                programme, null, ListSource.EU_SANCTIONS_MAP));

        return new SanctionedEntity(
                "eu-map-" + id, entityType, ListSource.EU_SANCTIONS_MAP,
                primaryName, aliases, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                null, programs, null, Instant.now());
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        String s = val.toString();
        return s.isBlank() ? null : s;
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
