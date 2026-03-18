package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.NameInfo;
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

            String primaryNormalized =
                    NameNormalizer.normalize(entity.primaryName().fullName());
            if (normalizedQuery.equals(primaryNormalized)) {
                results.add(
                        new MatchResult(entity, 1.0, "primaryName", ALGORITHM_NAME));
                continue;
            }

            List<NameInfo> aliases = entity.aliases();
            for (int i = 0; i < aliases.size(); i++) {
                String aliasNormalized =
                        NameNormalizer.normalize(aliases.get(i).fullName());
                if (normalizedQuery.equals(aliasNormalized)) {
                    results.add(
                            new MatchResult(
                                    entity,
                                    1.0,
                                    "alias[" + i + "]",
                                    ALGORITHM_NAME));
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
            ScreeningRequest request, EntityIndex index) {
        if (request.sources().isPresent()) {
            return request.sources().get().stream()
                    .flatMap(source -> index.findBySource(source).stream())
                    .toList();
        }
        return index.all();
    }
}
