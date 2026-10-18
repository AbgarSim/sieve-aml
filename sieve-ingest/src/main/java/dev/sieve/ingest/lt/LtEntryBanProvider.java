package dev.sieve.ingest.lt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Gender;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches Lithuania's published list of foreigners banned from entering the country, kept by the
 * Migration Department.
 *
 * <p>Most entries are bans under Article 133(4) of the Law on the Legal Status of Aliens, the
 * provision added in 2017 for people involved in human rights abuses, money laundering or
 * corruption (Lithuania's Magnitsky law); others cite a threat to state security. Each entry gives
 * a name, date of birth, gender, citizenships, the date the ban runs until and its legal ground,
 * which becomes the listing reason. Ids are {@code lt-} followed by a hash of the name and date of
 * birth.
 *
 * @see <a href="https://www.migracija.lt/">Migration Department</a>
 */
public final class LtEntryBanProvider extends AbstractListProvider {

    /** The search endpoint behind the Migration Department's published list. */
    public static final String DEFAULT_URL =
            "https://www.migracija.lt/external/nam/search?pageNo=0&pageSize=500&language=en";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Creates a provider that reads the published list. */
    public LtEntryBanProvider() {
        super(ListSource.LT_ENTRY_BAN, URI.create(DEFAULT_URL), "application/json");
    }

    /**
     * Creates a provider with a custom source URI and HTTP client (for testing).
     *
     * @param sourceUri the URI to fetch the list from
     * @param httpClient the HTTP client to use for requests
     */
    public LtEntryBanProvider(URI sourceUri, HttpClient httpClient) {
        super(
                ListSource.LT_ENTRY_BAN,
                sourceUri,
                "application/json",
                httpClient,
                Duration.ofSeconds(60));
    }

    @Override
    protected List<SanctionedEntity> parseResponse(byte[] responseBody)
            throws ListIngestionException {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (IOException e) {
            throw new ListIngestionException(
                    "Failed to parse the Lithuanian entry ban list: " + e.getMessage(),
                    ListSource.LT_ENTRY_BAN,
                    e);
        }
        JsonNode list = root.path("list");
        if (!list.isArray()) {
            throw new ListIngestionException(
                    "Lithuanian entry ban response has no list", ListSource.LT_ENTRY_BAN);
        }
        int found = root.path("numFound").asInt(list.size());
        if (found > list.size()) {
            log.warn(
                    "Lithuanian entry ban list is longer than one page [numFound={}, read={}]",
                    found,
                    list.size());
        }
        List<SanctionedEntity> entities = new ArrayList<>();
        StableIds ids = new StableIds("lt-");
        Instant now = Instant.now();
        for (JsonNode row : list) {
            String given = text(row, "vardas");
            String family = text(row, "pavarde");
            if (given == null && family == null) {
                continue;
            }
            String full = given == null ? family : family == null ? given : given + " " + family;
            LocalDate dob = date(text(row, "gimimoData"));
            LocalDate until = date(text(row, "uzdraustaIki"));
            String reason = text(row, "priezastis");

            Set<String> citizenships = new LinkedHashSet<>();
            for (JsonNode c : row.path("pilietybes")) {
                JsonNode entry = c.path("valstybe").path("pilietybeClaEntry");
                String code = entry.path("properties").path("kodas2s").asText("");
                if (!code.isBlank()) {
                    citizenships.add(code.strip());
                } else if (!entry.path("titleEn").asText("").isBlank()) {
                    citizenships.add(entry.path("titleEn").asText().strip());
                }
            }
            String sex = text(row, "lytis");
            Gender gender = "V".equals(sex) ? Gender.MALE : "M".equals(sex) ? Gender.FEMALE : null;

            SanctionedEntity entity =
                    new SanctionedEntity(
                                    ids.next(full, String.valueOf(dob)),
                                    EntityType.INDIVIDUAL,
                                    ListSource.LT_ENTRY_BAN,
                                    new NameInfo(
                                            full,
                                            given,
                                            family,
                                            null,
                                            null,
                                            NameType.PRIMARY,
                                            NameStrength.STRONG,
                                            null),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.copyOf(citizenships),
                                    List.copyOf(citizenships),
                                    dob == null ? List.of() : List.of(dob),
                                    List.of(),
                                    until == null
                                            ? null
                                            : "Banned from entering Lithuania until " + until,
                                    List.of(program(reason)),
                                    null,
                                    now,
                                    Set.of(RiskTopic.SANCTION),
                                    List.of())
                            .withListingReasons(reason == null ? List.of() : List.of(reason));
            entities.add(gender == null ? entity : entity.withGender(gender));
        }
        return entities;
    }

    /**
     * Names the legal ground of a ban: Article 133(4) is Lithuania's Magnitsky provision; any other
     * ground keeps its own wording.
     *
     * @param reason the ground as published, may be {@code null}
     * @return the program
     */
    static SanctionsProgram program(String reason) {
        if (reason != null && reason.contains("133 str. 4 d.")) {
            return new SanctionsProgram(
                    "Aliens Law Art. 133(4)",
                    "Entry ban for human rights abuses, money laundering or corruption (Magnitsky)",
                    ListSource.LT_ENTRY_BAN);
        }
        String ground = reason == null ? "Entry ban" : reason;
        return new SanctionsProgram(ground, ground, ListSource.LT_ENTRY_BAN);
    }

    private static LocalDate date(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }
}
