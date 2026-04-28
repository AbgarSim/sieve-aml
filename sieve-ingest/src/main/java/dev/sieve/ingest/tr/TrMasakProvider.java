package dev.sieve.ingest.tr;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
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
 * Fetches and parses the Turkish MASAK (Financial Crimes Investigation Board) sanctions list.
 *
 * <p>The FCIB publishes asset freezing decisions as XLSX files linked from a React SPA.
 * Uses Playwright headless browser to render the JS-based frontend and discover XLSX URLs,
 * then downloads and parses the spreadsheets. Typically contains ~2,300 entities across
 * three categories (B, C, D). Category A (UN Security Council) is skipped as it duplicates
 * the UN consolidated list.
 *
 * @see <a href="https://en.hmb.gov.tr/fcib-tf-current-list">Turkey MASAK</a>
 */
public final class TrMasakProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(TrMasakProvider.class);

    private static final String DEFAULT_PAGE_URL =
            "https://en.hmb.gov.tr/fcib-tf-current-list";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final DateTimeFormatter TR_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Pattern XLSX_HREF = Pattern.compile(
            "href=[\"'](https?://[^\"']*\\.xlsx)[\"']", Pattern.CASE_INSENSITIVE);

    // Category slugs to process (skip 5madde_ing = UN Security Council)
    private static final Map<String, String> LABEL_MAPPING = Map.of(
            "6madde_ing", "B - Foreign government requests (Art. 6)",
            "7madde_ing", "C - Domestic legal actions (Art. 7)",
            "3a3b", "D - WMD proliferation prevention (Art. 3A/3B)"
    );

    private final String pageUrl;
    private final HttpClient httpClient;
    private volatile ListMetadata currentMetadata;

    public TrMasakProvider() {
        this(DEFAULT_PAGE_URL, HttpClientFactory.createTrustAllClient(Duration.ofSeconds(30)));
    }

    public TrMasakProvider(String pageUrl, HttpClient httpClient) {
        this.pageUrl = pageUrl;
        this.httpClient = httpClient;
        this.currentMetadata = new ListMetadata(
                ListSource.TR_MASAK, null, null, null, URI.create(pageUrl), 0);
    }

    @Override
    public ListSource source() { return ListSource.TR_MASAK; }

    @Override
    public ListMetadata metadata() { return currentMetadata; }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching TR MASAK list [page={}]", pageUrl);
        Instant start = Instant.now();

        try {
            // Step 1: Use Playwright to render the React SPA and discover category links
            List<String> categoryUrls = discoverCategoryUrls();
            log.info("TR MASAK: discovered {} category URLs", categoryUrls.size());

            // Step 2: For each category, render the page and find XLSX links
            List<XlsxSource> xlsxSources = new ArrayList<>();
            for (String catUrl : categoryUrls) {
                String slug = catUrl.substring(catUrl.lastIndexOf('/') + 1).toLowerCase();
                if (!LABEL_MAPPING.containsKey(slug)) {
                    log.debug("Skipping unknown/UN category: {}", slug);
                    continue;
                }
                String xlsxUrl = discoverXlsxUrl(catUrl);
                if (xlsxUrl != null) {
                    xlsxSources.add(new XlsxSource(xlsxUrl, LABEL_MAPPING.get(slug)));
                    log.info("TR MASAK: found XLSX for {} -> {}", slug, xlsxUrl);
                }
            }

            // Step 3: Download and parse each XLSX
            List<SanctionedEntity> entities = new ArrayList<>();
            for (XlsxSource src : xlsxSources) {
                List<SanctionedEntity> parsed = downloadAndParseXlsx(src.url, src.program);
                log.info("TR MASAK: parsed {} entities from {}", parsed.size(), src.program);
                entities.addAll(parsed);
            }

            String hash = computeSha256(
                    String.valueOf(entities.size()).getBytes());
            currentMetadata = new ListMetadata(
                    ListSource.TR_MASAK, Instant.now(), null, hash,
                    URI.create(pageUrl), entities.size());
            log.info("TR MASAK ingestion complete [entities={}, duration={}ms]",
                    entities.size(), Duration.between(start, Instant.now()).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Error during TR MASAK ingestion: " + e.getMessage(),
                    ListSource.TR_MASAK, e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) { return true; }

    private List<String> discoverCategoryUrls() {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(pageUrl);
            page.waitForSelector("table.table.table-bordered", new Page.WaitForSelectorOptions()
                    .setTimeout(30000));

            // Extract all links from the bordered table
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) page.evalOnSelectorAll(
                    "table.table-bordered a[href]",
                    "els => els.map(e => e.href)");
            return urls;
        }
    }

    private String discoverXlsxUrl(String categoryUrl) {
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(
                     new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            page.navigate(categoryUrl);
            // Wait for content to render
            page.waitForTimeout(5000);
            String html = page.content();
            Matcher m = XLSX_HREF.matcher(html);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            log.warn("Failed to discover XLSX URL from {}: {}", categoryUrl, e.getMessage());
            return null;
        }
    }

    private List<SanctionedEntity> downloadAndParseXlsx(String xlsxUrl, String program)
            throws ListIngestionException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(xlsxUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "*/*")
                    .GET()
                    .build();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new ListIngestionException(
                        String.format("TR MASAK XLSX fetch failed [url=%s, status=%d]",
                                xlsxUrl, response.statusCode()),
                        ListSource.TR_MASAK);
            }

            return parseXlsx(response.body(), program);

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to download TR MASAK XLSX from " + xlsxUrl + ": " + e.getMessage(),
                    ListSource.TR_MASAK, e);
        }
    }

    private List<SanctionedEntity> parseXlsx(byte[] data, String program)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             Workbook workbook = WorkbookFactory.create(bais)) {

            for (Sheet sheet : workbook) {
                Row headerRow = sheet.getRow(0);
                if (headerRow == null) continue;

                Map<String, Integer> colIndex = new HashMap<>();
                for (Cell cell : headerRow) {
                    String header = cellToString(cell);
                    if (header != null) {
                        colIndex.put(normalizeHeader(header), cell.getColumnIndex());
                    }
                }

                // Must have at least a name column
                Integer nameIdx = findCol(colIndex, "name", "adi_soyadi", "gercek_kisi");
                if (nameIdx == null) continue;

                for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    try {
                        SanctionedEntity entity = parseRow(row, colIndex, nameIdx, program, i);
                        if (entity != null) entities.add(entity);
                    } catch (Exception e) {
                        log.debug("Skipping malformed row {} in TR MASAK XLSX: {}",
                                i, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse TR MASAK XLSX: " + e.getMessage(),
                    ListSource.TR_MASAK, e);
        }
        return entities;
    }

    private SanctionedEntity parseRow(Row row, Map<String, Integer> colIndex,
                                       int nameIdx, String program, int rowNum) {
        String name = cellToString(row.getCell(nameIdx));
        if (name == null || name.isBlank()) return null;

        // Check for legal entity name column
        String legalName = cellValNorm(row, colIndex, "legal_entity_name", "tuzel_kurulus");
        EntityType entityType = EntityType.INDIVIDUAL;
        if (legalName != null && !legalName.isBlank()) {
            name = legalName;
            entityType = EntityType.ENTITY;
        }

        NameInfo primaryName = new NameInfo(
                name, null, null, null, null, NameType.PRIMARY, null, null);

        List<NameInfo> aliases = new ArrayList<>();
        String aliasStr = cellValNorm(row, colIndex, "alias", "diger_isimleri");
        if (aliasStr != null && !aliasStr.isBlank()) {
            for (String a : aliasStr.split(";")) {
                String trimmed = a.strip();
                if (!trimmed.isEmpty() && !trimmed.equals(name)) {
                    aliases.add(new NameInfo(
                            trimmed, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        String prevName = cellValNorm(row, colIndex, "previous_name", "eski_adi");
        if (prevName != null && !prevName.isBlank() && !prevName.equals(name)) {
            aliases.add(new NameInfo(
                    prevName, null, null, null, null, NameType.FKA, null, null));
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        String dobStr = cellValNorm(row, colIndex, "birth_date", "dogum_tarihi");
        if (dobStr != null) {
            LocalDate dob = parseDateSafe(dobStr);
            if (dob != null) datesOfBirth.add(dob);
        }

        List<String> nationalities = new ArrayList<>();
        String natStr = cellValNorm(row, colIndex, "nationality", "uyrugu");
        if (natStr != null && !natStr.isBlank()) {
            nationalities.add(natStr.strip());
        }

        List<String> placesOfBirth = new ArrayList<>();
        String pob = cellValNorm(row, colIndex, "birth_place", "dogum_yeri");
        if (pob != null && !pob.isBlank()) placesOfBirth.add(pob.strip());

        List<SanctionsProgram> programs = List.of(
                new SanctionsProgram(program, "Turkey MASAK", ListSource.TR_MASAK));

        String id = "r" + rowNum + "-" + name.hashCode();

        return new SanctionedEntity(
                "tr-" + id, entityType, ListSource.TR_MASAK,
                primaryName, aliases, List.of(), List.of(),
                nationalities, List.of(), datesOfBirth, placesOfBirth,
                null, programs, null, Instant.now());
    }

    /** Normalize header text for matching: lowercase, remove diacritics, collapse whitespace. */
    private static String normalizeHeader(String header) {
        return header.toLowerCase()
                .replaceAll("[İı]", "i")
                .replaceAll("[Şş]", "s")
                .replaceAll("[Ğğ]", "g")
                .replaceAll("[Üü]", "u")
                .replaceAll("[Öö]", "o")
                .replaceAll("[Çç]", "c")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static Integer findCol(Map<String, Integer> colIndex, String... candidates) {
        for (String c : candidates) {
            for (var entry : colIndex.entrySet()) {
                if (entry.getKey().contains(c)) return entry.getValue();
            }
        }
        return null;
    }

    private static String cellValNorm(Row row, Map<String, Integer> colIndex,
                                       String... candidates) {
        Integer idx = findCol(colIndex, candidates);
        if (idx == null) return null;
        return cellToString(row.getCell(idx));
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
        String cleaned = dateStr.strip();
        try { return LocalDate.parse(cleaned, TR_DATE_FORMAT); }
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

    private record XlsxSource(String url, String program) {}
}
