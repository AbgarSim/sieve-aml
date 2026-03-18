package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.SanctionedEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Match engine that performs exact (post-normalization) name comparison.
 *
 * <p>Names are normalized by lowercasing, trimming, and collapsing whitespace before comparison.
 * Checks the entity's primary name and all aliases. Produces a score of 1.0 for exact matches and
 * 0.0 otherwise.
 */
public final class ExactMatchEngine implements MatchEngine {

    private static final Logger log = LoggerFactory.getLogger(ExactMatchEngine.class);
    private static final String ALGORITHM_NAME = "EXACT";

    private final NormalizedNameCache nameCache;
    private final NgramIndex ngramIndex;

    /** Creates an exact match engine with shared name cache and n-gram index. */
    public ExactMatchEngine(NormalizedNameCache nameCache, NgramIndex ngramIndex) {
        this.nameCache = nameCache;
        this.ngramIndex = ngramIndex;
    }

    /** Creates an exact match engine with a shared name cache (no n-gram filtering). */
    public ExactMatchEngine(NormalizedNameCache nameCache) {
        this(nameCache, new NgramIndex());
    }

    /** Creates an exact match engine with its own name cache and n-gram index. */
    public ExactMatchEngine() {
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
            if (request.entityType().isPresent()
                    && request.entityType().get() != entity.entityType()) {
                continue;
            }

            NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);

            if (normalizedQuery.equals(cached.primaryName())) {
                results.add(new MatchResult(entity, 1.0, "primaryName", ALGORITHM_NAME));
                continue;
            }

            List<String> aliases = cached.aliases();
            for (int i = 0; i < aliases.size(); i++) {
                if (normalizedQuery.equals(aliases.get(i))) {
                    results.add(new MatchResult(entity, 1.0, "alias[" + i + "]", ALGORITHM_NAME));
                    break;
                }
            }
        }

        results.sort(null);
        log.debug(
                "Exact match screening [query={}, candidates={}, matches={}]",
                request.name(),
                candidates.size(),
                results.size());
        return results;
    }

    private Collection<SanctionedEntity> resolveCandidates(
            ScreeningRequest request, EntityIndex index, String normalizedQuery) {
        if (request.sources().isPresent()) {
            return request.sources().get().stream()
                    .flatMap(source -> index.findBySource(source).stream())
                    .toList();
        }
        return ngramIndex.candidates(normalizedQuery);
    }
}
