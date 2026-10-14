package dev.sieve.ingest.enforcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sieve.ingest.enforcement.EnforcementAction.Kind;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads HM Revenue and Customs' compound settlements for UK trade sanctions and strategic export
 * control offences, from the GOV.UK content API.
 *
 * <p>Since mid-2026 HMRC keeps one page of named settlements, a section per business headed "Name
 * (published 8 September 2026)". Before that, each settlement was announced in a Notice to
 * Exporters, usually a list of anonymous payments ("August 2024 - £58,426.45 was paid relating to
 * ..."), sometimes a single paragraph; court cases HMRC brought are announced the same way. A
 * settlement is the alternative to prosecution HMRC offers, so it is counted as a civil penalty.
 */
public final class HmrcSettlementParser {

    /** The current page of named settlements, as JSON. */
    public static final URI SETTLEMENTS =
            URI.create(
                    "https://www.gov.uk/api/content/government/publications/businesses-agreeing-to-a-compound-settlement-for-strategic-export-and-sanction-offences/agreed-compound-settlements-for-strategic-export-and-sanction-offences");

    /** The same page as people read it. */
    public static final URI PAGE =
            URI.create(
                    "https://www.gov.uk/government/publications/businesses-agreeing-to-a-compound-settlement-for-strategic-export-and-sanction-offences/agreed-compound-settlements-for-strategic-export-and-sanction-offences");

    /** GOV.UK search for the notices to exporters that announce settlements. */
    public static final URI SEARCH =
            URI.create(
                    "https://www.gov.uk/api/search.json?q=compound+settlement"
                            + "&filter_content_store_document_type=notice&count=200"
                            + "&fields=title,link,public_timestamp");

    private static final String GOV_UK = "https://www.gov.uk";
    private static final Pattern H2 = Pattern.compile("(?s)<h2[^>]*>(.*?)</h2>");
    private static final Pattern H3 = Pattern.compile("(?s)<h3[^>]*>");
    private static final Pattern PARAGRAPH = Pattern.compile("(?s)<(p|li)[^>]*>(.*?)</\\1>");
    private static final Pattern PUBLISHED =
            Pattern.compile("(?i)^(.*?)\\s*\\(published (\\d{1,2} [A-Za-z]+ \\d{4})\\)$");
    private static final Pattern POUNDS =
            Pattern.compile("(?i)£\\s?(\\d[\\d,]*(?:\\.\\d+)?)(\\s*million)?");
    private static final Pattern PAYMENT =
            Pattern.compile(
                    "(?i)^(in )?(January|February|March|April|May|June|July|August|September|October|November|December) \\d{4}\\s*[-–,]");
    private static final Pattern NOTICE = Pattern.compile("(?i)^notice to exporters \\d{4}/\\d+");
    private static final Pattern SETTLEMENT_NOTICE =
            Pattern.compile("(?i)compound|fined|penalt|punished|settlement");
    private static final Pattern COURT_NOTICE =
            Pattern.compile("(?i)sentenced|jailed|convicted|prosecut");
    // Lines that sum up several settlements rather than describe one.
    private static final Pattern AGGREGATE =
            Pattern.compile(
                    "(?i)\\btotal|between £|ranging|settlements|offers to|penalties between|"
                            + "sanctioned over|funding");
    private static final DateTimeFormatter DMY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** A notice to exporters found by the search. */
    public record Notice(String title, String path, LocalDate published) {}

    private HmrcSettlementParser() {}

    /**
     * Parses the current page of named settlements.
     *
     * @param json the content API's response
     * @return the settlements, newest first as the page lists them
     * @throws IOException if the body is not the expected JSON
     */
    public static List<EnforcementAction> parseSettlements(String json) throws IOException {
        String html = body(json);
        List<EnforcementAction> out = new ArrayList<>();
        List<int[]> heads = new ArrayList<>();
        Matcher h = H2.matcher(html);
        while (h.find()) {
            heads.add(new int[] {h.start(), h.end()});
        }
        for (int i = 0; i < heads.size(); i++) {
            Matcher head =
                    PUBLISHED.matcher(Html.text(html.substring(heads.get(i)[0], heads.get(i)[1])));
            if (!head.matches()) {
                continue;
            }
            Optional<LocalDate> date = date(head.group(2));
            if (date.isEmpty()) {
                continue;
            }
            String section =
                    html.substring(
                            heads.get(i)[1],
                            i + 1 < heads.size() ? heads.get(i + 1)[0] : html.length());
            // Sections run on into "Compound settlements criteria" and other boilerplate that
            // names sanctions in general; only the overview describes the case.
            Matcher sub = H3.matcher(section);
            if (sub.find() && sub.find()) {
                section = section.substring(0, sub.start());
            }
            String name = head.group(1);
            Optional<String> party =
                    name.toLowerCase(Locale.ROOT).contains("anonymous")
                            ? Optional.empty()
                            : Optional.of(name);
            List<String> paragraphs = paragraphs(section);
            Optional<String> first =
                    paragraphs.stream().filter(p -> POUNDS.matcher(p).find()).findFirst();
            String text = String.join(" ", paragraphs);
            out.add(
                    action(
                            date.get(),
                            party,
                            first.flatMap(HmrcSettlementParser::pounds),
                            text,
                            first.orElse(name),
                            PAGE
                                    + "#"
                                    + Html.slug(
                                            Html.text(
                                                    html.substring(
                                                            heads.get(i)[0], heads.get(i)[1]))),
                            Kind.CIVIL_PENALTY,
                            party.orElse("anonymous")));
        }
        return out;
    }

    /**
     * The notices to exporters about settlements and court cases, from the search's response.
     *
     * @param json the search API's response
     * @return the notices, in the search's order
     * @throws IOException if the body is not the expected JSON
     */
    public static List<Notice> notices(String json) throws IOException {
        JsonNode results = new ObjectMapper().readTree(json).path("results");
        if (!results.isArray()) {
            throw new IOException("No results in the GOV.UK search response");
        }
        List<Notice> out = new ArrayList<>();
        for (JsonNode r : results) {
            String title = r.path("title").asText("");
            String link = r.path("link").asText("");
            if (!NOTICE.matcher(title).find()
                    || !link.startsWith("/")
                    || !(SETTLEMENT_NOTICE.matcher(title).find()
                            || COURT_NOTICE.matcher(title).find())) {
                continue;
            }
            try {
                LocalDate published =
                        OffsetDateTime.parse(r.path("public_timestamp").asText()).toLocalDate();
                out.add(new Notice(title, link, published));
            } catch (DateTimeParseException e) {
                // a notice without a date cannot be placed in time
            }
        }
        return out;
    }

    /**
     * The address of a notice's HTML attachment, which holds its text.
     *
     * @param json the content API's response for the notice
     * @return the attachment's content API address, if the notice has one
     * @throws IOException if the body is not JSON
     */
    public static Optional<URI> attachment(String json) throws IOException {
        for (JsonNode a : new ObjectMapper().readTree(json).path("details").path("attachments")) {
            String url = a.path("url").asText("");
            if (url.startsWith("/")) {
                return Optional.of(URI.create(GOV_UK + "/api/content" + url));
            }
        }
        return Optional.empty();
    }

    /**
     * The address of a page in the content API.
     *
     * @param path the page's path on GOV.UK
     * @return the content API address
     */
    public static URI content(String path) {
        return URI.create(GOV_UK + "/api/content" + path);
    }

    /**
     * Parses one notice to exporters: each listed payment is a settlement; a notice without a list
     * is one settlement, or one court case when its title says so.
     *
     * @param notice the notice
     * @param json the content API's response for the notice's HTML attachment
     * @return the settlements or case the notice announces
     * @throws IOException if the body is not the expected JSON
     */
    public static List<EnforcementAction> parseNotice(Notice notice, String json)
            throws IOException {
        List<String> paragraphs = paragraphs(body(json));
        String url = GOV_UK + notice.path();
        String number =
                notice.title().replaceAll("(?i)^notice to exporters (\\d{4})/(\\d+).*", "$1-$2");
        List<EnforcementAction> out = new ArrayList<>();
        if (COURT_NOTICE.matcher(notice.title()).find()) {
            String text = String.join(" ", paragraphs);
            out.add(
                    action(
                            notice.published(),
                            Optional.empty(),
                            Optional.empty(),
                            notice.title() + " " + text,
                            notice.title()
                                    .replaceFirst(
                                            "(?i)^notice to exporters \\d{4}/\\d+\\s*:\\s*", ""),
                            url,
                            Kind.CRIMINAL_CASE,
                            "nte-" + number));
            return out;
        }
        List<String> payments = paragraphs.stream().filter(p -> PAYMENT.matcher(p).find()).toList();
        if (payments.isEmpty()) {
            Optional<String> single =
                    paragraphs.stream()
                            .filter(p -> POUNDS.matcher(p).find() && !AGGREGATE.matcher(p).find())
                            .findFirst();
            if (single.isEmpty()) {
                return out;
            }
            payments = List.of(single.get());
        }
        for (int i = 0; i < payments.size(); i++) {
            String p = payments.get(i);
            Optional<Long> amount = pounds(p);
            if (amount.isEmpty()) {
                continue;
            }
            out.add(
                    action(
                            notice.published(),
                            Optional.empty(),
                            amount,
                            p,
                            p,
                            url,
                            Kind.CIVIL_PENALTY,
                            "nte-" + number + (payments.size() > 1 ? "-" + (i + 1) : "")));
        }
        return out;
    }

    /**
     * Whether two settlements are the same one announced twice: the same amount within a few months
     * (a named settlement appears both in its notice and on the current page).
     *
     * @param a one settlement
     * @param b another
     * @return whether they are the same
     */
    public static boolean sameSettlement(EnforcementAction a, EnforcementAction b) {
        return a.kind() == Kind.CIVIL_PENALTY
                && b.kind() == Kind.CIVIL_PENALTY
                && a.amount().isPresent()
                && a.amount().equals(b.amount())
                && Math.abs(a.date().toEpochDay() - b.date().toEpochDay()) <= 120;
    }

    private static EnforcementAction action(
            LocalDate date,
            Optional<String> party,
            Optional<Long> amount,
            String text,
            String summary,
            String url,
            Kind kind,
            String slug) {
        SanctionsPrograms.Found found = SanctionsPrograms.find(text);
        String line = summary.length() > 300 ? summary.substring(0, 297) + "..." : summary;
        return new EnforcementAction(
                "HMRC-" + date.toString().replace("-", "") + "-" + Html.slug(slug),
                "HMRC",
                "GB",
                kind,
                date,
                party,
                amount,
                "GBP",
                Optional.empty(),
                found.programs(),
                found.targets(),
                Optional.of(line),
                url);
    }

    private static String body(String json) throws IOException {
        JsonNode body = new ObjectMapper().readTree(json).path("details").path("body");
        if (!body.isTextual()) {
            throw new IOException("No details.body in the GOV.UK response");
        }
        return body.asText();
    }

    private static List<String> paragraphs(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = PARAGRAPH.matcher(html);
        while (m.find()) {
            String t = Html.text(m.group(2));
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** The first amount in pounds in a text, in whole pounds; "£1 million" counts. */
    static Optional<Long> pounds(String text) {
        Matcher m = POUNDS.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            BigDecimal n = new BigDecimal(m.group(1).replace(",", ""));
            if (m.group(2) != null) {
                n = n.multiply(BigDecimal.valueOf(1_000_000));
            }
            return Optional.of(n.longValue());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDate> date(String s) {
        try {
            return Optional.of(LocalDate.parse(s, DMY));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
