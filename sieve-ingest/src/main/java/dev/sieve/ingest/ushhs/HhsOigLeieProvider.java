package dev.sieve.ingest.ushhs;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.Address;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import dev.sieve.ingest.CsvRows;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches the U.S. HHS Office of Inspector General List of Excluded Individuals/Entities (LEIE).
 *
 * <p>The OIG excludes people and businesses from Medicare, Medicaid and every other federal health
 * care program, and publishes the whole list as one CSV file each month. Columns: LASTNAME,
 * FIRSTNAME, MIDNAME, BUSNAME, GENERAL, SPECIALTY, UPIN, NPI, DOB, ADDRESS, CITY, STATE, ZIP,
 * EXCLTYPE, EXCLDATE, REINDATE, WAIVERDATE, WVRSTATE. A row with a business name is an entity; any
 * other row is a person.
 *
 * <p>The file has no record id, so each entry's id is {@code leie-} followed by a hash of its name,
 * date of birth, exclusion type and exclusion date, which do not change while the exclusion stands.
 * When two rows share those, the address is added to the hash; rows identical in every field are
 * kept once. Every entry is tagged {@link RiskTopic#DEBARMENT}, its program is the Social Security
 * Act section the exclusion rests on, and the NPI and UPIN numbers become identifiers.
 *
 * @see <a href="https://oig.hhs.gov/exclusions/">OIG exclusions</a>
 */
public final class HhsOigLeieProvider extends AbstractListProvider {

    /** The monthly file holding every current exclusion. */
    public static final String DEFAULT_URL =
            "https://oig.hhs.gov/exclusions/downloadables/UPDATED.csv";

    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern SECTION = Pattern.compile("^1128([ab])(\\d+)([a-z]?)$");

    /** What each exclusion authority means, from the OIG's list of exclusion authorities. */
    private static final Map<String, String> AUTHORITIES =
            Map.ofEntries(
                    Map.entry("1128a1", "Conviction of program-related crimes"),
                    Map.entry("1128a2", "Conviction relating to patient abuse or neglect"),
                    Map.entry("1128a3", "Felony conviction relating to health care fraud"),
                    Map.entry("1128a4", "Felony conviction relating to controlled substance"),
                    Map.entry("1128b1", "Misdemeanor conviction relating to health care fraud"),
                    Map.entry("1128b2", "Conviction relating to obstruction of an investigation"),
                    Map.entry("1128b3", "Misdemeanor conviction relating to controlled substance"),
                    Map.entry("1128b4", "License revocation, suspension or surrender"),
                    Map.entry(
                            "1128b5",
                            "Exclusion or suspension under a federal or state health care program"),
                    Map.entry(
                            "1128b6",
                            "Claims for excessive charges, unnecessary services or services that"
                                    + " fail to meet professionally recognized standards"),
                    Map.entry("1128b7", "Fraud, kickbacks and other prohibited activities"),
                    Map.entry("1128b8", "Entity controlled by a sanctioned individual"),
                    Map.entry(
                            "1128b8a",
                            "Entity controlled by a family or household member of an excluded"
                                    + " individual"),
                    Map.entry("1128b9", "Failure to disclose required information"),
                    Map.entry(
                            "1128b10",
                            "Failure to supply requested information on subcontractors and"
                                    + " suppliers"),
                    Map.entry("1128b11", "Failure to supply payment information"),
                    Map.entry("1128b12", "Failure to grant immediate access"),
                    Map.entry("1128b13", "Failure to take corrective action"),
                    Map.entry(
                            "1128b14",
                            "Default on health education loan or scholarship obligations"),
                    Map.entry("1128b15", "Individual controlling a sanctioned entity"),
                    Map.entry(
                            "1128b16",
                            "Making false statements or misrepresentation of material facts"));

    /** Creates a provider that reads the OIG's monthly file. */
    public HhsOigLeieProvider() {
        super(ListSource.US_HHS_LEIE, URI.create(DEFAULT_URL), "text/csv");
    }

    /**
     * Creates a provider with a custom source URI.
     *
     * @param sourceUri the URI to fetch the CSV file from
     */
    public HhsOigLeieProvider(URI sourceUri) {
        super(ListSource.US_HHS_LEIE, sourceUri, "text/csv");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the CSV file from
     * @param httpClient the HTTP client to use for requests
     */
    public HhsOigLeieProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.US_HHS_LEIE, sourceUri, "text/csv", httpClient, Duration.ofSeconds(180));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<List<String>> rows = CsvRows.parse(new String(responseBody, StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            columns.putIfAbsent(header.get(i).strip().toUpperCase(Locale.ROOT), i);
        }
        if (!columns.containsKey("LASTNAME") || !columns.containsKey("BUSNAME")) {
            throw new ListIngestionException(
                    "Unexpected LEIE header: " + String.join(",", header), ListSource.US_HHS_LEIE);
        }

        List<SanctionedEntity> entities = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<List<String>> seenRows = new HashSet<>();
        Instant now = Instant.now();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (!seenRows.add(row)) {
                continue;
            }
            Row r = new Row(row, columns);
            SanctionedEntity entity = toEntity(r, ids, now);
            if (entity != null) {
                entities.add(entity);
            }
        }
        return entities;
    }

    private SanctionedEntity toEntity(Row r, Set<String> ids, Instant now) {
        String business = r.get("BUSNAME");
        String last = r.get("LASTNAME");
        String first = r.get("FIRSTNAME");
        String middle = r.get("MIDNAME");
        EntityType type = business != null ? EntityType.ENTITY : EntityType.INDIVIDUAL;
        NameInfo name;
        if (type == EntityType.ENTITY) {
            name =
                    new NameInfo(
                            business,
                            null,
                            null,
                            null,
                            null,
                            NameType.PRIMARY,
                            NameStrength.STRONG,
                            null);
        } else {
            StringJoiner full = new StringJoiner(" ");
            for (String part : new String[] {first, middle, last}) {
                if (part != null) {
                    full.add(part);
                }
            }
            if (full.length() == 0) {
                return null;
            }
            name =
                    new NameInfo(
                            full.toString(),
                            first,
                            last,
                            middle,
                            null,
                            NameType.PRIMARY,
                            NameStrength.STRONG,
                            null);
        }

        LocalDate dob = date(r.get("DOB"));
        LocalDate excluded = date(r.get("EXCLDATE"));
        String code = r.get("EXCLTYPE");

        String street = r.get("ADDRESS");
        String city = r.get("CITY");
        String state = r.get("STATE");
        String zip = r.get("ZIP");
        List<Address> addresses = new ArrayList<>();
        if (street != null || city != null || state != null || zip != null) {
            StringJoiner full = new StringJoiner(", ");
            for (String part : new String[] {street, city, state, zip}) {
                if (part != null) {
                    full.add(part);
                }
            }
            addresses.add(new Address(street, city, state, zip, "US", full.toString()));
        }

        List<Identifier> identifiers = new ArrayList<>();
        String npi = r.get("NPI");
        if (npi != null && !npi.chars().allMatch(c -> c == '0')) {
            identifiers.add(
                    new Identifier(
                            IdentifierType.OTHER, npi, "US", "National Provider Identifier (NPI)"));
        }
        String upin = r.get("UPIN");
        if (upin != null) {
            identifiers.add(
                    new Identifier(
                            IdentifierType.OTHER,
                            upin,
                            "US",
                            "Unique Physician Identification Number (UPIN)"));
        }

        String key =
                String.join(
                        "|",
                        lower(business),
                        lower(last),
                        lower(first),
                        lower(middle),
                        String.valueOf(dob),
                        lower(code),
                        String.valueOf(excluded));
        String id = "leie-" + hash(key);
        if (!ids.add(id)) {
            id = "leie-" + hash(key + "|" + lower(street) + "|" + lower(city) + "|" + lower(zip));
            if (!ids.add(id)) {
                return null;
            }
        }

        String section = section(code);
        String meaning = code == null ? null : AUTHORITIES.get(code.toLowerCase(Locale.ROOT));
        List<SanctionsProgram> programs =
                section == null
                        ? List.of()
                        : List.of(
                                new SanctionsProgram(
                                        section,
                                        meaning == null ? section : section + ": " + meaning,
                                        ListSource.US_HHS_LEIE));

        return new SanctionedEntity(
                        id,
                        type,
                        ListSource.US_HHS_LEIE,
                        name,
                        List.of(),
                        addresses,
                        identifiers,
                        List.of(),
                        List.of(),
                        dob == null ? List.of() : List.of(dob),
                        List.of(),
                        remarks(r, excluded),
                        programs,
                        excluded == null ? null : excluded.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        now,
                        Set.of(RiskTopic.DEBARMENT),
                        List.of())
                .withListingReasons(meaning == null ? List.of() : List.of(meaning));
    }

    private static String remarks(Row r, LocalDate excluded) {
        StringJoiner joiner = new StringJoiner("\n");
        if (excluded != null) {
            joiner.add("Excluded from federal health care programs from " + excluded);
        }
        String general = r.get("GENERAL");
        String specialty = r.get("SPECIALTY");
        if (general != null) {
            joiner.add("Category: " + general);
        }
        if (specialty != null) {
            joiner.add("Specialty: " + specialty);
        }
        LocalDate waiver = date(r.get("WAIVERDATE"));
        if (waiver != null) {
            String state = r.get("WVRSTATE");
            joiner.add("Waiver granted " + waiver + (state == null ? "" : " for " + state));
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    /**
     * Writes an exclusion type the way the statute does, such as {@code 1128(b)(4)} for {@code
     * 1128b4}; other codes are returned as they are.
     *
     * @param code the EXCLTYPE value, may be {@code null}
     * @return the section, or {@code null} when there is no code
     */
    static String section(String code) {
        if (code == null) {
            return null;
        }
        Matcher m = SECTION.matcher(code.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return code;
        }
        String section = "1128(" + m.group(1) + ")(" + m.group(2) + ")";
        return m.group(3).isEmpty()
                ? section
                : section + "(" + m.group(3).toUpperCase(Locale.ROOT) + ")";
    }

    private static LocalDate date(String value) {
        if (value == null || value.length() != 8 || value.chars().allMatch(c -> c == '0')) {
            return null;
        }
        try {
            return LocalDate.parse(value, DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String hash(String key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    /** One CSV row read by column name; the LEIE writes "NULL" for some missing last names. */
    private record Row(List<String> fields, Map<String, Integer> columns) {
        String get(String column) {
            Integer index = columns.get(column);
            String value = index == null ? null : CsvRows.field(fields, index);
            return value == null || value.equalsIgnoreCase("NULL") ? null : value;
        }
    }
}
