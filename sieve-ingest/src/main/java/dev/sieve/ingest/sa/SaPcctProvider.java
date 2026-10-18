package dev.sieve.ingest.sa;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.VesselDetails;
import dev.sieve.ingest.AbstractListProvider;
import dev.sieve.ingest.StableIds;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
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
 * Fetches Saudi Arabia's national terrorism designations list, published by the Presidency of State
 * Security as an Excel workbook.
 *
 * <p>The workbook has three sheets: Individuals, Entities and Vessels. Columns are found by their
 * header. Each row becomes one entity with its aliases, the organisation it is linked to as its
 * program, its date of designation and, by sheet, dates of birth, nationalities, documents and
 * addresses (people), registration numbers and addresses (entities), or the IMO number and flag
 * (vessels). Ids are {@code sa-pcct-} followed by a hash of the sheet and name, because the row
 * numbers are positions that shift when a row is removed.
 *
 * @see <a href="https://pctc.pss.gov.sa/">Presidency of State Security</a>
 */
public final class SaPcctProvider extends AbstractListProvider {

    /** The English workbook of the national designations list. */
    public static final String DEFAULT_URL =
            "https://pctc.pss.gov.sa/portal/pcct/slist/js/slistEN.xlsx";

    private static final Pattern DATE =
            Pattern.compile("(\\d{4})(?:[/-](\\d{1,2})[/-](\\d{1,2}))?");
    private static final Pattern IMO = Pattern.compile("(\\d{7})");
    private static final DataFormatter FORMAT = new DataFormatter(Locale.ROOT);

