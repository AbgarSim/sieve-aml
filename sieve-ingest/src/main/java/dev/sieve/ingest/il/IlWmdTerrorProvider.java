package dev.sieve.ingest.il;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.ListMetadata;
import dev.sieve.ingest.ListProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches and parses Israeli WMD and Terrorism sanctions lists.
 *
 * <p>Downloads two XLSX files (organizations and individuals) from the Israeli NBCTF site. Uses
 * Playwright headless browser to bypass Incapsula WAF protection. Typically contains ~1,200
 * entities combined.
 *
 * @see <a href="https://nbctf.mod.gov.il/en">Israel NBCTF</a>
 */
public final class IlWmdTerrorProvider implements ListProvider {

    private static final Logger log = LoggerFactory.getLogger(IlWmdTerrorProvider.class);

    private static final String ORG_URL =
            "https://nbctf.mod.gov.il/he/Announcements/Documents/NBCTFIsrael%20-%20Terror%20Organization%20Designation%20List_XL.xlsx";
    private static final String PEOPLE_URL =
            "https://nbctf.mod.gov.il/he/Announcements/Documents/NBCTF%20Israel%20designation%20Individuals_XL.xlsx";
    private static final String LANDING_PAGE =
            "https://nbctf.mod.gov.il/en/Minister%20Sanctions/Designation/Pages/downloads.aspx";

    private static final DateTimeFormatter IL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern NA_VALUE = Pattern.compile("^=?[\"-/]+$");

    private volatile ListMetadata currentMetadata;

    public IlWmdTerrorProvider() {
        this.currentMetadata =
                new ListMetadata(
                        ListSource.IL_WMD_TERROR, null, null, null, URI.create(ORG_URL), 0);
    }

    @Override
    public ListSource source() {
        return ListSource.IL_WMD_TERROR;
    }

    @Override
    public ListMetadata metadata() {
        return currentMetadata;
    }

    @Override
    public List<SanctionedEntity> fetch() throws ListIngestionException {
        log.info("Fetching IL WMD/Terror lists via Playwright");
        Instant start = Instant.now();

        try (Playwright pw = Playwright.create();
                Browser browser =
                        pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {

            // First visit the main NBCTF page to pass Incapsula JS challenge
            Page page = browser.newPage();
            page.navigate(LANDING_PAGE);
            // Wait for Incapsula challenge to resolve
            page.waitForTimeout(5000);
            log.info("IL WMD/Terror: Incapsula challenge passed, session established");

            List<SanctionedEntity> entities = new ArrayList<>();

            // Download organizations XLSX using the established session
            byte[] orgData = downloadFile(page, ORG_URL, "organizations.xlsx");
            log.info("IL WMD/Terror: downloaded organizations XLSX [bytes={}]", orgData.length);
            entities.addAll(parseOrganizations(orgData));

            // Download individuals XLSX
            byte[] peopleData = downloadFile(page, PEOPLE_URL, "individuals.xlsx");
            log.info("IL WMD/Terror: downloaded individuals XLSX [bytes={}]", peopleData.length);
            entities.addAll(parseIndividuals(peopleData));

            String hash = computeSha256((orgData.length + ":" + peopleData.length).getBytes());
            currentMetadata =
                    new ListMetadata(
                            ListSource.IL_WMD_TERROR,
                            Instant.now(),
                            null,
                            hash,
                            URI.create(ORG_URL),
                            entities.size());
            log.info(
                    "IL WMD/Terror ingestion complete [entities={}, duration={}ms]",
                    entities.size(),
                    Duration.between(start, Instant.now()).toMillis());
            return entities;

        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Error during IL WMD/Terror ingestion: " + e.getMessage(),
                    ListSource.IL_WMD_TERROR,
                    e);
        }
    }

    @Override
    public boolean hasUpdates(ListMetadata previousMetadata) {
        return true;
    }

