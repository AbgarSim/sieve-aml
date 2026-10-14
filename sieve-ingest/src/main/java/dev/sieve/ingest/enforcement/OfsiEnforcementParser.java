package dev.sieve.ingest.enforcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the UK Office of Financial Sanctions Implementation's enforcement actions from the GOV.UK
 * content API: the collection page's body lists each action as a heading linked to its notice,
 * followed by a table of date, sector, penalty (an amount in pounds, or "Disclosure" for a breach
 * published without a penalty), reason and the regulations breached.
 */
public final class OfsiEnforcementParser {

    /** The collection, as JSON. */
    public static final URI COLLECTION =
            URI.create(
                    "https://www.gov.uk/api/content/government/collections/enforcement-of-financial-sanctions");

    /** The collection as people read it. */
    public static final URI PAGE =
            URI.create(
                    "https://www.gov.uk/government/collections/enforcement-of-financial-sanctions");

    private static final Pattern H3 = Pattern.compile("(?s)<h3[^>]*>(.*?)</h3>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern ROW = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?s)<t[dh][^>]*>(.*?)</t[dh]>");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2}) ([A-Za-z]+) (\\d{4})");
    private static final DateTimeFormatter DMY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    private OfsiEnforcementParser() {}

    /**
     * Parses the content API's JSON for the collection.
     *
     * @param json the response body
     * @return the actions, newest first as the page lists them
     * @throws IOException if the body is not the expected JSON
     */
    public static List<EnforcementAction> parse(String json) throws IOException {
        JsonNode body = new ObjectMapper().readTree(json).path("details").path("body");
        if (!body.isTextual()) {
            throw new IOException("No details.body in the OFSI collection");
        }
        return parseBody(body.asText());
    }

    static List<EnforcementAction> parseBody(String html) {
        List<EnforcementAction> out = new ArrayList<>();
        List<int[]> heads = new ArrayList<>();
        Matcher h = H3.matcher(html);
        while (h.find()) {
            heads.add(new int[] {h.start(), h.end()});
        }
        for (int i = 0; i < heads.size(); i++) {
            String head = html.substring(heads.get(i)[0], heads.get(i)[1]);
            String section =
                    html.substring(
                            heads.get(i)[1],
                            i + 1 < heads.size() ? heads.get(i + 1)[0] : html.length());
            Map<String, String> fields = fields(section);
            Optional<LocalDate> date = date(fields.getOrDefault("date", ""));
            String party = Html.text(head);
            if (date.isEmpty() || party.isEmpty()) {
                continue;
            }
            Matcher link = HREF.matcher(head);
            String url =
                    link.find()
                            ? PAGE.resolve(link.group(1).replace("&amp;", "&")).toString()
                            : PAGE.toString();
            String penalty = fields.getOrDefault("penalty", "");
            boolean disclosure = penalty.toLowerCase(Locale.ROOT).contains("disclosure");
            String regulations = fields.getOrDefault("regulations", "");
            String reason = fields.getOrDefault("reason", "");
            SanctionsPrograms.Found found = SanctionsPrograms.find(regulations);
            out.add(
                    new EnforcementAction(
                            "OFSI-"
                                    + date.get().toString().replace("-", "")
                                    + "-"
                                    + Html.slug(party),
                            "OFSI",
                            "GB",
                            disclosure ? Kind.DISCLOSURE : Kind.CIVIL_PENALTY,
                            date.get(),
                            Optional.of(party),
                            disclosure ? Optional.empty() : Html.amount(penalty),
                            "GBP",
                            Optional.empty(),
                            found.programs(),
                            found.targets(),
                            reason.isEmpty() ? Optional.empty() : Optional.of(reason),
                            url));
        }
        return OfacEnforcementParser.unique(out);
    }

    /** The two-cell rows of a section's table, keyed by the first cell in lower case. */
    private static Map<String, String> fields(String section) {
        Map<String, String> out = new HashMap<>();
        Matcher r = ROW.matcher(section);
        while (r.find()) {
            List<String> cells = new ArrayList<>();
            Matcher c = CELL.matcher(r.group(1));
            while (c.find()) {
                cells.add(Html.text(c.group(1)));
            }
            if (cells.size() >= 2) {
                out.putIfAbsent(cells.get(0).toLowerCase(Locale.ROOT), cells.get(1));
            }
        }
        return out;
    }

    private static Optional<LocalDate> date(String s) {
        Matcher m = DATE.matcher(s);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    LocalDate.parse(m.group(1) + " " + m.group(2) + " " + m.group(3), DMY));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
