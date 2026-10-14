package dev.sieve.ingest.enforcement;

import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads OFAC's civil penalties chart, one page per year: a table whose rows give the date (linked
 * to the enforcement notice, a PDF), the party, the number of penalties, settlements or findings of
 * violation, and the total in USD.
 *
 * <p>Up to about 2008 several rows share one PDF that tabulates many cases, so a row's program is
 * read from the text that follows the party's name (see {@link #programsFor}).
 */
public final class OfacEnforcementParser {

    /** The chart for the current year. */
    public static final URI CURRENT =
            URI.create("https://ofac.treasury.gov/civil-penalties-and-enforcement-information");

    /** The first year OFAC publishes a chart for. */
    public static final int FIRST_YEAR = 2003;

    private static final Pattern ROW = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?s)<td[^>]*>(.*?)</td>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final DateTimeFormatter MDY = DateTimeFormatter.ofPattern("M/d/yyyy");

    /** Characters of a shared notice read after the party's name. */
    static final int WINDOW = 400;

    /** Characters of a notice of its own read for its programs. */
    static final int HEAD = 3000;

    private OfacEnforcementParser() {}

    /**
     * The chart of a year.
     *
     * @param year the year
     * @param currentYear the current year, whose chart has no year in its address
     * @return the page's address
     */
    public static URI yearPage(int year, int currentYear) {
        return year >= currentYear
                ? CURRENT
                : CURRENT.resolve(
                        "civil-penalties-and-enforcement-information/"
                                + year
                                + "-enforcement-information");
    }

    /**
     * Parses one year's chart. Rows without a date are skipped.
     *
     * @param html the page
     * @param page the page's address, to resolve the notice links against
     * @return the actions, programs not yet read
     */
    public static List<EnforcementAction> parse(String html, URI page) {
        List<EnforcementAction> out = new ArrayList<>();
        int start = html.indexOf("<tbody");
        if (start < 0) {
            return out;
        }
        Matcher rows = ROW.matcher(html.substring(start));
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher c = CELL.matcher(rows.group(1));
            while (c.find()) {
                cells.add(c.group(1));
            }
            if (cells.size() < 4) {
                continue;
            }
            Matcher d = DATE.matcher(Html.text(cells.get(0)));
            if (!d.find()) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(d.group(1) + "/" + d.group(2) + "/" + d.group(3), MDY);
            } catch (DateTimeParseException e) {
                continue;
            }
            Matcher h = HREF.matcher(cells.get(0));
            String url =
                    h.find()
                            ? page.resolve(h.group(1).replace("&amp;", "&")).toString()
                            : page.toString();
            String party = Html.text(cells.get(1));
            if (party.isEmpty()) {
                continue;
            }
            Optional<Integer> count =
                    Html.amount(Html.text(cells.get(2))).map(Long::intValue).filter(n -> n > 0);
            Optional<Long> amount = Html.amount(Html.text(cells.get(3)));
            out.add(
                    new EnforcementAction(
                            "OFAC-" + date.toString().replace("-", "") + "-" + Html.slug(party),
                            "OFAC",
                            "US",
                            Kind.CIVIL_PENALTY,
                            date,
                            Optional.of(party),
                            amount,
                            "USD",
                            count,
                            List.of(),
                            List.of(),
                            Optional.empty(),
                            url));
        }
        return unique(out);
    }

    /** Makes ids unique by numbering repeats: the same party twice on one day. */
    static List<EnforcementAction> unique(List<EnforcementAction> actions) {
        Map<String, Integer> seen = new HashMap<>();
        List<EnforcementAction> out = new ArrayList<>(actions.size());
        for (EnforcementAction a : actions) {
            int n = seen.merge(a.id(), 1, Integer::sum);
            out.add(
                    n == 1
                            ? a
                            : new EnforcementAction(
                                    a.id() + "-" + n,
                                    a.authority(),
                                    a.jurisdiction(),
                                    a.kind(),
                                    a.date(),
                                    a.party(),
                                    a.amount(),
                                    a.currency(),
                                    a.violations(),
                                    a.programs(),
                                    a.targets(),
                                    a.summary(),
                                    a.url()));
        }
        return out;
    }

    /**
     * Reads a row's programs from its notice. A notice of its own names them in its first
     * paragraphs; a notice shared by several rows names each row's program next to the party.
     *
     * @param noticeText the notice's text
     * @param party the row's party
     * @param shared whether other rows link the same notice
     * @return the programs found
     */
    public static SanctionsPrograms.Found programsFor(
            String noticeText, String party, boolean shared) {
        if (!shared) {
            return SanctionsPrograms.find(
                    noticeText.substring(0, Math.min(HEAD, noticeText.length())));
        }
        String flat = noticeText.replaceAll("\\s+", " ");
        int at = indexOfName(flat, party);
        if (at < 0) {
            return SanctionsPrograms.Found.NONE;
        }
        // The chart gives one program per row, right after the address; the next row follows.
        int from = at + party.length();
        int dollar = flat.indexOf('$', from);
        int to =
                Math.min(
                        flat.length(),
                        Math.min(from + WINDOW, dollar < 0 ? Integer.MAX_VALUE : dollar));
        return SanctionsPrograms.find(flat.substring(from, to)).first();
    }

    /**
     * Where a name starts in a text, ignoring case; tries its first three words when whole fails.
     */
    static int indexOfName(String text, String name) {
        String t = text.toLowerCase();
        String n = name.toLowerCase().replaceAll("\\s+", " ");
        int at = t.indexOf(n);
        if (at >= 0) {
            return at;
        }
        String[] words = n.split(" ");
        if (words.length > 3) {
            return t.indexOf(String.join(" ", List.of(words).subList(0, 3)));
        }
        return -1;
    }
}
