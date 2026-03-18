package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.algorithm.JaroWinkler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Match engine that uses Jaro-Winkler fuzzy string similarity.
 *
 * <p>Compares the screening query against each entity's primary name and all aliases, keeping the
 * best (highest) score per entity. Results below the request's threshold are discarded.
 */
public final class FuzzyMatchEngine implements MatchEngine {

    private static final Logger log = LoggerFactory.getLogger(FuzzyMatchEngine.class);
    private static final String ALGORITHM_NAME = "JARO_WINKLER";

    private final NormalizedNameCache nameCache;
    private final NgramIndex ngramIndex;

    /** Creates a fuzzy match engine with shared name cache and n-gram index. */
    public FuzzyMatchEngine(NormalizedNameCache nameCache, NgramIndex ngramIndex) {
        this.nameCache = nameCache;
        this.ngramIndex = ngramIndex;
    }

    /** Creates a fuzzy match engine with a shared name cache (no n-gram filtering). */
    public FuzzyMatchEngine(NormalizedNameCache nameCache) {
        this(nameCache, new NgramIndex());
    }

    /** Creates a fuzzy match engine with its own name cache and n-gram index. */
    public FuzzyMatchEngine() {
        this(new NormalizedNameCache());
    }

    @Override
    public List<MatchResult> screen(ScreeningRequest request, EntityIndex index) {
        nameCache.ensureBuilt(index);
        ngramIndex.ensureBuilt(index, nameCache);
        String normalizedQuery = NameNormalizer.normalize(request.name());
        Collection<SanctionedEntity> candidates =
                resolveCandidates(request, index, normalizedQuery);
        List<MatchResult> results = new ArrayList<>();

        for (SanctionedEntity entity : candidates) {
            if (shouldSkipEntity(request, entity)) {
                continue;
            }
            MatchResult result = scoreEntity(normalizedQuery, entity, request.threshold());
            if (result != null) {
                results.add(result);
            }
        }

        results.sort(null);
        log.debug(
                "Fuzzy match screening [query={}, candidates={}, matches={}]",
                request.name(),
                candidates.size(),
                results.size());
        return results;
    }

    private static boolean shouldSkipEntity(ScreeningRequest request, SanctionedEntity entity) {
        return request.entityType().isPresent()
                && request.entityType().get() != entity.entityType();
    }

    private MatchResult scoreEntity(
            String normalizedQuery, SanctionedEntity entity, double threshold) {
        NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);

        double bestScore =
                JaroWinkler.similarityWithThreshold(
                        normalizedQuery, cached.primaryName(), threshold);
        String bestField = "primaryName";

        if (bestScore < 1.0) {
            List<String> aliases = cached.aliases();
            for (int i = 0; i < aliases.size(); i++) {
                double aliasScore =
                        JaroWinkler.similarityWithThreshold(
                                normalizedQuery, aliases.get(i), threshold);
                if (aliasScore > bestScore) {
                    bestScore = aliasScore;
                    bestField = "alias[" + i + "]";
                    if (bestScore >= 1.0) break;
                }
            }
        }

        if (bestScore >= threshold) {
            return new MatchResult(entity, bestScore, bestField, ALGORITHM_NAME);
        }
        return null;
    }

    private Collection<SanctionedEntity> resolveCandidates(
            ScreeningRequest request, EntityIndex index, String normalizedQuery) {
        if (request.sources().isPresent()) {
            // Source-filtered queries are already narrow — no n-gram filtering needed
            return request.sources().get().stream()
                    .flatMap(source -> index.findBySource(source).stream())
                    .toList();
        }
        return ngramIndex.candidates(normalizedQuery);
    }
}
