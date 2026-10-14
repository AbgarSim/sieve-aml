package dev.sieve.ingest.enforcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads US Department of Justice press releases about criminal sanctions cases from the justice.gov
 * API ({@code /api/v1/press_releases.json}), asked for releases whose title contains "sanctions".
 *
 * <p>Releases are kept when the title is about breaking sanctions (a charge, plea, conviction or
 * sentence for violating or evading them), not about sanctions in another sense. The same case is
 * often announced by both the department and a US attorney's office; those count once.
 */
public final class DojSanctionsParser {

    /** Releases per page; the API's maximum. */
    public static final int PAGE_SIZE = 50;

    private static final Pattern BREACH =
            Pattern.compile(
                    "(?i)(violat|evad|evasion|circumvent|conspir|charg|plead|pleads|guilty|convict|sentenc|indict|arrest|extradit|smuggl|export|launder|forfeit|seiz)");

    private DojSanctionsParser() {}

    /**
     * A page of the API.
     *
     * @param page zero-based page number
     * @return the address
     */
    public static URI page(int page) {
        return URI.create(
                "https://www.justice.gov/api/v1/press_releases.json?pagesize="
                        + PAGE_SIZE
                        + "&page="
                        + page
                        + "&parameters%5Btitle%5D=sanctions&sort=date&direction=DESC"
                        + "&fields=title,date,url,uuid");
    }

    /**
     * Parses one page.
     *
     * @param json the response body
     * @return the releases about breaches, in the page's order
     * @throws IOException if the body is not the expected JSON
     */
    public static List<EnforcementAction> parse(String json) throws IOException {
        JsonNode results = new ObjectMapper().readTree(json).path("results");
        if (!results.isArray()) {
            throw new IOException("No results in the DOJ response");
        }
        List<EnforcementAction> out = new ArrayList<>();
        for (JsonNode r : results) {
            String title = Html.text(r.path("title").asText(""));
            String url = r.path("url").asText("");
            long seconds = r.path("date").asLong(0);
            if (title.isEmpty() || url.isEmpty() || seconds <= 0 || !isBreach(title)) {
                continue;
            }
            LocalDate date =
                    Instant.ofEpochSecond(seconds)
                            .atZone(ZoneId.of("America/New_York"))
                            .toLocalDate();
            SanctionsPrograms.Found found = SanctionsPrograms.find(title);
            out.add(
                    new EnforcementAction(
                            "DOJ-" + date.toString().replace("-", "") + "-" + Html.slug(title),
                            "DOJ",
                            "US",
                            Kind.CRIMINAL_CASE,
                            date,
                            Optional.empty(),
                            Optional.empty(),
                            "USD",
                            Optional.empty(),
                            found.programs(),
                            found.targets(),
                            Optional.of(title),
                            url));
        }
        return out;
    }

    /**
     * The number of releases on a page, breaches or not; fewer than {@link #PAGE_SIZE} means the
     * last page.
     *
     * @param json the response body
     * @return the number of results
     * @throws IOException if the body is not JSON
     */
    public static int results(String json) throws IOException {
        return new ObjectMapper().readTree(json).path("results").size();
    }

    /** Whether a title is about breaking sanctions. */
    static boolean isBreach(String title) {
        return title.toLowerCase(Locale.ROOT).contains("sanction") && BREACH.matcher(title).find();
    }

    /** The key two releases about the same case share: the date and the title. */
    static String caseKey(EnforcementAction a) {
        return a.date() + "|" + a.summary().orElse("").toLowerCase(Locale.ROOT);
    }
}
