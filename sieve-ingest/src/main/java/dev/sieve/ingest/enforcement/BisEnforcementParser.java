package dev.sieve.ingest.enforcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the US Commerce Department's Bureau of Industry and Security enforcement press releases:
 * administrative penalties and settlements for export control violations, and the criminal cases
 * BIS agents worked on.
 *
 * <p>The news page is built with Next.js and carries every release in its {@code __NEXT_DATA__}
 * script, each with a title, a path, a publication time and topics. Releases tagged "Enforcement"
 * are kept when the title is a penalty, a settlement or a step in a criminal case; additions to the
 * Entity List, guidance and rewards are not breaches and are left out.
 */
public final class BisEnforcementParser {

    /** The news page. */
    public static final URI PAGE = URI.create("https://www.bis.gov/news-updates");

    private static final String SITE = "https://www.bis.gov";
    private static final Pattern NEXT_DATA =
            Pattern.compile("(?s)<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>");
    private static final Pattern CIVIL =
            Pattern.compile("(?i)\\bsettlement|\\bpenalt|\\bto pay\\b|\\bagrees? to pay");
    private static final Pattern CRIMINAL =
            Pattern.compile(
                    "(?i)\\b(sentenced|pleads?|pleaded|guilty|convicted|charged|indicted|arrested|"
                            + "extradited|admits)\\b");
    // A criminal case counts when it is about exports or sanctions, not fraud or corruption.
    private static final Pattern TRADE =
            Pattern.compile("(?i)export|sanction|smuggl|evad|evasion|circumvent|divert|\\bship");
    private static final Pattern NOT_A_CASE =
            Pattern.compile(
                    "(?i)\\bentity list|guidance|\\breward|fact sheet|don.t let this happen");
    private static final Pattern SETTLED_WITH =
            Pattern.compile("(?i)settlement with (.+?)(\\s+order)?\\s*$");
    private static final Pattern PENALTY_ON =
            Pattern.compile("(?i)penalt(?:y|ies) (?:on|against) (.+)$");
    // What follows the name in a title: the reason, or a note the page appended.
    private static final Pattern AFTER_NAME =
            Pattern.compile(
                    "(?i)\\s+(to resolve|for |related to|additional information|resolving|,"
                            + " resolving).*$");
    // "a Pennsylvania company" says who, not which.
    private static final Pattern NOT_A_NAME =
            Pattern.compile("(?i)^(an? )?([a-z]+ )?(company|manufacturer|contractor)\\b.*");
    private static final Pattern PAYS =
            Pattern.compile("(?i)^(.+?)\\s*(?:\\(.*?\\)\\s*)?(?:to|agrees to|will) pay\\b");
    private static final Pattern DOLLARS =
            Pattern.compile("(?i)\\$\\s?(\\d[\\d,]*(?:\\.\\d+)?)\\s*(million|m|billion|b)?\\b");

    private BisEnforcementParser() {}

    /**
     * Parses the news page.
     *
     * @param html the page
     * @return the penalties and criminal cases, newest first as the page lists them
     * @throws IOException if the page has no release data
     */
    public static List<EnforcementAction> parse(String html) throws IOException {
        Matcher m = NEXT_DATA.matcher(html);
        if (!m.find()) {
            throw new IOException("No __NEXT_DATA__ on the BIS news page");
        }
        JsonNode teasers =
                new ObjectMapper()
                        .readTree(m.group(1))
                        .path("props")
                        .path("pageProps")
                        .path("pressReleaseTeasers");
        if (!teasers.isArray()) {
            throw new IOException("No press releases on the BIS news page");
        }
        // The page repeats some releases; the same title on the same day counts once.
        Map<String, EnforcementAction> out = new LinkedHashMap<>();
        for (JsonNode t : teasers) {
            String title = Html.text(t.path("title").asText("")).replaceAll("[:\\s]+$", "");
            String path = t.path("path").asText("");
            long seconds = t.path("publishedOn").path("timestamp").asLong(0);
            if (seconds <= 0) {
                seconds = t.path("created").path("timestamp").asLong(0);
            }
            if (title.isEmpty() || path.isEmpty() || seconds <= 0 || !enforcement(t)) {
                continue;
            }
            Optional<Kind> kind = kind(title);
            if (kind.isEmpty()) {
                continue;
            }
            LocalDate date = Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate();
            SanctionsPrograms.Found found = SanctionsPrograms.find(title);
            boolean civil = kind.get() == Kind.CIVIL_PENALTY;
            Optional<String> party = civil ? party(title) : Optional.empty();
            out.putIfAbsent(
                    date + "|" + title.toLowerCase(Locale.ROOT),
                    new EnforcementAction(
                            "BIS-"
                                    + date.toString().replace("-", "")
                                    + "-"
                                    + Html.slug(party.orElse(title)),
                            "BIS",
                            "US",
                            kind.get(),
                            date,
                            party,
                            civil ? amount(title) : Optional.empty(),
                            "USD",
                            Optional.empty(),
                            found.programs(),
                            found.targets(),
                            Optional.of(title),
                            path.startsWith("http") ? path : SITE + path));
        }
        return OfacEnforcementParser.unique(new ArrayList<>(out.values()));
    }

    private static boolean enforcement(JsonNode teaser) {
        for (JsonNode topic : teaser.path("newsUpdatesTopic")) {
            if (topic.path("name").asText("").equalsIgnoreCase("Enforcement")) {
                return true;
            }
        }
        return false;
    }

    /** What a release's title announces, if it is a penalty or a criminal case. */
    static Optional<Kind> kind(String title) {
        if (NOT_A_CASE.matcher(title).find()) {
            return Optional.empty();
        }
        if (CRIMINAL.matcher(title).find()) {
            return TRADE.matcher(title).find() ? Optional.of(Kind.CRIMINAL_CASE) : Optional.empty();
        }
        if (CIVIL.matcher(title).find()) {
            return Optional.of(Kind.CIVIL_PENALTY);
        }
        return Optional.empty();
    }

    /** The company a penalty title names. */
    static Optional<String> party(String title) {
        for (Pattern p : List.of(SETTLED_WITH, PENALTY_ON, PAYS)) {
            Matcher m = p.matcher(title);
            if (m.find()) {
                String name =
                        AFTER_NAME
                                .matcher(m.group(1).trim())
                                .replaceFirst("")
                                .replaceAll(",+$", "");
                if (!name.isEmpty()
                        && name.length() <= 160
                        && !NOT_A_NAME.matcher(name).matches()) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The amount a penalty title states. A penalty paid to BIS and OFAC together is left without
     * one, since OFAC's chart already counts its share.
     */
    static Optional<Long> amount(String title) {
        if (title.toLowerCase(Locale.ROOT).contains("combined")) {
            return Optional.empty();
        }
        Matcher m = DOLLARS.matcher(title);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            BigDecimal n = new BigDecimal(m.group(1).replace(",", ""));
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            if (unit.startsWith("m")) {
                n = n.multiply(BigDecimal.valueOf(1_000_000));
            } else if (unit.startsWith("b")) {
                n = n.multiply(BigDecimal.valueOf(1_000_000_000));
            }
            return Optional.of(n.longValue());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