    /** Creates a provider that reads the published workbook. */
    public SaPcctProvider() {
        super(ListSource.SA_PCCT, URI.create(DEFAULT_URL), "*/*");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the workbook from
     * @param httpClient the HTTP client to use for requests
     */
    public SaPcctProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.SA_PCCT, sourceUri, "*/*", httpClient, Duration.ofSeconds(60));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();
        StableIds ids = new StableIds("sa-pcct-");
        Instant now = Instant.now();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(responseBody))) {
            for (Sheet sheet : workbook) {
                String kind = sheet.getSheetName().strip().toLowerCase(Locale.ROOT);
                EntityType type =
                        kind.startsWith("individual")
                                ? EntityType.INDIVIDUAL
                                : kind.startsWith("entit")
                                        ? EntityType.ORGANIZATION
                                        : kind.startsWith("vessel") ? EntityType.VESSEL : null;
                if (type == null) {
                    log.warn("Skipping unknown SA PCCT sheet [sheet={}]", sheet.getSheetName());
                    continue;
                }
                Map<String, Integer> columns = null;
                for (Row row : sheet) {
                    if (columns == null) {
                        columns = header(row);
                        continue;
                    }
                    SanctionedEntity entity = toEntity(new Cells(row, columns), type, ids, now);
                    if (entity != null) {
                        entities.add(entity);
                    }
                }
            }
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to read the Saudi designations workbook: " + e.getMessage(),
                    ListSource.SA_PCCT,
                    e);
        }
        if (entities.isEmpty()) {
            throw new ListIngestionException(
                    "The Saudi designations workbook has no rows", ListSource.SA_PCCT);
        }
        return entities;
    }

    private static Map<String, Integer> header(Row row) {
        Map<String, Integer> columns = new HashMap<>();
        for (Cell cell : row) {
            String label = text(cell);
            if (label != null) {
                columns.putIfAbsent(
                        label.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT),
                        cell.getColumnIndex());
            }
        }
        return columns;
    }

    private static SanctionedEntity toEntity(Cells c, EntityType type, StableIds ids, Instant now) {
        String name =
                c.get(
                        type == EntityType.INDIVIDUAL
                                ? "full name"
                                : type == EntityType.VESSEL ? "vessel name" : "entities name");
        if (name == null) {
            return null;
        }
        String given = null;
        String family = null;
        int comma = name.indexOf(", ");
        if (type == EntityType.INDIVIDUAL && comma > 0) {
            family = name.substring(0, comma).strip();
            given = name.substring(comma + 2).strip();
        }
        NameInfo primary =
                new NameInfo(
                        name,
                        given,
                        family,
                        null,
                        null,
                        NameType.PRIMARY,
                        NameStrength.STRONG,
                        null);

        Set<String> aliasNames = new LinkedHashSet<>(split(c.get("aliases")));
        aliasNames.removeIf(a -> a.equalsIgnoreCase(name));
        List<NameInfo> aliases = new ArrayList<>();
        for (String alias : aliasNames) {
            aliases.add(new NameInfo(alias, null, null, null, null, NameType.AKA, null, null));
        }

        String link = c.get("link of terrorism org.");
        List<SanctionsProgram> programs =
                List.of(
                        new SanctionsProgram(
                                link == null ? "SA PCCT" : link,
                                link == null
                                        ? "Saudi national terrorism designations"
                                        : "Saudi national terrorism designations: " + link,
                                ListSource.SA_PCCT));

        List<Address> addresses = new ArrayList<>();
        for (String line : split(c.get("address"))) {
            addresses.add(new Address(null, null, null, null, null, line));
        }

        List<Identifier> identifiers = new ArrayList<>();
        List<LocalDate> dobs = new ArrayList<>();
        List<String> places = new ArrayList<>();
        List<String> nationalities = new ArrayList<>();
        Gender gender = null;
        VesselDetails vessel = null;
        StringJoiner remarks = new StringJoiner("\n");
        if (link != null) {
            remarks.add("Linked to: " + link);
        }
        if (type == EntityType.INDIVIDUAL) {
            for (String d : split(c.get("dob"))) {
                LocalDate dob = date(d);
                if (dob != null) {
                    dobs.add(dob);
                }
            }
            String pob = c.get("pob");
            String cob = c.get("cob");
            if (pob != null || cob != null) {
                places.add(pob == null ? cob : cob == null ? pob : pob + ", " + cob);
            }
            nationalities.addAll(split(c.get("nationality")));
            String g = c.get("gender");
            if (g != null) {
                gender =
                        g.toLowerCase(Locale.ROOT).startsWith("f")
                                ? Gender.FEMALE
                                : g.toLowerCase(Locale.ROOT).startsWith("m") ? Gender.MALE : null;
            }
            String docType = c.get("document type");
            IdentifierType idType =
                    docType != null && docType.toLowerCase(Locale.ROOT).contains("passport")
                            ? IdentifierType.PASSPORT
                            : IdentifierType.NATIONAL_ID;
            for (String number : split(c.get("document no."))) {
                identifiers.add(new Identifier(idType, number, c.get("issuing country"), docType));
            }
        } else if (type == EntityType.ORGANIZATION) {
            for (String number : split(c.get("registration number"))) {
                identifiers.add(
                        new Identifier(IdentifierType.REGISTRATION_NUMBER, number, null, null));
            }
        } else {
            String imo = c.get("imo no.");
            if (imo != null) {
                Matcher m = IMO.matcher(imo);
                if (m.find()) {
                    identifiers.add(
                            new Identifier(IdentifierType.IMO_NUMBER, m.group(1), null, null));
                }
            }
            String flag = c.get("vessel flag");
            if (flag != null) {
                vessel =
                        new VesselDetails(
                                flag.replaceFirst("(?i)\\s*flag$", "").strip(),
                                null,
                                null,
                                null,
                                null);
            }
        }
        String owner = c.get("owner name");
        if (owner != null) {
            String info = c.get("owner information");
            remarks.add("Owner: " + owner + (info == null ? "" : " (" + info + ")"));
        }
        String extra =
                c.get(
                        type == EntityType.INDIVIDUAL
                                ? "additional information"
                                : "additional info.");
        if (extra != null) {
            remarks.add(extra);
        }
        LocalDate designated = date(c.get("designated date"));

        SanctionedEntity entity =
                new SanctionedEntity(
                        ids.next(type.name(), name),
                        type,
                        ListSource.SA_PCCT,
                        primary,
                        aliases,
                        addresses,
                        identifiers,
                        nationalities,
                        List.of(),
                        dobs,
                        places,
                        remarks.length() == 0 ? null : remarks.toString(),
                        programs,
                        designated == null
                                ? null
                                : designated.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        now,
                        Set.of(RiskTopic.SANCTION),
                        List.of());
        if (gender != null) {
            entity = entity.withGender(gender);
        }
        if (vessel != null) {
            entity = entity.withVessel(vessel);
        }
        return entity;
    }

    /**
     * Splits a cell holding several values separated by semicolons or line breaks, dropping the
     * stray full stops and "a.k.a." markers the sheet carries.
     *
     * @param value the cell text, may be {@code null}
     * @return the values in order, without blanks or repeats
     */
    static List<String> split(String value) {
        if (value == null) {
            return List.of();
        }
        Set<String> parts = new LinkedHashSet<>();
        for (String part : value.split("[;\\n]")) {
            String p =
                    part.strip()
                            .replaceFirst("(?i)^a\\.k\\.a\\.\\s*", "")
                            .replaceAll("^[.\\s]+|[.,\\s]+$", "")
                            .replaceAll("^\"|\"$", "")
                            .strip();
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return List.copyOf(parts);
    }

    /**
     * Reads a date written as year/month/day or as a year alone, which is taken as 1 January.
     *
     * @param value the cell text, may be {@code null}
     * @return the date, or {@code null} when none can be read
     */
    static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = DATE.matcher(value.strip());
        if (!m.find()) {
            return null;
        }
        try {
            int year = Integer.parseInt(m.group(1));
            return m.group(2) == null
                    ? LocalDate.of(year, 1, 1)
                    : LocalDate.of(
                            year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        String value;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            value = cell.getLocalDateTimeCellValue().toLocalDate().toString();
        } else {
            value = FORMAT.formatCellValue(cell);
        }
        value = value.replace('\t', ' ').strip();
        return value.isEmpty() ? null : value;
    }

    /** One sheet row read by header label. */
    private record Cells(Row row, Map<String, Integer> columns) {
        String get(String label) {
            Integer index = columns.get(label);
            return index == null ? null : text(row.getCell(index));
        }
    }
}
