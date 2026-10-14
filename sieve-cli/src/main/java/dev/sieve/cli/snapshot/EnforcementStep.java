package dev.sieve.cli.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.sieve.ingest.enforcement.EnforcementAction;
import dev.sieve.ingest.enforcement.EnforcementFeed;
import dev.sieve.ingest.enforcement.SuspectedBreach;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Writes {@code enforcement.json}: published enforcement actions for sanctions breaches (civil
 * penalties, disclosures and criminal cases), with the countries the breached programs target.
 *
 * <p>The previous file (restored from the published site, like {@code news.json}) lets a night skip
 * the notices it already read, and stands in for a source that fails.
 *
 * <p>It also keeps news of suspected breaches, unverified, for {@link #NEWS_DAYS} days across
 * nightly runs.
 *
 * <p>Format: {@code {formatVersion, generatedAt, sources: [{id, name, authority, cc, url, status,
 * actions, fetchMs, error}], actions: [{id, source, cc, kind, date, party, amount, currency,
 * violations, programs, targets, summary, url, unread}], suspected: [{url, title, domain, seenAt,
 * programs, targets}]}}, newest first. {@code unread} marks an OFAC action whose notice the run
 * could not read in time, so that the next run reads it.
 */
public final class EnforcementStep {

    /** The file name. */
    public static final String FILE = "enforcement.json";

    private final ObjectMapper mapper =
            new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);

    /** Days a news article of a suspected breach stays in the file. */
    static final int NEWS_DAYS = 90;

    /** News articles kept at most, newest first. */
    static final int NEWS_MAX = 3000;

    private final EnforcementFeed feed;
    private final Supplier<List<SuspectedBreach>> news;
    private final Clock clock;

    /**
     * Creates the step.
     *
     * @param feed the feed
     * @param news tonight's news of suspected breaches; empty when the news is not read
     * @param clock the clock
     */
    public EnforcementStep(
            EnforcementFeed feed, Supplier<List<SuspectedBreach>> news, Clock clock) {
        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.news = Objects.requireNonNull(news, "news must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Fetches the actions and writes the file, reading the previous file in the same directory.
     *
     * @param outDir the snapshot directory
     * @return what was fetched
     * @throws IOException if the file cannot be written
     */
    public EnforcementFeed.Result write(Path outDir) throws IOException {
        Path file = outDir.resolve(FILE);
        Set<String> previousUnread = new HashSet<>();
        Map<String, EnforcementAction> previous =
                Files.exists(file) ? read(file, previousUnread) : Map.of();
        EnforcementFeed.Result result = feed.fetch(previous, previousUnread);
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", 1);
        root.put("generatedAt", clock.instant().toString());
        ArrayNode sources = root.putArray("sources");
        for (EnforcementFeed.SourceResult s : result.sources()) {
            ObjectNode n = sources.addObject();
            n.put("id", s.id());
            n.put("name", s.name());
            n.put("authority", s.authority());
            n.put("cc", s.jurisdiction());
            n.put("url", s.url());
            n.put("status", s.loaded() ? "OK" : "FAILED");
            n.put("actions", s.actions());
            n.put("fetchMs", s.fetchMs());
            s.error().ifPresent(e -> n.put("error", e));
        }
        ArrayNode actions = root.putArray("actions");
        for (EnforcementAction a : result.actions()) {
            ObjectNode n = actions.addObject();
            n.put("id", a.id());
            n.put("source", a.authority());
            n.put("cc", a.jurisdiction());
            n.put("kind", a.kind().name());
            n.put("date", a.date().toString());
            a.party().ifPresent(p -> n.put("party", p));
            a.amount().ifPresent(v -> n.put("amount", v));
            n.put("currency", a.currency());
            a.violations().ifPresent(v -> n.put("violations", v));
            a.programs().forEach(n.putArray("programs")::add);
            a.targets().forEach(n.putArray("targets")::add);
            if (a.programs().isEmpty()) {
                n.putArray("programs");
            }
            if (a.targets().isEmpty()) {
                n.putArray("targets");
            }
            a.summary().ifPresent(v -> n.put("summary", v));
            n.put("url", a.url());
            if (result.unread().contains(a.id())) {
                n.put("unread", true);
            }
        }
        List<SuspectedBreach> suspected = mergeNews(file);
        ArrayNode articles = root.putArray("suspected");
        for (SuspectedBreach b : suspected) {
            ObjectNode n = articles.addObject();
            n.put("url", b.url());
            n.put("title", b.title());
            n.put("domain", b.domain());
            n.put("seenAt", b.seenAt().toString());
            b.programs().forEach(n.putArray("programs")::add);
            if (b.programs().isEmpty()) {
                n.putArray("programs");
            }
            b.targets().forEach(n.putArray("targets")::add);
            if (b.targets().isEmpty()) {
                n.putArray("targets");
            }
        }
        lastNewsCount = suspected.size();
        Files.createDirectories(outDir);
        mapper.writeValue(file.toFile(), root);
        return result;
    }

    private int lastNewsCount;

    /** The number of news articles the last {@link #write} kept. */
    public int newsCount() {
        return lastNewsCount;
    }

    /**
     * Tonight's articles merged into the previous file's: one per address and per title (the same
     * wire story runs on many sites), newer than {@link #NEWS_DAYS}, at most {@link #NEWS_MAX}.
     */
    List<SuspectedBreach> mergeNews(Path file) {
        List<SuspectedBreach> all = new ArrayList<>();
        try {
            all.addAll(news.get());
        } catch (RuntimeException e) {
            System.err.println("Breach news skipped: " + e.getMessage());
        }
        if (Files.exists(file)) {
            try {
                for (JsonNode n : mapper.readTree(file.toFile()).path("suspected")) {
                    all.add(
                            new SuspectedBreach(
                                    n.path("url").asText(),
                                    n.path("title").asText(),
                                    n.path("domain").asText(),
                                    Instant.parse(n.path("seenAt").asText()),
                                    strings(n.path("programs")),
                                    strings(n.path("targets"))));
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("Previous breach news unreadable: " + e.getMessage());
            }
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(NEWS_DAYS));
        Set<String> urls = new HashSet<>();
        Set<String> titles = new HashSet<>();
        return all.stream()
                .sorted(Comparator.comparing(SuspectedBreach::seenAt).reversed())
                .filter(b -> b.seenAt().isAfter(cutoff))
                .filter(b -> urls.add(b.url()))
                .filter(b -> titles.add(b.title().toLowerCase(Locale.ROOT)))
                .limit(NEWS_MAX)
                .toList();
    }

    /** The actions of a file written earlier, by id. An unreadable file counts as none. */
    Map<String, EnforcementAction> read(Path file, Set<String> unread) {
        Map<String, EnforcementAction> out = new LinkedHashMap<>();
        try {
            for (JsonNode n : mapper.readTree(file.toFile()).path("actions")) {
                if (n.path("unread").asBoolean(false)) {
                    unread.add(n.path("id").asText());
                }
                out.put(
                        n.path("id").asText(),
                        new EnforcementAction(
                                n.path("id").asText(),
                                n.path("source").asText(),
                                n.path("cc").asText(),
                                EnforcementAction.Kind.valueOf(n.path("kind").asText()),
                                LocalDate.parse(n.path("date").asText()),
                                text(n, "party"),
                                n.has("amount")
                                        ? Optional.of(n.path("amount").asLong())
                                        : Optional.empty(),
                                n.path("currency").asText("USD"),
                                n.has("violations")
                                        ? Optional.of(n.path("violations").asInt())
                                        : Optional.empty(),
                                strings(n.path("programs")),
                                strings(n.path("targets")),
                                text(n, "summary"),
                                n.path("url").asText()));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println(
                    "Previous " + FILE + " unreadable, reading every notice: " + e.getMessage());
            return Map.of();
        }
        return out;
    }

    private static Optional<String> text(JsonNode n, String field) {
        return n.hasNonNull(field) ? Optional.of(n.path(field).asText()) : Optional.empty();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(x -> out.add(x.asText()));
        return out;
    }
}
