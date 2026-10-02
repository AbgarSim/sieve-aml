package dev.sieve.ingest.au;

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
import java.io.ByteArrayInputStream;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the Australian DFAT Consolidated Sanctions list.
 *
 * <p>Published by the Department of Foreign Affairs and Trade as XLSX. Covers ~15 sanctions
 * regimes. Typically contains ~5,100 entities. Requires a browser-like User-Agent header
 * as the DFAT CDN blocks non-browser clients.
 *
 * @see <a href="https://www.dfat.gov.au/international-relations/security/sanctions/consolidated-list">
 *     DFAT Sanctions</a>
 */
public final class AuDfatProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(AuDfatProvider.class);

    private static final String DEFAULT_URL =
            "https://www.dfat.gov.au/sites/default/files/Australian_Sanctions_Consolidated_List.xlsx";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(300);
    private static final DateTimeFormatter AU_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final URI sourceUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    public AuDfatProvider() {
        this(URI.create(DEFAULT_URL),
                HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public AuDfatProvider(URI sourceUri) {
        this(sourceUri, HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public AuDfatProvider(URI sourceUri, HttpClient httpClient) {
        this.sourceUri = sourceUri;
        this.httpClient = httpClient;
        this.currentMetadata = new ListMetadata(
                ListSource.AU_DFAT, null, null, null, sourceUri, 0);
    }

    @Override
    public ListSource source() { return ListSource.AU_DFAT; }

    @Override
    public ListMetadata metadata() { return currentMetadata; }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching AU DFAT list [uri={}]", sourceUri);
        Instant start = Instant.now();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(sourceUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "*/*")
                    .header("User-Agent", BROWSER_UA)
                    .GET()
                    .build();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format("AU DFAT fetch failed [status=%d]", response.statusCode()),
                        ListSource.AU_DFAT);
            }

            byte[] body = response.body();
            String hash = computeSha256(body);
            String etag = response.headers().firstValue("ETag").orElse(null);
            log.info("AU DFAT downloaded [bytes={}, etag={}, hash={}]",
                    body.length, etag, hash.substring(0, 12) + "...");

            List<SanctionedEntity> entities = parseXlsx(body);

            currentMetadata = new ListMetadata(
                    ListSource.AU_DFAT, Instant.now(), etag, hash, sourceUri, entities.size());
            log.info("AU DFAT ingestion complete [entities={}, duration={}ms]",
                    entities.size(), Duration.between(start, Instant.now()).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Network error fetching AU DFAT: " + e.getMessage(), ListSource.AU_DFAT, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ListIngestionException("AU DFAT fetch interrupted", ListSource.AU_DFAT, e);
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Unexpected error during AU DFAT ingestion: " + e.getMessage(),
                    ListSource.AU_DFAT, e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) { return true; }

    private List<SanctionedEntity> parseXlsx(byte[] responseBody)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(responseBody);
             Workbook workbook = WorkbookFactory.create(bais)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return entities;

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return entities;

            Map<String, Integer> colIndex = new HashMap<>();
            for (Cell cell : headerRow) {
                String header = cellToString(cell);
                if (header != null) {
                    colIndex.put(header.toLowerCase().strip(), cell.getColumnIndex());
                }
            }

            // Group rows by cleaned reference (numeric part only, e.g. "101a" -> "101")
            Map<String, List<Map<String, String>>> groups = new java.util.LinkedHashMap<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String ref = cellVal(row, colIndex, "reference");
                if (ref == null || ref.isBlank()) continue;
                String cleanRef = cleanReference(ref);
                if (cleanRef == null) continue;

                Map<String, String> rowData = new HashMap<>();
                for (var entry : colIndex.entrySet()) {
                    Cell cell = row.getCell(entry.getValue());
                    String val = cellToString(cell);
                    if (val != null) rowData.put(entry.getKey(), val);
                }
                groups.computeIfAbsent(cleanRef, k -> new ArrayList<>()).add(rowData);
            }

            // Process each group into a single entity
            for (var entry : groups.entrySet()) {
                try {
                    SanctionedEntity entity = buildEntity(entry.getKey(), entry.getValue());
                    if (entity != null) entities.add(entity);
                } catch (Exception e) {
                    log.debug("Skipping malformed ref {} in AU DFAT: {}", entry.getKey(), e.getMessage());
                }
            }

        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse AU DFAT XLSX: " + e.getMessage(), ListSource.AU_DFAT, e);
        }
        return entities;
    }

    /** Extracts the numeric prefix from a reference like "101a" -> "101". */
    private static String cleanReference(String ref) {
        StringBuilder sb = new StringBuilder();
        for (char c : ref.toCharArray()) {
            if (Character.isDigit(c)) sb.append(c);
            else break;
        }
        return !sb.isEmpty() ? sb.toString() : null;
    }

    private SanctionedEntity buildEntity(String reference, List<Map<String, String>> rows) {
        String primaryName = null;
        EntityType entityType = EntityType.INDIVIDUAL;
        List<NameInfo> aliases = new ArrayList<>();
        List<LocalDate> datesOfBirth = new ArrayList<>();
        List<String> placesOfBirth = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        List<SanctionsProgram> programs = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String nameType = row.getOrDefault("name type", "");
            String name = row.get("name of individual or entity");
            if (name == null || name.isBlank()) continue;

            String typeStr = row.get("type");
            if (typeStr != null) {
                String tl = typeStr.toLowerCase();
                if (tl.contains("entity")) entityType = EntityType.ENTITY;
                else if (tl.contains("vessel")) entityType = EntityType.VESSEL;
            }

            if ("Primary Name".equalsIgnoreCase(nameType)) {
                if (primaryName == null) primaryName = name;
                else aliases.add(new NameInfo(name, null, null, null, null, NameType.AKA, null, null));
            } else {
                // Alias, Original Script, etc.
                aliases.add(new NameInfo(name, null, null, null, null, NameType.AKA, null, null));
            }

            String dobStr = row.get("date of birth");
            if (dobStr != null) {
                LocalDate dob = parseDateSafe(dobStr);
                if (dob != null && !datesOfBirth.contains(dob)) datesOfBirth.add(dob);
            }

            String pob = row.get("place of birth");
            if (pob != null && !pob.isBlank() && !placesOfBirth.contains(pob.strip()))
                placesOfBirth.add(pob.strip());

            String cit = row.get("citizenship");
            if (cit != null && !cit.isBlank() && !nationalities.contains(cit.strip()))
                nationalities.add(cit.strip());

            String committee = row.get("committees");
            if (committee != null && !committee.isBlank()) {
                SanctionsProgram prog = new SanctionsProgram(committee.strip(), null, ListSource.AU_DFAT);
                if (programs.stream().noneMatch(p -> committee.strip().equals(p.name())))
                    programs.add(prog);
            }
        }

        if (primaryName == null) return null;

        NameInfo primary = new NameInfo(
                primaryName, null, null, null, null, NameType.PRIMARY, null, null);

        return new SanctionedEntity(
                "au-" + reference, entityType, ListSource.AU_DFAT,
                primary, aliases, List.of(), List.of(),
                nationalities, List.of(), datesOfBirth, placesOfBirth,
                null, programs, null, Instant.now());
    }

    private static String cellVal(Row row, Map<String, Integer> colIndex, String key) {
        Integer idx = colIndex.get(key);
        if (idx == null) return null;
        Cell cell = row.getCell(idx);
        return cellToString(cell);
    }

    private static String cellToString(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> {
                String val = cell.getStringCellValue();
                yield (val != null && !val.isBlank()) ? val.strip() : null;
            }
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try { return LocalDate.parse(dateStr.strip()); }
        catch (DateTimeParseException e) {
            try { return LocalDate.parse(dateStr.strip(), AU_DATE_FORMAT); }
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
