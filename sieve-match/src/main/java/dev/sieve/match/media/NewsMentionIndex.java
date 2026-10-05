package dev.sieve.match.media;

import dev.sieve.core.media.AdverseMediaQuery;
import dev.sieve.core.media.AdverseMediaReport;
import dev.sieve.core.media.AdverseMediaSearch;
import dev.sieve.core.media.MediaArticle;
import dev.sieve.core.media.NewsMention;
import dev.sieve.core.media.NewsMentionFeed;
import dev.sieve.core.model.EntityType;
import dev.sieve.match.NameNormalizer;
import dev.sieve.match.algorithm.JaroWinkler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds a rolling window of adverse news articles from a {@link NewsMentionFeed} and finds the ones
 * that name someone like the searched name.
 *
 * <p>This index is separate from the entity index on purpose. The articles are not entities, are
 * never screened against, and a hit here is a candidate for an analyst to read, not a match: the
 * news index extracted the names by machine, and an article that is adverse may be adverse for
 * someone else it names.
 *
 * <p>Names are compared on the same key as list screening ({@link NameNormalizer}), word by word in
 * any order: every word of the shorter name must pair with a different word of the longer one at
 * the threshold's Jaro-Winkler similarity, or be that word cut short by up to two letters (the
 * index drops letters it cannot read). The longer name may have one word more. One-word names must
 * be equal, so a surname alone never finds anyone.
 */
public final class NewsMentionIndex implements AdverseMediaSearch {

    private static final Logger log = LoggerFactory.getLogger(NewsMentionIndex.class);
    private static final int BLOCK_LENGTH = 4;

    private final NewsMentionFeed feed;
    private final String indexName;
    private final Duration backfill;
    private final Duration retention;
    private final double threshold;
    private final Clock clock;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Entry> byUrl = new LinkedHashMap<>();
    private final Map<String, Set<String>> urlsByName = new HashMap<>();
    private final Map<String, Set<String>> namesByBlock = new HashMap<>();
    private Instant coveredUntil;

