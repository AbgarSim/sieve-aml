package dev.sieve.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.ingest.media.GdeltAdverseMediaSearch;
import dev.sieve.ingest.media.GdeltGkgFeed;
import dev.sieve.match.media.NewsMentionIndex;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI command to look up candidate adverse media for names (experimental).
 *
 * <p>Articles are leads for an analyst, never a match, so this command exits 0 whatever it finds,
 * unlike {@code screen}. It exits 2 when the news index could not be asked.
 */
@Command(
        name = "media",
        mixinStandardHelpOptions = true,
        description =
                "Find candidate adverse media for names in GDELT (experimental; never a match)")
public class MediaCommand implements Callable<Integer> {

    @Parameters(arity = "1..*", description = "Names to look up")
    private List<String> names;

    @Option(
            names = "--index",
            description =
                    "gkg: read recent GDELT news files and search them here (default);"
                            + " doc-api: ask GDELT's search API (one name every 5 seconds)",
            defaultValue = "gkg")
    private String index;

    @Option(
            names = "--hours",
            description = "gkg: hours of news files to read (1-168, default: 6)",
            defaultValue = "6")
    private int hours;

    @Option(
            names = "--english-only",
            description = "gkg: skip GDELT's machine-translated non-English files")
    private boolean englishOnly;

    @Option(
            names = "--days",
            description = "doc-api: days back to search (1-90, default: 90)",
            defaultValue = "90")
    private int days;

    @Option(
            names = {"--max-results", "-n"},
            description = "Most articles per name (default: 10)",
            defaultValue = "10")
    private int maxResults;

    @Option(names = "--json", description = "Print the reports as JSON")
    private boolean json;

    @Override
    public Integer call() throws Exception {
        if (hours < 1 || hours > 168) {
            throw new IllegalArgumentException("--hours must be between 1 and 168");
        }
        AdverseMediaSearch search;
        Duration lookback;
        switch (index) {
            case "gkg" -> {
                NewsMentionIndex mentions =
                        new NewsMentionIndex(
                                new GdeltGkgFeed(!englishOnly),
                                "GDELT GKG",
                                Duration.ofHours(hours),
                                Duration.ofHours(hours),
                                0.92,
                                Clock.systemUTC());
                System.err.printf("Reading %d hour(s) of GDELT news files...%n", hours);
                mentions.refresh();
                System.err.printf("%,d adverse articles loaded%n", mentions.size());
                search = mentions;
                lookback = Duration.ofDays(1).plusHours(hours);
            }
            case "doc-api" -> {
                search = new GdeltAdverseMediaSearch();
                lookback = Duration.ofDays(days);
            }
            default -> throw new IllegalArgumentException("--index must be gkg or doc-api");
        }

        int exitCode = 0;
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                        .enable(SerializationFeature.INDENT_OUTPUT);
        for (String name : names) {
            AdverseMediaReport report =
                    search.search(new AdverseMediaQuery(name, lookback, maxResults));
            if (report.status() == AdverseMediaReport.Status.UNAVAILABLE) {
                exitCode = 2;
            }
            if (json) {
                System.out.println(mapper.writeValueAsString(toMap(report)));
            } else {
                print(report);
            }
        }
        return exitCode;
    }

    private static Map<String, Object> toMap(AdverseMediaReport report) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", report.name());
        out.put("index", report.index());
        out.put("indexQuery", report.indexQuery());
        out.put("status", report.status().name());
        report.detail().ifPresent(d -> out.put("detail", d));
        out.put("searchedAt", report.searchedAt());
        List<Map<String, Object>> articles = new ArrayList<>();
        for (MediaArticle article : report.articles()) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("url", article.url());
            a.put("title", article.title());
            a.put("domain", article.domain());
            a.put("language", article.language());
            article.sourceCountry().ifPresent(c -> a.put("sourceCountry", c));
            a.put("seenAt", article.seenAt());
            a.put("adverseTerms", article.adverseTerms());
            article.mentionedAs().ifPresent(n -> a.put("mentionedAs", n));
            articles.add(a);
        }
        out.put("articles", articles);
        return out;
    }

    private static void print(AdverseMediaReport report) {
        System.out.printf("%n%s  [%s: %s]%n", report.name(), report.index(), report.indexQuery());
        if (report.status() == AdverseMediaReport.Status.UNAVAILABLE) {
            System.out.printf("  Index unavailable: %s%n", report.detail().orElse("unknown"));
            return;
        }
        if (report.articles().isEmpty()) {
            System.out.println("  No candidate articles (this does not clear the name)");
            return;
        }
        System.out.printf(
                "  %d candidate article(s); not a match, read before relying on them%n",
                report.articles().size());
        for (MediaArticle article : report.articles()) {
            System.out.printf(
                    "  %s  %-25s  %s%n      %s%n      %s%s%n",
                    article.seenAt().toString().substring(0, 10),
                    article.domain(),
                    String.join(", ", article.adverseTerms()),
                    article.title().isEmpty() ? "(no title)" : article.title(),
                    article.url(),
                    article.mentionedAs().map(n -> "  (named as: " + n + ")").orElse(""));
        }
    }
}
