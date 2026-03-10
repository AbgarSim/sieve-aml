package dev.sieve.ingest.jp;

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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses the Japan Ministry of Finance sanctions list.
 *
 * <p>Published as XLSX with a dynamic URL that changes on each update. The provider first
 * scrapes the MoF HTML listing page to discover the current XLSX download link, then
 * fetches and parses the spreadsheet. Typically contains ~3,900 entities. The list covers
 * designations under Japan's Foreign Exchange and Foreign Trade Act.
 *
 * @see <a href="https://www.mof.go.jp/policy/international_policy/gaitame_kawase/gaitame/economic_sanctions/list.html">
 *     Japan MoF Sanctions</a>
 */
public final class JpMofProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(JpMofProvider.class);

    private static final String DEFAULT_LIST_PAGE_URL =
            "https://www.mof.go.jp/policy/international_policy/gaitame_kawase/gaitame/economic_sanctions/list.html";
    private static final Pattern XLSX_HREF_PATTERN =
            Pattern.compile("href=\"([^\"]*\\.xlsx?)\"", Pattern.CASE_INSENSITIVE);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final URI listPageUri;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    public JpMofProvider() {
        this(URI.create(DEFAULT_LIST_PAGE_URL),
                HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public JpMofProvider(URI listPageUri) {
        this(listPageUri, HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public JpMofProvider(URI listPageUri, HttpClient httpClient) {
        this.listPageUri = listPageUri;
        this.httpClient = httpClient;
        this.currentMetadata = new ListMetadata(
                ListSource.JP_MOF, null, null, null, listPageUri, 0);
    }

    @Override
    public ListSource source() {
        return ListSource.JP_MOF;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching JP MoF list [listPage={}]", listPageUri);
        Instant start = Instant.now();

        try {
            // Step 1: Scrape the HTML page to find the XLSX URL
            URI xlsxUri = discoverXlsxUrl();
            log.info("JP MoF discovered XLSX URL [uri={}]", xlsxUri);

            // Step 2: Download the XLSX file
            HttpRequest xlsxRequest = HttpRequest.newBuilder()
                    .uri(xlsxUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "sieve-aml/1.0")
                    .GET()
                    .build();

            HttpResponse<byte[]> xlsxResponse =
                    httpClient.send(xlsxRequest, HttpResponse.BodyHandlers.ofByteArray());

            if (xlsxResponse.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format("JP MoF XLSX fetch failed [status=%d, uri=%s]",
                                xlsxResponse.statusCode(), xlsxUri),
                        ListSource.JP_MOF);
            }

            byte[] body = xlsxResponse.body();
            String contentHash = computeSha256(body);
            log.info("JP MoF downloaded XLSX [bytes={}, hash={}]",
                    body.length, contentHash.substring(0, 12) + "...");

            // Step 3: Parse the XLSX
            List<SanctionedEntity> entities = parseXlsx(body);

            Instant now = Instant.now();
            currentMetadata = new ListMetadata(
                    ListSource.JP_MOF, now, null, contentHash, xlsxUri, entities.size());

            log.info("JP MoF ingestion complete [entities={}, duration={}ms]",
                    entities.size(), Duration.between(start, now).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Error fetching JP MoF: " + e.getMessage(), ListSource.JP_MOF, e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return true;
    }

    private URI discoverXlsxUrl() throws ListIngestionException {
        try {
            HttpRequest htmlRequest = HttpRequest.newBuilder()
                    .uri(listPageUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "sieve-aml/1.0")
                    .GET()
                    .build();

            HttpResponse<String> htmlResponse =
                    httpClient.send(htmlRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (htmlResponse.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format("JP MoF list page fetch failed [status=%d]",
                                htmlResponse.statusCode()),
                        ListSource.JP_MOF);
            }

            Matcher matcher = XLSX_HREF_PATTERN.matcher(htmlResponse.body());
            if (!matcher.find()) {
                throw new ListIngestionException(
                        "Could not find XLSX link on JP MoF list page", ListSource.JP_MOF);
            }

            String href = matcher.group(1);
            // Resolve relative URL against the list page URL
            return listPageUri.resolve(href);

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Error discovering JP MoF XLSX URL: " + e.getMessage(),
                    ListSource.JP_MOF, e);
        }
    }

    private List<SanctionedEntity> parseXlsx(byte[] responseBody)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(responseBody);
             Workbook workbook = WorkbookFactory.create(bais)) {

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) continue;

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) continue;

                Map<String, Integer> colIndex = new HashMap<>();
                for (Cell cell : headerRow) {
                    String header = cellToString(cell);
                    if (header != null) {
                        colIndex.put(header.toLowerCase().strip(), cell.getColumnIndex());
                    }
                }

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    try {
                        SanctionedEntity entity = parseRow(row, colIndex, s, i);
                        if (entity != null) entities.add(entity);
                    } catch (Exception e) {
                        log.debug("Skipping malformed row {} in Japan MoF XLSX sheet {}: {}",
                                i, s, e.getMessage());
                    }
                }
            }

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse Japan MoF XLSX: " + e.getMessage(), ListSource.JP_MOF, e);
        }
        return entities;
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

    private SanctionedEntity parseRow(Row row, Map<String, Integer> colIndex,
                                       int sheetIdx, int rowNum) {
        // Try various column name patterns (Japanese MoF may use Japanese or English headers)
        String name = firstNonNull(row, colIndex, "name", "名前", "氏名", "名称");
        if (name == null || name.isBlank()) {
            // Fallback: read first column
            Cell first = row.getCell(0);
            name = cellToString(first);
        }
        if (name == null || name.isBlank()) return null;

        String typeStr = firstNonNull(row, colIndex, "type", "種別", "区分");
        EntityType entityType = EntityType.INDIVIDUAL;
        if (typeStr != null && (typeStr.contains("団体") || typeStr.toLowerCase().contains("entit"))) {
            entityType = EntityType.ENTITY;
        }

        NameInfo primaryName = new NameInfo(
                name, null, null, null, null, NameType.PRIMARY, null, null);

        List<NameInfo> aliases = new ArrayList<>();
        String aliasStr = firstNonNull(row, colIndex, "aliases", "alias", "別名", "aka");
        if (aliasStr != null && !aliasStr.isBlank()) {
            for (String a : aliasStr.split("[;,、]")) {
                String trimmed = a.strip();
                if (!trimmed.isEmpty() && !trimmed.equals(name)) {
                    aliases.add(new NameInfo(
                            trimmed, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        List<SanctionsProgram> programs = new ArrayList<>();
        String program = firstNonNull(row, colIndex, "program", "programme", "根拠法", "措置");
        if (program != null && !program.isBlank()) {
            programs.add(new SanctionsProgram(program.strip(), null, ListSource.JP_MOF));
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        String dobStr = firstNonNull(row, colIndex, "date of birth", "dob", "生年月日");
        if (dobStr != null) {
            LocalDate dob = parseDateSafe(dobStr);
            if (dob != null) datesOfBirth.add(dob);
        }

        return new SanctionedEntity(
                "jp-" + sheetIdx + "-" + rowNum, entityType, ListSource.JP_MOF,
                primaryName, aliases, List.of(), List.of(),
                List.of(), List.of(), datesOfBirth, List.of(),
                null, programs, null, Instant.now());
    }

    private static String firstNonNull(Row row, Map<String, Integer> colIndex, String... keys) {
        for (String key : keys) {
            Integer idx = colIndex.get(key);
            if (idx != null) {
                Cell cell = row.getCell(idx);
                String val = cellToString(cell);
                if (val != null) return val;
            }
        }
        return null;
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
        catch (DateTimeParseException e) { return null; }
    }
}
