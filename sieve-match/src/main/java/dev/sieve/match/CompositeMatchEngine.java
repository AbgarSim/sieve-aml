package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composite match engine that delegates to multiple underlying engines.
 *
 * <p>Runs all registered engines, deduplicates results by entity ID, and keeps the highest score
 * per entity. The final result list is sorted by score in descending order.
 */
public final class CompositeMatchEngine implements MatchEngine {

    private static final Logger log = LoggerFactory.getLogger(CompositeMatchEngine.class);

    private final List<MatchEngine> engines;

    /**
     * Creates a composite engine delegating to the given engines.
     *
     * @param engines the match engines to delegate to, must not be {@code null} or empty
     * @throws NullPointerException if {@code engines} is {@code null}
     * @throws IllegalArgumentException if {@code engines} is empty
     */
    public CompositeMatchEngine(List<MatchEngine> engines) {
        Objects.requireNonNull(engines, "engines must not be null");
        if (engines.isEmpty()) {
            throw new IllegalArgumentException("At least one MatchEngine is required");
        }
        this.engines = List.copyOf(engines);
    }

    @Override
    public List<MatchResult> screen(ScreeningRequest request, EntityIndex index) {
        // Fast path: single engine — no dedup needed
        if (engines.size() == 1) {
            return engines.getFirst().screen(request, index);
        }

        // Collect results from all engines
        List<MatchResult> firstResults = engines.getFirst().screen(request, index);
        HashMap<String, MatchResult> bestByEntityId = new HashMap<>(firstResults.size() * 2);
        for (MatchResult result : firstResults) {
            bestByEntityId.put(result.entity().id(), result);
        }

        for (int e = 1; e < engines.size(); e++) {
            List<MatchResult> engineResults = engines.get(e).screen(request, index);
            for (MatchResult result : engineResults) {
                String entityId = result.entity().id();
                MatchResult existing = bestByEntityId.get(entityId);
                if (existing == null || result.score() > existing.score()) {
                    bestByEntityId.put(entityId, result);
                }
            }
        }

        List<MatchResult> results = new ArrayList<>(bestByEntityId.values());
        results.sort(null);

        log.debug(
                "Composite match screening [query={}, engines={}, uniqueMatches={}]",
                request.name(),
                engines.size(),
                results.size());

        return results;
    }
}
