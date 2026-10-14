package dev.sieve.ingest.enforcement;

import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads FinCEN's enforcement actions: one table of every action since 1999, giving the action's
 * name (linked to its notice, a PDF), the release date, the matter number and the kind of financial
 * institution.
 *
 * <p>FinCEN enforces the Bank Secrecy Act, so most of its actions are anti-money-laundering cases;
 * an action counts as a sanctions breach on the map only when its notice ties a program to
 * sanctions (see {@link #programsIn}). The penalty is read from the notice too.
 */
public final class FincenEnforcementParser {

    /** The table. */
    public static final URI PAGE =
            URI.create("https://www.fincen.gov/news-room/enforcement-actions");

    private static final Pattern ROW = Pattern.compile("(?s)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?s)<td[^>]*>(.*?)</td>");
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
    private static final Pattern IN_THE_MATTER = Pattern.compile("(?i)^in the matter of:?\\s*");
    private static final DateTimeFormatter MDY = DateTimeFormatter.ofPattern("M/d/yyyy");
    private static final String AMOUNT = "\\$\\s?([\\d,]+(?:\\.\\d+)?)(\\s*(?:million|billion))?";
    // Orders also quote statutory maxima ("a civil money penalty of up to $71,545 per day") and
    // reporting thresholds ("at least $5,000"); those are skipped and the largest penalty kept.
    private static final Pattern PENALTY =
            Pattern.compile(
                    "(?i)(?:civil money penalty|penalty|\\bpay)([^$.]{0,80})"
                            + AMOUNT);
    private static final Pattern NOT_THE_PENALTY =
            Pattern.compile(
                    "(?i)up to|exceed|greater|maximum|not more than|at least|minimum|per\\b|each");

    private static final Pattern SENTENCE =
            Pattern.compile("[^.]*\\b(?:sanction|OFAC)[^.]*\\.?", Pattern.CASE_INSENSITIVE);

    private FincenEnforcementParser() {}

    /**
     * Parses the table.
     *
     * @param html the page
     * @return the actions, programs and amounts not yet read
     */
    public static List<EnforcementAction> parse(String html) {
        List<EnforcementAction> out = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher c = CELL.matcher(rows.group(1));
            while (c.find()) {
                cells.add(c.group(1));
            }
            if (cells.size() < 2) {
                continue;
            }
            Matcher d = DATE.matcher(Html.text(cells.get(1)));
            if (!d.find()) {
                continue;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(d.group(1) + "/" + d.group(2) + "/" + d.group(3), MDY);
            } catch (DateTimeParseException e) {
                continue;
            }
            String party = IN_THE_MATTER.matcher(Html.text(cells.get(0))).replaceFirst("");
            if (party.isEmpty()) {
                continue;
            }
            Matcher h = HREF.matcher(cells.get(0));
            String url =
                    h.find()
                            ? PAGE.resolve(h.group(1).replace("&amp;", "&")).toString()
                            : PAGE.toString();
            String sector = cells.size() > 3 ? Html.text(cells.get(3)) : "";
            out.add(
                    new EnforcementAction(
                            "FINCEN-" + date.toString().replace("-", "") + "-" + Html.slug(party),
                            "FINCEN",
                            "US",
                            Kind.CIVIL_PENALTY,
                            date,
                            Optional.of(party),
                            Optional.empty(),
                            "USD",
                            Optional.empty(),
                            List.of(),
                            List.of(),
                            sector.isEmpty() ? Optional.empty() : Optional.of(sector),
                            url));
        }
        return OfacEnforcementParser.unique(out);
    }

    /**
     * The programs a notice ties to sanctions: only sentences that mention sanctions or OFAC are
     * read, since a money-laundering notice names countries for other reasons.
     *
     * @param noticeText the notice's text
     * @return the programs found
     */
    public static SanctionsPrograms.Found programsIn(String noticeText) {
        StringBuilder sentences = new StringBuilder();
        Matcher m = SENTENCE.matcher(noticeText.replaceAll("\\s+", " "));
        while (m.find()) {
            sentences.append(m.group()).append(' ');
        }
        return SanctionsPrograms.find(sentences.toString());
    }

    /**
     * The penalty a notice states, in whole dollars.
     *
     * @param noticeText the notice's text
     * @return the amount, if one is stated
     */
    public static Optional<Long> penaltyIn(String noticeText) {
        Matcher m = PENALTY.matcher(noticeText.replaceAll("\\s+", " "));
        Optional<Long> largest = Optional.empty();
        while (m.find()) {
            if (NOT_THE_PENALTY.matcher(m.group(1)).find()) {
                continue;
            }
            try {
                double v = Double.parseDouble(m.group(2).replace(",", ""));
                String scale = m.group(3) == null ? "" : m.group(3).strip().toLowerCase();
                v *= scale.equals("million") ? 1e6 : scale.equals("billion") ? 1e9 : 1;
                if (largest.isEmpty() || v > largest.get()) {
                    largest = Optional.of((long) v);
                }
            } catch (NumberFormatException e) {
                // not an amount
            }
        }
        return largest;
    }
}