    /**
     * Creates an empty index; call {@link #refresh()} to load it.
     *
     * @param feed where the articles come from
     * @param indexName the name reported on results, for example {@code GDELT GKG}
     * @param backfill how far back the first refresh reads
     * @param retention how long articles are kept
     * @param threshold the least name similarity taken as the same name, between 0.8 and 1.0
     * @param clock the clock
     */
    public NewsMentionIndex(
            NewsMentionFeed feed,
            String indexName,
            Duration backfill,
            Duration retention,
            double threshold,
            Clock clock) {
        this.feed = Objects.requireNonNull(feed, "feed must not be null");
        this.indexName = Objects.requireNonNull(indexName, "indexName must not be null");
        this.backfill = Objects.requireNonNull(backfill, "backfill must not be null");
        this.retention = Objects.requireNonNull(retention, "retention must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (threshold < 0.8 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold must be between 0.8 and 1.0");
        }
        this.threshold = threshold;
    }

    /**
     * Reads the articles published since the last refresh (or the backfill period on the first
     * call) and drops the ones older than the retention period.
     *
     * @return the number of articles added
     */
    public int refresh() {
        Instant now = clock.instant();
        Instant from;
        lock.readLock().lock();
        try {
            from = coveredUntil != null ? coveredUntil : now.minus(backfill);
        } finally {
            lock.readLock().unlock();
        }
        NewsMentionFeed.Batch batch = feed.fetch(from, now);

        lock.writeLock().lock();
        try {
            int added = 0;
            for (NewsMention mention : batch.mentions()) {
                if (add(mention)) {
                    added++;
                }
            }
            int dropped = evictBefore(now.minus(retention));
            if (batch.filesRead() > 0 || coveredUntil != null) {
                coveredUntil = batch.coveredUntil();
            }
            log.info(
                    "Adverse media index refreshed [added={}, dropped={}, articles={}, names={},"
                            + " coveredUntil={}]",
                    added,
                    dropped,
                    byUrl.size(),
                    urlsByName.size(),
                    coveredUntil);
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns how many articles the index holds.
     *
     * @return the article count
     */
    public int size() {
        lock.readLock().lock();
        try {
            return byUrl.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns how far the feed has been read, empty before the first successful refresh.
     *
     * @return the end of the window read
     */
    public Optional<Instant> coveredUntil() {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(coveredUntil);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public AdverseMediaReport search(AdverseMediaQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        Instant now = clock.instant();
        String key = NameNormalizer.normalizeQuery(query.name(), Optional.empty());
        String described = "names like \"" + key + "\" in adverse articles";

        lock.readLock().lock();
        try {
            if (coveredUntil == null) {
                return new AdverseMediaReport(
                        query.name(),
                        indexName,
                        described,
                        AdverseMediaReport.Status.UNAVAILABLE,
                        List.of(),
                        now,
                        Optional.of("The news feed has not been read yet"));
            }
            Instant since = now.minus(query.lookback());
            Map<String, MediaArticle> found = new HashMap<>();
            for (String name : matchingNames(key)) {
                for (String url : urlsByName.getOrDefault(name, Set.of())) {
                    Entry entry = byUrl.get(url);
                    if (entry == null || entry.mention.article().seenAt().isBefore(since)) {
                        continue;
                    }
                    found.computeIfAbsent(
                            url, u -> entry.mention.article().mentionedAs(entry.original(name)));
                }
            }
            // Syndicated copies of one story share a headline: keep the newest
            Set<String> titles = new HashSet<>();
            List<MediaArticle> articles =
                    found.values().stream()
                            .sorted(Comparator.comparing(MediaArticle::seenAt).reversed())
                            .filter(a -> a.title().isEmpty() || titles.add(headline(a.title())))
                            .limit(query.maxArticles())
                            .toList();
            return new AdverseMediaReport(
                    query.name(),
                    indexName,
                    described + " since " + since,
                    AdverseMediaReport.Status.OK,
                    articles,
                    now,
                    Optional.empty());
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<String> matchingNames(String key) {
        if (key.isEmpty()) {
            return List.of();
        }
        String[] words = key.split(" ");
        Set<String> candidates = new HashSet<>();
        for (String block : blocks(words)) {
            candidates.addAll(namesByBlock.getOrDefault(block, Set.of()));
        }
        List<String> names = new ArrayList<>();
        for (String candidate : candidates) {
            if (key.equals(candidate) || sameName(words, candidate.split(" "))) {
                names.add(candidate);
            }
        }
        return names;
    }

    /**
     * Pairs every word of the shorter name with a different word of the longer one, in any order.
     * The longer name may have one word more; one-word names must be equal.
     */
    private boolean sameName(String[] a, String[] b) {
        String[] shorter = a.length <= b.length ? a : b;
        String[] longer = shorter == a ? b : a;
        if (longer.length - shorter.length > 1) {
            return false;
        }
        if (shorter.length < 2) {
            return longer.length == 1 && shorter[0].equals(longer[0]);
        }
        boolean[] used = new boolean[longer.length];
        for (String word : shorter) {
            int best = -1;
            double bestScore = 0;
            for (int i = 0; i < longer.length; i++) {
                if (used[i]) {
                    continue;
                }
                double score = wordSimilarity(word, longer[i]);
                if (score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best < 0 || bestScore < threshold) {
                return false;
            }
            used[best] = true;
        }
        return true;
    }

    /**
     * Jaro-Winkler similarity of two words, or 1.0 when one is the other with up to two letters cut
     * off the end, as the news index does with letters it cannot read ("Cvijanovi" for
     * "Cvijanovic").
     */
    private static double wordSimilarity(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        if (shorter.length() >= 4
                && longer.length() - shorter.length() <= 2
                && longer.startsWith(shorter)) {
            return 1.0;
        }
        return JaroWinkler.similarity(a, b);
    }

    private boolean add(NewsMention mention) {
        String url = mention.article().url();
        if (byUrl.containsKey(url)) {
            return false;
        }
        Map<String, String> originals = new LinkedHashMap<>();
        for (String name : mention.persons()) {
            originals.putIfAbsent(
                    NameNormalizer.normalizeUncached(name, EntityType.INDIVIDUAL), name);
        }
        for (String name : mention.organisations()) {
            originals.putIfAbsent(
                    NameNormalizer.normalizeUncached(name, EntityType.ORGANIZATION), name);
        }
        originals.remove("");
        if (originals.isEmpty()) {
            return false;
        }
        byUrl.put(url, new Entry(mention, originals));
        for (String name : originals.keySet()) {
            urlsByName.computeIfAbsent(name, n -> new HashSet<>()).add(url);
            for (String block : blocks(name.split(" "))) {
                namesByBlock.computeIfAbsent(block, b -> new HashSet<>()).add(name);
            }
        }
        return true;
    }

    private int evictBefore(Instant cutoff) {
        int dropped = 0;
        Iterator<Map.Entry<String, Entry>> it = byUrl.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (!e.getValue().mention.article().seenAt().isBefore(cutoff)) {
                continue;
            }
            it.remove();
            dropped++;
            for (String name : e.getValue().originals.keySet()) {
                Set<String> urls = urlsByName.get(name);
                if (urls == null) {
                    continue;
                }
                urls.remove(e.getKey());
                if (urls.isEmpty()) {
                    urlsByName.remove(name);
                    for (String block : blocks(name.split(" "))) {
                        Set<String> names = namesByBlock.get(block);
                        if (names != null) {
                            names.remove(name);
                            if (names.isEmpty()) {
                                namesByBlock.remove(block);
                            }
                        }
                    }
                }
            }
        }
        return dropped;
    }

    /** The headline without a trailing " - Site name" or " | Site name", in lowercase. */
    private static String headline(String title) {
        String lower = title.toLowerCase(Locale.ROOT).strip();
        int cut = Math.max(lower.lastIndexOf(" - "), lower.lastIndexOf(" | "));
        return cut >= 20 ? lower.substring(0, cut) : lower;
    }

    /** Words of three letters or more, cut to their first four letters; all words if none. */
    private static Set<String> blocks(String[] words) {
        Set<String> blocks = new HashSet<>();
        for (String word : words) {
            if (word.length() >= 3) {
                blocks.add(word.substring(0, Math.min(BLOCK_LENGTH, word.length())));
            }
        }
        if (blocks.isEmpty()) {
            blocks.addAll(Arrays.asList(words));
        }
        return blocks;
    }

    private record Entry(NewsMention mention, Map<String, String> originals) {
        String original(String key) {
            return originals.getOrDefault(key, key);
        }
    }
}
