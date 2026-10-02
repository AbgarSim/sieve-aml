package dev.sieve.ingest.be;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches and parses the Belgian FOD/SPF Finance sanctions list.
 *
 * <p>Includes the national terrorism list. Published as semicolon-delimited CSV. Columns: Lastname,
 * Firstname, Middlename, Wholename, Gender, Birth date, Birth place, Birth country, Function,
 * Number, Remark, Embargos, type, Regulation, Publication date, Links.
 *
 * @see <a
 *     href="https://financien.belgium.be/nl/thesaurie/financiele-sancties/terrorisme-en-terrorismefinanciering">
 *     Belgian FOD Sanctions</a>
 */
public final class BeFodProvider extends AbstractListProvider {

    private static final String DEFAULT_URL =
            "https://sifi.minfin.fgov.be/public/api/consolidated-list";
    private static final DateTimeFormatter BE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yy");

    // CSV column indices (0-based)
    private static final int COL_LASTNAME = 0;
    private static final int COL_FIRSTNAME = 1;
    private static final int COL_WHOLENAME = 3;
    private static final int COL_BIRTHDATE = 5;
    private static final int COL_BIRTHPLACE = 6;
    private static final int COL_BIRTHCOUNTRY = 7;
    private static final int COL_EMBARGOS = 11;
    private static final int COL_TYPE = 12;
    private static final int COL_REGULATION = 13;
    private static final int MIN_COLUMNS = 14;

    public BeFodProvider() {
        super(ListSource.BE_FOD, URI.create(DEFAULT_URL), "*/*");
    }

    public BeFodProvider(URI sourceUri) {
        super(ListSource.BE_FOD, sourceUri, "*/*");
    }

    public BeFodProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.BE_FOD, sourceUri, "*/*", httpClient, Duration.ofSeconds(120));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<SanctionedEntity> entities = new ArrayList<>();

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new ByteArrayInputStream(responseBody), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine != null && headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }
            if (headerLine == null || !headerLine.contains("Lastname")) {
                throw new ListIngestionException(
                        "Unexpected CSV header in Belgian FOD response", ListSource.BE_FOD);
            }

            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    SanctionedEntity entity = parseCsvRow(line, idx);
                    if (entity != null) entities.add(entity);
                } catch (Exception e) {
                    log.debug("Skipping malformed BE FOD row {}: {}", idx, e.getMessage());
                }
                idx++;
            }
        } catch (ListIngestionException e) {
            throw e;
        } catch (Exception e) {
            throw new ListIngestionException(
                    "Failed to parse Belgian FOD CSV: " + e.getMessage(), ListSource.BE_FOD, e);
        }
        return entities;
    }

    private SanctionedEntity parseCsvRow(String line, int idx) {
        String[] fields = parseCsvLine(line);
        if (fields.length < MIN_COLUMNS) return null;

        String lastName = clean(fields[COL_LASTNAME]);
        String firstName = clean(fields[COL_FIRSTNAME]);
        String wholeName = clean(fields[COL_WHOLENAME]);

        String fullName = wholeName;
        if (fullName == null || fullName.isEmpty()) {
            if (lastName == null || lastName.isEmpty()) return null;
            fullName =
                    firstName != null && !firstName.isEmpty()
                            ? firstName + " " + lastName
                            : lastName;
        }

        // Determine entity type: "P" = person, "E" = entity
        String typeCode = clean(fields[COL_TYPE]);
        EntityType entityType =
                "E".equalsIgnoreCase(typeCode) ? EntityType.ENTITY : EntityType.INDIVIDUAL;

        NameInfo primaryName =
                new NameInfo(
                        fullName, firstName, lastName, null, null, NameType.PRIMARY, null, null);

        List<LocalDate> datesOfBirth = new ArrayList<>();
        String dobStr = clean(fields[COL_BIRTHDATE]);
        if (dobStr != null) {
            LocalDate dob = parseDateSafe(dobStr);
            if (dob != null) datesOfBirth.add(dob);
        }

        List<String> placesOfBirth = new ArrayList<>();
        String pob = clean(fields[COL_BIRTHPLACE]);
        if (pob != null) placesOfBirth.add(pob);

        List<String> nationalities = new ArrayList<>();
        String country = clean(fields[COL_BIRTHCOUNTRY]);
        if (country != null) nationalities.add(country);

        List<SanctionsProgram> programs = new ArrayList<>();
        String embargos = clean(fields[COL_EMBARGOS]);
        String regulation = clean(fields[COL_REGULATION]);
        String progName =
                embargos != null ? embargos : (regulation != null ? regulation : "BE FOD");
        programs.add(new SanctionsProgram(progName, null, ListSource.BE_FOD));

        return new SanctionedEntity(
                "be-" + idx,
                entityType,
                ListSource.BE_FOD,
                primaryName,
                List.of(),
                List.of(),
                List.of(),
                nationalities,
                List.of(),
                datesOfBirth,
                placesOfBirth,
                null,
                programs,
                null,
                Instant.now());
    }

    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ';') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static String clean(String val) {
        if (val == null) return null;
        String s = val.strip();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate parseDateSafe(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        try {
            return LocalDate.parse(dateStr.strip(), BE_DATE_FORMAT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(dateStr.strip());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
