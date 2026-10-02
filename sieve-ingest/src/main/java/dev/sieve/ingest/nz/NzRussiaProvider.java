package dev.sieve.ingest.nz;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.util.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Fetches and parses the New Zealand Russia Sanctions Register.
 *
 * <p>Published as XLSX by the New Zealand Ministry of Foreign Affairs and Trade. Separate from
 * New Zealand's terrorism list. Typically contains ~1,853 entities.
 *
 * @see <a href="https://www.mfat.govt.nz/en/countries-and-regions/europe/ukraine/russian-invasion-of-ukraine/sanctions/">
 *     NZ Russia Sanctions Register</a>
 */
public final class NzRussiaProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://www.mfat.govt.nz/assets/Countries-and-Regions/Europe/Ukraine/Russia-Sanctions-Register.xlsx";
    private static final DateTimeFormatter NZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public NzRussiaProvider() {
        super(ListSource.NZ_RUSSIA, URI.create(DEFAULT_URL),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    public NzRussiaProvider(URI sourceUri) {
        super(ListSource.NZ_RUSSIA, sourceUri,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    public NzRussiaProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.NZ_RUSSIA, sourceUri,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                httpClient, Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();

        try (ByteArrayInputStream bais = new ByteArrayInputStream(responseBody)) {
            IOUtils.setByteArrayMaxOverride(300_000_000);
            Workbook workbook = WorkbookFactory.create(bais);

            // Scan all sheets for a header row containing "Unique Identifier" and "DOB"
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                if (sheet == null) continue;

                Map<String, Integer> colIndex = null;
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    if (colIndex == null) {
                        // Check if this row is the header
                        Map<String, Integer> candidate = buildColIndex(row);
                        if (candidate.containsKey("unique identifier")
                                && candidate.containsKey("dob")) {
                            colIndex = candidate;
                            continue;
                        }
                    } else {
                        try {
                            SanctionedEntity entity = parseRow(row, colIndex, entities.size());
                            if (entity != null) entities.add(entity);
                        } catch (Exception e) {
                            log.debug("Skipping malformed row {} in NZ Russia XLSX: {}",
                                    r, e.getMessage());
                        }
                    }
                }
            }
            workbook.close();

        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse NZ Russia XLSX: " + e.getMessage(), ListSource.NZ_RUSSIA, e);
        }
        return entities;
    }

    private static Map<String, Integer> buildColIndex(Row row) {
        Map<String, Integer> colIndex = new HashMap<>();
        for (Cell cell : row) {
            String header = cellToString(cell);
            if (header != null) {
                colIndex.put(header.toLowerCase().strip(), cell.getColumnIndex());
            }
        }
        return colIndex;
    }

    private SanctionedEntity parseRow(Row row, Map<String, Integer> colIndex, int idx) {
        String typeStr = cellVal(row, colIndex, "type");
        // Skip non-entity types like "Asset", "Total"
        if (typeStr == null) return null;
        String typeLower = typeStr.toLowerCase();
        if (typeLower.contains("asset") || typeLower.contains("total")) return null;

        EntityType entityType;
        if (typeLower.contains("entity") || typeLower.contains("bank")) {
            entityType = EntityType.ENTITY;
        } else {
            entityType = EntityType.INDIVIDUAL;
        }

        String firstName = cellVal(row, colIndex, "first name");
        String middleName = cellVal(row, colIndex, "middle name(s)");
        String lastName = cellVal(row, colIndex, "last name");

        // Build full name
        StringBuilder nameBuilder = new StringBuilder();
        if (firstName != null) nameBuilder.append(firstName);
        if (middleName != null) {
            if (!nameBuilder.isEmpty()) nameBuilder.append(" ");
            nameBuilder.append(middleName);
        }
        if (lastName != null) {
            if (!nameBuilder.isEmpty()) nameBuilder.append(" ");
            nameBuilder.append(lastName);
        }
        String fullName = nameBuilder.toString().strip();
        if (fullName.isEmpty()) return null;

        String uniqueId = cellVal(row, colIndex, "unique identifier");
        if (uniqueId == null) uniqueId = String.valueOf(idx);

        NameInfo primaryName = new NameInfo(
                fullName, firstName, lastName, null, null, NameType.PRIMARY, null, null);

        List<NameInfo> aliases = new ArrayList<>();
        String aliasStr = cellVal(row, colIndex, "alias/alternate spellings");
        if (aliasStr != null && !aliasStr.isBlank()) {
            for (String a : aliasStr.split("[;,]")) {
                String trimmed = a.strip();
                if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(fullName)) {
                    aliases.add(new NameInfo(
                            trimmed, null, null, null, null, NameType.AKA, null, null));
                }
            }
        }

        List<LocalDate> datesOfBirth = new ArrayList<>();
        String dobStr = cellVal(row, colIndex, "dob");
        if (dobStr != null) {
            LocalDate dob = parseDateSafe(dobStr);
            if (dob != null) datesOfBirth.add(dob);
        }

        List<String> nationalities = new ArrayList<>();
        for (String key : List.of("citizenship", "citizenship 2", "citizenship 3")) {
            String cit = cellVal(row, colIndex, key);
            if (cit != null && !cit.isBlank()) nationalities.add(cit.strip());
        }

        List<String> placesOfBirth = new ArrayList<>();
        String pob = cellVal(row, colIndex, "place of birth");
        if (pob != null && !pob.isBlank()) placesOfBirth.add(pob.strip());

        List<SanctionsProgram> programs = List.of(
                new SanctionsProgram("NZ-RSA2022", "Russia Sanctions", ListSource.NZ_RUSSIA));

        return new SanctionedEntity(
                "nz-" + uniqueId, entityType, ListSource.NZ_RUSSIA,
                primaryName, aliases, List.of(), List.of(),
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
            try { return LocalDate.parse(dateStr.strip(), NZ_DATE_FORMAT); }
            catch (DateTimeParseException e2) { return null; }
        }
    }
}