    private byte[] downloadFile(Page page, String url, String label) throws ListIngestionException {
        try {
            // Use the existing page session (with Incapsula cookies) to trigger download
            Download download =
                    page.waitForDownload(
                            new Page.WaitForDownloadOptions().setTimeout(60000),
                            () -> page.evaluate("url => { location.href = url; }", url));

            Path tempFile = Files.createTempFile("il-wmd-", ".xlsx");
            try {
                download.saveAs(tempFile);
                byte[] data = Files.readAllBytes(tempFile);
                if (data.length < 500) {
                    throw new ListIngestionException(
                            "IL WMD/Terror download too small for "
                                    + label
                                    + " ("
                                    + data.length
                                    + " bytes, likely blocked)",
                            ListSource.IL_WMD_TERROR);
                }
                return data;
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (ListIngestionException e) {
            throw e;
        } catch (IOException e) {
            throw new ListIngestionException(
                    "IO error downloading " + label + ": " + e.getMessage(),
                    ListSource.IL_WMD_TERROR,
                    e);
        }
    }

    private List<SanctionedEntity> parseOrganizations(byte[] data) throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                Workbook workbook = WorkbookFactory.create(bais)) {

            for (Sheet sheet : workbook) {
                Map<String, Integer> colIndex = findHeaders(sheet, 1);
                if (colIndex.isEmpty()) continue;

                for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    try {
                        SanctionedEntity entity = parseOrgRow(row, colIndex);
                        if (entity != null) entities.add(entity);
                    } catch (Exception e) {
                        log.debug("Skipping malformed org row {}: {}", i, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse IL organizations XLSX: " + e.getMessage(),
                    ListSource.IL_WMD_TERROR,
                    e);
        }
        log.info("IL WMD/Terror: parsed {} organizations", entities.size());
        return entities;
    }

    private List<SanctionedEntity> parseIndividuals(byte[] data) throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
                Workbook workbook = WorkbookFactory.create(bais)) {

            for (Sheet sheet : workbook) {
                Map<String, Integer> colIndex = findHeaders(sheet, 1);
                if (colIndex.isEmpty()) continue;

                for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                    Row row = sheet.getRow(i);
                    if (row == null) continue;
                    try {
                        SanctionedEntity entity = parsePersonRow(row, colIndex);
                        if (entity != null) entities.add(entity);
                    } catch (Exception e) {
                        log.debug("Skipping malformed person row {}: {}", i, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse IL individuals XLSX: " + e.getMessage(),
                    ListSource.IL_WMD_TERROR,
                    e);
        }
        log.info("IL WMD/Terror: parsed {} individuals", entities.size());
        return entities;
    }

    /** Read headers from the given row index (Python crawler uses row index 1). */
    private Map<String, Integer> findHeaders(Sheet sheet, int headerRowIdx) {
        Row headerRow = sheet.getRow(headerRowIdx);
        if (headerRow == null) return Map.of();
        Map<String, Integer> colIndex = new HashMap<>();
        for (Cell cell : headerRow) {
            String header = cellToString(cell);
            if (header != null) {
                colIndex.put(slugify(header), cell.getColumnIndex());
            }
        }
        return colIndex;
    }

    private SanctionedEntity parseOrgRow(Row row, Map<String, Integer> colIndex) {
        String seqId = cellVal(row, colIndex, "internal_seq_id");
        String nameEn = cleanNa(cellVal(row, colIndex, "organization_name_english"));
        String nameHe = cleanNa(cellVal(row, colIndex, "organization_name_hebrew"));

        String name = nameEn != null ? nameEn : nameHe;
        if (name == null || name.isBlank()) return null;

        NameInfo primaryName =
                new NameInfo(name, null, null, null, null, NameType.PRIMARY, null, null);

        List<NameInfo> aliases = new ArrayList<>();
        if (nameHe != null && !nameHe.equals(name)) {
            aliases.add(new NameInfo(nameHe, null, null, null, null, NameType.AKA, null, null));
        }

        String id = seqId != null ? seqId : String.valueOf(name.hashCode());

        List<SanctionsProgram> programs = new ArrayList<>();
        String designation = cellVal(row, colIndex, "designation_type");
        programs.add(
                new SanctionsProgram(
                        designation != null ? designation : "IL Terror Designation",
                        null,
                        ListSource.IL_WMD_TERROR));

        return new SanctionedEntity(
                "il-org-" + id,
                EntityType.ENTITY,
                ListSource.IL_WMD_TERROR,
                primaryName,
                aliases,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                programs,
                null,
                Instant.now());
    }

    private SanctionedEntity parsePersonRow(Row row, Map<String, Integer> colIndex) {
        String seqId = cellVal(row, colIndex, "internal_seq_id");
        if (seqId == null || isNa(seqId)) return null;

        String nameEn = cleanNa(cellVal(row, colIndex, "name_of_individual_english"));
        String nameHe = cleanNa(cellVal(row, colIndex, "name_of_individual_hebrew"));
        String nameAr = cleanNa(cellVal(row, colIndex, "name_of_individual_arabic"));

        String name = nameEn != null ? nameEn : (nameHe != null ? nameHe : nameAr);
        if (name == null || name.isBlank()) return null;

        // Clean numbered names like "1: IBRAHIM 2: ALI"
        name = name.replaceAll("\\b\\d+\\s*:\\s*", "").strip().replaceAll("\\s+", " ");

        NameInfo primaryName =
                new NameInfo(name, null, null, null, null, NameType.PRIMARY, null, null);

        List<NameInfo> aliases = new ArrayList<>();
        if (nameHe != null && !nameHe.equals(name)) {
            aliases.add(new NameInfo(nameHe, null, null, null, null, NameType.AKA, null, null));
        }
        if (nameAr != null && !nameAr.equals(name) && !nameAr.equals(nameHe)) {
            aliases.add(new NameInfo(nameAr, null, null, null, null, NameType.AKA, null, null));
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        String dobStr = cellVal(row, colIndex, "d_o_b");
        if (dobStr != null) {
            LocalDate dob = parseDateSafe(dobStr);
            if (dob != null) datesOfBirth.add(dob);
        }

        List<String> nationalities = new ArrayList<>();
        String nat = cellVal(row, colIndex, "nationality_residency");
        if (nat != null && !nat.isBlank()) nationalities.add(nat.strip());

        String id = seqId;

        List<SanctionsProgram> programs = new ArrayList<>();
        String designation = cellVal(row, colIndex, "designation");
        programs.add(
                new SanctionsProgram(
                        designation != null ? designation : "IL Terror Designation",
                        null,
                        ListSource.IL_WMD_TERROR));

        return new SanctionedEntity(
                "il-ind-" + id,
                EntityType.INDIVIDUAL,
                ListSource.IL_WMD_TERROR,
                primaryName,
                aliases,
                List.of(),
                List.of(),
                nationalities,
                List.of(),
                datesOfBirth,
                List.of(),
                null,
                programs,
                null,
                Instant.now());
    }

    private static String cellVal(Row row, Map<String, Integer> colIndex, String key) {
        Integer idx = colIndex.get(key);
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
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDate d = cell.getLocalDateTimeCellValue().toLocalDate();
                    yield d.format(IL_DATE_FORMAT);
                }
                yield String.valueOf((long) cell.getNumericCellValue());
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
    }

    /** Slugify header: lowercase, replace non-alphanumeric with _, strip edges. */
    private static String slugify(String header) {
        return header.replace("(DD/MM/YYYY)", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private static boolean isNa(String val) {
        return val != null && NA_VALUE.matcher(val).matches();
    }

    private static String cleanNa(String val) {
        if (val == null) return null;
        val = val.replace("=\"---\"", "").strip();
        return val.isBlank() || isNa(val) ? null : val;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String cleaned = dateStr.strip();
        try {
            return LocalDate.parse(cleaned, IL_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(cleaned);
            } catch (DateTimeParseException e2) {
                return null;
            }
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
