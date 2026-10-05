package dev.sieve.cli.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.NameNormalizer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes {@code news.json}: recent news articles that mention a published record's name in an
 * adverse context, kept for a rolling window across nightly runs.
 *
 * <p>A mention is a lead, never a match: the article may be about someone else of the same name.
 * Only people and organisations whose primary name has two words or more are looked up, since a
 * one-word name matches too much. Each night's articles are merged into the previous file (the
 * workflow restores it from the published site, like {@code seen.json}); articles older than the
 * window and records no longer published are dropped, and each record keeps its newest {@link
 * #PER_RECORD} articles.
 *
 * <p>Format: {@code {formatVersion, generatedAt, days, records: {key: [{url, title, domain,
 * language, seenAt, terms, mentionedAs}]}}}, newest article first.
 */
public final class NewsStep {

    private static final Logger log = LoggerFactory.getLogger(NewsStep.class);

    /** Articles kept per record. */
    static final int PER_RECORD = 10;

    /** Days an article stays in the file. */
    static final int DAYS = 30;

    private static final Set<EntityType> LOOKED_UP =
            Set.of(
                    EntityType.INDIVIDUAL,
                    EntityType.ENTITY,
                    EntityType.COMPANY,
                    EntityType.ORGANIZATION);

    private final ObjectMapper mapper =
            new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);
    private final AdverseMediaSearch search;
    private final Duration lookback;
    private final Clock clock;

    /**
     * Creates the step.
     *
     * @param search the news index, already loaded
     * @param lookback how far back to ask the index, at least a day
     * @param clock the clock
     */
    public NewsStep(AdverseMediaSearch search, Duration lookback, Clock clock) {
        this.search = Objects.requireNonNull(search, "search must not be null");
        this.lookback = Objects.requireNonNull(lookback, "lookback must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Looks up the published records of the fetched lists and writes {@code news.json} to the
     * output directory, merged with the file already there.
     *
     * @param fetched the fetched lists
     * @param outDir the snapshot directory
     * @return the number of records with at least one article
     * @throws IOException if the file cannot be read or written
     */
    public int write(List<FetchedSource> fetched, Path outDir) throws IOException {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofDays(DAYS));
        Map<String, Map<String, JsonNode>> byKey = new TreeMap<>();
        Set<String> published = new HashSet<>();
        Map<String, List<MediaArticle>> byName = new HashMap<>();
        int asked = 0;
        for (FetchedSource source : fetched) {
            for (SanctionedEntity entity : source.entities()) {
                if (!SnapshotWriter.isPublic(entity)) {
                    continue;
                }
                String key = SnapshotWriter.key(entity);
                published.add(key);
                String name = entity.primaryName().fullName();
                if (!LOOKED_UP.contains(entity.entityType()) || !lookedUp(name)) {
                    continue;
                }
                List<MediaArticle> articles = byName.get(name);
                if (articles == null) {
                    AdverseMediaReport report =
                            search.search(new AdverseMediaQuery(name, lookback, PER_RECORD));
                    articles = report.articles();
                    byName.put(name, articles);
                    asked++;
                }
                for (MediaArticle article : articles) {
                    if (!namesWhole(name, article.mentionedAs().orElse(""), entity.entityType())) {
                        continue;
                    }
                    byKey.computeIfAbsent(key, k -> new LinkedHashMap<>())
                            .put(article.url(), toJson(article));
                }
            }
        }
        int fresh = byKey.size();

        Path file = outDir.resolve("news.json");
        if (Files.exists(file)) {
            JsonNode previous = mapper.readTree(file.toFile()).path("records");
            previous.fields()
                    .forEachRemaining(
                            e -> {
                                if (!published.contains(e.getKey())) {
                                    return;
                                }
                                Map<String, JsonNode> articles =
                                        byKey.computeIfAbsent(
                                                e.getKey(), k -> new LinkedHashMap<>());
                                for (JsonNode a : e.getValue()) {
                                    articles.putIfAbsent(a.path("url").asText(), a);
                                }
                            });
        }

        Map<String, List<JsonNode>> records = new TreeMap<>();
        byKey.forEach(
                (key, articles) -> {
                    List<JsonNode> kept =
                            articles.values().stream()
                                    .filter(a -> !seenAt(a).isBefore(cutoff))
                                    .sorted(Comparator.comparing(NewsStep::seenAt).reversed())
                                    .limit(PER_RECORD)
                                    .toList();
                    if (!kept.isEmpty()) {
                        records.put(key, kept);
                    }
                });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formatVersion", 1);
        out.put("generatedAt", now.toString());
        out.put("days", DAYS);
        out.put("records", records);
        Files.createDirectories(outDir);
        mapper.writeValue(file.toFile(), out);
        log.info(
                "News mentions written [namesAsked={}, recordsTonight={}, recordsKept={}]",
                asked,
                fresh,
                records.size());
        return records.size();
    }

    /** Whether a name is specific enough to look up: two words of two letters or more. */
    static boolean lookedUp(String name) {
        int words = 0;
        for (String word : name.split("[\\s,]+")) {
            if (word.codePoints().filter(Character::isLetter).count() >= 2) {
                words++;
            }
        }
        return words >= 2;
    }

    /**
     * Whether an article names the listed name itself. The news index pairs names that differ by a
     * word or a few letters, which finds "Borough Council" for "Stockton Borough Council" and "Ali
     * Shariati" for "Ali Sharifi"; a record keeps an article only when both names have the same
     * words once normalised (an organisation's without its legal forms), in any order.
     */
    static boolean namesWhole(String listed, String mentioned, EntityType type) {
        List<String> a = words(NameNormalizer.normalizeUncached(listed, type));
        return a.size() >= 2 && a.equals(words(NameNormalizer.normalizeUncached(mentioned, type)));
    }

    private static List<String> words(String normalized) {
        return normalized.isEmpty()
                ? List.of()
                : Arrays.stream(normalized.split(" ")).sorted().toList();
    }

    private Map<String, Object> article(MediaArticle article) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("url", article.url());
        a.put("title", article.title());
        a.put("domain", article.domain());
        a.put("language", article.language());
        a.put("seenAt", article.seenAt().toString());
        a.put("terms", article.adverseTerms());
        article.mentionedAs().ifPresent(n -> a.put("mentionedAs", n));
        return a;
    }

    private JsonNode toJson(MediaArticle article) {
        try {
            return mapper.readTree(mapper.writeValueAsBytes(article(article)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Instant seenAt(JsonNode article) {
        try {
            return Instant.parse(article.path("seenAt").asText());
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
