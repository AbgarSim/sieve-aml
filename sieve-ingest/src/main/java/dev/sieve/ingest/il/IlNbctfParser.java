package dev.sieve.ingest.il;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.ingest.StableIds;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Parses the Excel exports of the Israeli National Bureau for Counter Terror Financing (NBCTF), as
 * its site {@code matal.mod.gov.il} has served them since October 2026: one workbook for the
 * terrorist organisations and one for the terror operatives, each a single sheet with a header row.
 *
 * <p>Both carry the names in Hebrew, Arabic and English, a status ({@code Active} or {@code
 * Cancelled}) and the dates of the temporary and permanent designation. Cancelled designations are
 * left out, since they are no longer in force. Organisations carry the designation number, which
 * becomes the id ({@code il-org-408}); operatives carry none, so their ids are a hash of the
 * English name and date of birth ({@code il-ind-<hash>}). A cell holding several values separates
 * them with a line of dashes.
 */
final class IlNbctfParser {

    static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "IL-NBCTF",
                    "Israel designation under the Counter-Terrorism Law, 2016",
                    ListSource.IL_WMD_TERROR);

    private static final Pattern SEPARATOR = Pattern.compile("\\n-{3,}\\n|\\n");
    private static final Pattern ID_NUMBER =
            Pattern.compile("(?i)Identification Number:\\s*+(?!Unknown\\s*$)(.+)");
    private static final Pattern HEBREW = Pattern.compile("\\p{IsHebrew}");
    private static final Pattern ARABIC = Pattern.compile("\\p{IsArabic}");

    private IlNbctfParser() {}

    /**
     * Parses the organisations export.
     *
     * @param xlsx the workbook
     * @return the organisations in force
     * @throws ListIngestionException if the workbook cannot be read or lacks the expected columns
     */
    static List<SanctionedEntity> organisations(byte[] xlsx) throws ListIngestionException {
        List<SanctionedEntity> out = new ArrayList<>();
        for (Map<String, String> row : rows(xlsx, "Designation Number")) {
            if (cancelled(row)) {
                continue;
            }
            String number = row.get("Designation Number");
            Names names =
                    names(
                            row,
                            first(row, "Name en", "Organization Name", "Name he"),
                            "AlternativeNames en",
                            "AlternativeNames he",
                            "AlternativeNames ar");
            if (names == null || number.isEmpty()) {
                continue;
            }
            List<Identifier> ids = new ArrayList<>();
            String corporationId = row.getOrDefault("Corporation ID", "");
            if (!corporationId.isEmpty()) {
                ids.add(
                        new Identifier(
                                IdentifierType.REGISTRATION_NUMBER, corporationId, null, null));
            }
            out.add(
                    new SanctionedEntity(
                            "il-org-" + number,
                            EntityType.ENTITY,
                            ListSource.IL_WMD_TERROR,
                            names.primary(),
                            names.aliases(),
                            List.of(),
                            ids,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            remarks(
                                    row,
                                    "Designation number " + number,
                                    "Formation Location",
                                    "Linked Organizations"),
                            List.of(PROGRAM),
                            listed(row),
                            Instant.now()));
        }
        return out;
    }

    /**
     * Parses the operatives export.
     *
     * @param xlsx the workbook
     * @return the operatives in force
     * @throws ListIngestionException if the workbook cannot be read or lacks the expected columns
     */
    static List<SanctionedEntity> operatives(byte[] xlsx) throws ListIngestionException {
        StableIds stableIds = new StableIds("il-ind-");
        List<SanctionedEntity> out = new ArrayList<>();
        for (Map<String, String> row : rows(xlsx, "Full Name")) {
            if (cancelled(row)) {
                continue;
            }
            Names names = names(row, first(row, "Name en", "Full Name", "Name he"));
            if (names == null) {
                continue;
            }
            List<LocalDate> births = new ArrayList<>();
            for (String value : split(row.getOrDefault("Dates of Birth", ""))) {
                LocalDate date = date(value);
                if (date != null) {
                    births.add(date);
                }
            }
            List<Identifier> ids = new ArrayList<>();
            for (String line : row.getOrDefault("Identification Documents", "").split("\\n")) {
                var m = ID_NUMBER.matcher(line.strip());
                if (m.matches()) {
                    ids.add(new Identifier(IdentifierType.OTHER, m.group(1).strip(), null, null));
                }
            }
            String id =
                    stableIds.next(
                            names.primary().fullName(),
                            births.isEmpty() ? "" : births.get(0).toString());
            out.add(
                    new SanctionedEntity(
                            id,
                            EntityType.INDIVIDUAL,
                            ListSource.IL_WMD_TERROR,
                            names.primary(),
                            names.aliases(),
                            List.of(),
                            ids,
                            List.of(),
                            List.of(),
                            births,
                            List.of(),
                            remarks(row, null, "Related Organizations"),
                            List.of(PROGRAM),
                            listed(row),
                            Instant.now()));
        }
        return out;
    }

    private record Names(NameInfo primary, List<NameInfo> aliases) {}

    private static Names names(Map<String, String> row, String primary, String... aliasColumns) {
        if (primary.isEmpty()) {
            return null;
        }
        Set<String> aliases = new LinkedHashSet<>();
        for (String column : List.of("Name en", "Name he", "Name ar", "Full Name")) {
            aliases.add(row.getOrDefault(column, ""));
        }
        for (String column : aliasColumns) {
            aliases.addAll(split(row.getOrDefault(column, "")));
        }
        aliases.remove(primary);
        aliases.remove("");
        List<NameInfo> infos = new ArrayList<>();
        for (String alias : aliases) {
            infos.add(name(alias, NameType.AKA));
        }
        return new Names(name(primary, NameType.PRIMARY), infos);
    }

    private static NameInfo name(String name, NameType type) {
        ScriptType script =
                HEBREW.matcher(name).find()
                        ? ScriptType.OTHER
                        : ARABIC.matcher(name).find() ? ScriptType.ARABIC : ScriptType.LATIN;
        return new NameInfo(name, null, null, null, null, type, NameStrength.STRONG, script);
    }

    private static String remarks(Map<String, String> row, String lead, String... columns) {
        List<String> parts = new ArrayList<>();
        if (lead != null) {
            parts.add(lead);
        }
        String designator = row.getOrDefault("Local Designator", "");
        if (!designator.isEmpty()) {
            parts.add("Designated by " + designator);
        }
        for (String column : columns) {
            String value =
                    String.join(", ", split(row.getOrDefault(column, "").replace("Name: ", "")));
            if (!value.isEmpty()) {
                parts.add(column + ": " + value);
            }
        }
        String justification = row.getOrDefault("Justifications", "");
        if (!justification.isEmpty() && !HEBREW.matcher(justification).find()) {
            parts.add(justification.replaceAll("\\s+", " "));
        }
        return parts.isEmpty() ? null : String.join(". ", parts);
    }

    private static Instant listed(Map<String, String> row) {
        LocalDate date =
                date(first(row, "Temporary Designation Date", "Permanent Designation Date"));
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static boolean cancelled(Map<String, String> row) {
        return row.getOrDefault("Status", "").equalsIgnoreCase("Cancelled");
    }

    private static String first(Map<String, String> row, String... columns) {
        for (String column : columns) {
            String value = row.getOrDefault(column, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static List<String> split(String cell) {
        List<String> out = new ArrayList<>();
        for (String part : SEPARATOR.split(cell)) {
            String v = part.strip();
            if (!v.isEmpty() && !v.matches("-+")) {
                out.add(v);
            }
        }
        return out;
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date =
                    LocalDate.parse(
                            value.strip().substring(0, Math.min(10, value.strip().length())));
            // The source writes 9999-09-09 where it has no date.
            return date.getYear() >= 9000 ? null : date;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).strip();
    }

    /** Reads the first sheet into one map per row, keyed by the header row's names. */
    private static List<Map<String, String>> rows(byte[] xlsx, String requiredColumn)
            throws ListIngestionException {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            Map<Integer, String> names = new HashMap<>();
            if (header != null) {
                for (Cell cell : header) {
                    String name = formatter.formatCellValue(cell).strip();
                    if (!name.isEmpty()) {
                        names.put(cell.getColumnIndex(), name);
                    }
                }
            }
            if (!names.containsValue("Name en") || !names.containsValue(requiredColumn)) {
                throw new ListIngestionException(
                        "IL NBCTF export lacks the \"Name en\" or \""
                                + requiredColumn
                                + "\" column; the export layout may have changed",
                        ListSource.IL_WMD_TERROR);
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = sheet.getFirstRowNum() + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new HashMap<>();
                for (Map.Entry<Integer, String> column : names.entrySet()) {
                    Cell cell = row.getCell(column.getKey());
                    values.put(column.getValue(), text(cell, formatter));
                }
                rows.add(values);
            }
            return rows;
        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to read IL NBCTF export: " + e.getMessage(),
                    ListSource.IL_WMD_TERROR,
                    e);
        }
    }
}
