package dev.sieve.core.match;

import dev.sieve.core.dedup.CanonicalEntity;
import dev.sieve.core.dedup.DeduplicationIndex;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.SanctionedEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A match engine decorator that collapses duplicate results using a {@link DeduplicationIndex}.
 *
 * <p>Wraps any {@link MatchEngine} and post-processes its results: when multiple source entities
 * belong to the same {@link CanonicalEntity}, only the highest-scoring match is retained. The
 * response includes a reference to the canonical entity so callers can access all source listings.
 *
 * <p>When the deduplication index is not populated (e.g., dedup has not been run yet), this engine
 * delegates directly to the wrapped engine without any collapsing.
 */
public final class DeduplicatingMatchEngine implements MatchEngine {

    private static final Logger log = LoggerFactory.getLogger(DeduplicatingMatchEngine.class);

    private final MatchEngine delegate;
    private final DeduplicationIndex dedupIndex;

    /**
     * Creates a deduplicating match engine.
     *
     * @param delegate the underlying match engine to wrap
     * @param dedupIndex the deduplication index for canonical entity lookups
     */
    public DeduplicatingMatchEngine(MatchEngine delegate, DeduplicationIndex dedupIndex) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.dedupIndex = Objects.requireNonNull(dedupIndex, "dedupIndex must not be null");
    }

    @Override
    public List<MatchResult> screen(ScreeningRequest request, EntityIndex index) {
        List<MatchResult> rawResults = delegate.screen(request, index);

        if (!dedupIndex.isPopulated()) {
            return rawResults;
        }

        // Group by canonical ID, keeping only the best-scoring result per canonical entity
        Map<String, MatchResult> bestByCanonical = new LinkedHashMap<>();
        List<MatchResult> unmapped = new ArrayList<>();

        for (MatchResult result : rawResults) {
            SanctionedEntity entity = result.entity();
            Optional<String> canonicalId = dedupIndex.canonicalIdOf(entity.id());

            if (canonicalId.isPresent()) {
                String cid = canonicalId.get();
                MatchResult existing = bestByCanonical.get(cid);
                if (existing == null || result.score() > existing.score()) {
                    bestByCanonical.put(cid, result);
                }
            } else {
                // Entity not in dedup index — pass through as-is
                unmapped.add(result);
            }
        }

        List<MatchResult> collapsed = new ArrayList<>(bestByCanonical.size() + unmapped.size());
        collapsed.addAll(bestByCanonical.values());
        collapsed.addAll(unmapped);
        collapsed.sort(null);

        if (log.isDebugEnabled()) {
            int eliminated = rawResults.size() - collapsed.size();
            if (eliminated > 0) {
                log.debug(
                        "Dedup collapsed screening results [raw={}, collapsed={}, eliminated={}]",
                        rawResults.size(),
                        collapsed.size(),
                        eliminated);
            }
        }

        return collapsed;
    }

    /**
     * Returns the underlying deduplication index.
     *
     * @return the dedup index, never {@code null}
     */
    public DeduplicationIndex dedupIndex() {
        return dedupIndex;
    }
}
