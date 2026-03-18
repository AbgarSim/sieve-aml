package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.NameInfo;
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

    @Override
    public List<MatchResult> screen(ScreeningRequest request, EntityIndex index) {
        String normalizedQuery = NameNormalizer.normalize(request.name());
        Collection<SanctionedEntity> candidates = resolveCandidates(request, index);
        List<MatchResult> results = new ArrayList<>();

        for (SanctionedEntity entity : candidates) {
            if (request.entityType().isPresent()
                    && request.entityType().get() != entity.entityType()) {
                continue;
            }

            double bestScore = 0.0;
            String bestField = "primaryName";

            String primaryNormalized =
                    NameNormalizer.normalize(entity.primaryName().fullName());
            double primaryScore = JaroWinkler.similarity(normalizedQuery, primaryNormalized);
            if (primaryScore > bestScore) {
                bestScore = primaryScore;
                bestField = "primaryName";
            }

            List<NameInfo> aliases = entity.aliases();
            for (int i = 0; i < aliases.size(); i++) {
                String aliasNormalized =
                        NameNormalizer.normalize(aliases.get(i).fullName());
                double aliasScore = JaroWinkler.similarity(normalizedQuery, aliasNormalized);
                if (aliasScore > bestScore) {
                    bestScore = aliasScore;
                    bestField = "alias[" + i + "]";
                }
            }

            if (bestScore >= request.threshold()) {
                results.add(
                        new MatchResult(entity, bestScore, bestField, ALGORITHM_NAME));
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

    private Collection<SanctionedEntity> resolveCandidates(
            ScreeningRequest request, EntityIndex index) {
        if (request.sources().isPresent()) {
            return request.sources().get().stream()
                    .flatMap(source -> index.findBySource(source).stream())
                    .toList();
        }
        return index.all();
    }
}
