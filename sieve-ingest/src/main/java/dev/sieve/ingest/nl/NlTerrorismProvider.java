package dev.sieve.ingest.nl;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.RiskTopic;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.ingest.AbstractListProvider;
import dev.sieve.ingest.StableIds;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fetches the Netherlands' national terrorism list: the people and organisations whose assets the
 * Minister of Foreign Affairs has frozen under the Sanctieregeling terrorisme 2007-II, published on
 * government.nl as an OpenDocument spreadsheet.
 *
 * <p>The sheet has a note, then a header row (Surname, First name(s), Alias, Date of Birth, Place
 * of Birth, Date of ministerial decision, Link official notification) and one row per listing.
 * Columns are found by their header. Ids are {@code nl-} followed by a hash of the surname, first
 * names and date of birth; the date of the ministerial decision is the listing date and the
 * Staatscourant notice goes in the remarks.
 *
 * @see <a href="https://www.government.nl/topics/terrorism/national-terrorism-list">National
 *     terrorism list</a>
 */
public final class NlTerrorismProvider extends AbstractListProvider {

    /** The English spreadsheet of the national terrorism list. */
    public static final String DEFAULT_URL =
            "https://www.government.nl/site/binaries/site-content/collections/documents/2016/01/15/"
                    + "national-terrorism-list/eng-terrorismelijst.ods";

    private static final List<DateTimeFormatter> DATES =
            List.of(
                    DateTimeFormatter.ISO_LOCAL_DATE,
                    DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                    DateTimeFormatter.ofPattern("d-M-yyyy"),
                    DateTimeFormatter.ofPattern("d/M/yyyy"));

    private static final SanctionsProgram PROGRAM =
            new SanctionsProgram(
                    "Sanctieregeling terrorisme 2007-II",
                    "Dutch national terrorism list (Sanctieregeling terrorisme 2007-II)",
                    ListSource.NL_TERRORISM);

    /** Creates a provider that reads the published spreadsheet. */
    public NlTerrorismProvider() {
        super(ListSource.NL_TERRORISM, URI.create(DEFAULT_URL), "*/*");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the spreadsheet from
     * @param httpClient the HTTP client to use for requests
     */
    public NlTerrorismProvider(URI sourceUri, HttpClient httpClient) {
        super(ListSource.NL_TERRORISM, sourceUri, "*/*", httpClient, Duration.ofSeconds(60));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        List<List<String>> rows;
        try {
            rows = OdsSheet.firstSheet(responseBody);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Failed to read the Dutch terrorism list: " + e.getMessage(),
                    ListSource.NL_TERRORISM,
                    e);
        }
        int headerAt = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() && rows.get(i).get(0).strip().equalsIgnoreCase("surname")) {
                headerAt = i;
                break;
            }
        }
        if (headerAt < 0) {
            throw new ListIngestionException(
                    "No header row in the Dutch terrorism list", ListSource.NL_TERRORISM);
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = rows.get(headerAt);
        for (int i = 0; i < header.size(); i++) {
            String label = header.get(i).toLowerCase(Locale.ROOT);
            for (String key :
                    List.of(
                            "surname",
                            "first name",
                            "alias",
                            "date of birth",
                            "place of birth",
                            "ministerial decision",
                            "notification")) {
                if (label.contains(key)) {
                    columns.putIfAbsent(key, i);
                }
            }
        }

        List<SanctionedEntity> entities = new ArrayList<>();
        StableIds ids = new StableIds("nl-");
        Instant now = Instant.now();
        for (List<String> row : rows.subList(headerAt + 1, rows.size())) {
            String surname = cell(row, columns, "surname");
            String first = cell(row, columns, "first name");
            if (surname == null && first == null) {
                continue;
            }
            String full = first == null ? surname : surname == null ? first : first + " " + surname;
            LocalDate dob = date(cell(row, columns, "date of birth"));
            LocalDate decided = date(cell(row, columns, "ministerial decision"));
            String alias = cell(row, columns, "alias");
            List<NameInfo> aliases = new ArrayList<>();
            if (alias != null) {
                for (String a : alias.split("[;\\n]")) {
                    if (!a.isBlank()) {
                        aliases.add(
                                new NameInfo(
                                        a.strip(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        NameType.AKA,
                                        null,
                                        null));
                    }
                }
            }
            String place = cell(row, columns, "place of birth");
            String notice = cell(row, columns, "notification");
            boolean person = first != null;
            entities.add(
                    new SanctionedEntity(
                            ids.next(surname, first, String.valueOf(dob)),
                            person ? EntityType.INDIVIDUAL : EntityType.ORGANIZATION,
                            ListSource.NL_TERRORISM,
                            new NameInfo(
                                    full,
                                    first,
                                    person ? surname : null,
                                    null,
                                    null,
                                    NameType.PRIMARY,
                                    NameStrength.STRONG,
                                    null),
                            aliases,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            dob == null ? List.of() : List.of(dob),
                            place == null ? List.of() : List.of(place),
                            notice == null ? null : "Official notification: " + notice,
                            List.of(PROGRAM),
                            decided == null
                                    ? null
                                    : decided.atStartOfDay(ZoneOffset.UTC).toInstant(),
                            now,
                            Set.of(RiskTopic.SANCTION),
                            List.of()));
        }
        return entities;
    }

    private static String cell(List<String> row, Map<String, Integer> columns, String key) {
        Integer index = columns.get(key);
        if (index == null || index >= row.size()) {
            return null;
        }
        String value = row.get(index).strip();
        return value.isEmpty() ? null : value;
    }

    /**
     * Reads a date written as an ISO date or as day, month and year.
     *
     * @param value the cell text, may be {@code null}
     * @return the date, or {@code null} when it cannot be read
     */
    static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        for (DateTimeFormatter format : DATES) {
            try {
                return LocalDate.parse(value.strip(), format);
            } catch (DateTimeParseException e) {
                // try the next layout
            }
        }
        return null;
    }
}
