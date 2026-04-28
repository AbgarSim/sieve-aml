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
 * Match engine that performs token-based name comparison, handling name reordering and partial
 * token overlap.
 *
 * <p>Sanctions lists frequently use different name orderings than the query:
 *
 * <ul>
 *   <li>"John Smith" vs "SMITH, John" — reversed order
 *   <li>"Vladimir Putin" vs "PUTIN, Vladimir Vladimirovich" — partial overlap with extra tokens
 *   <li>"Kim Jong Un" vs "KIM, Jong-un" — partial with hyphenation
 * </ul>
 *
 * <p>This engine tokenizes both the query and candidate names, then computes a score based on the
 * best fuzzy match for each query token against any candidate token, regardless of order. The final
 * score is the weighted average of the best per-token matches.
 *
 * <p>Designed to be used alongside {@link ExactMatchEngine} and {@link FuzzyMatchEngine} inside a
 * {@link CompositeMatchEngine}.
 */
public final class TokenMatchEngine implements MatchEngine {

    private static final Logger log = LoggerFactory.getLogger(TokenMatchEngine.class);
    private static final String ALGORITHM_NAME = "TOKEN_SET";

    /**
     * Minimum individual token similarity to count as a token match. Lower than the overall
     * threshold to allow for transliteration variants within tokens.
     */
    private static final double TOKEN_MATCH_FLOOR = 0.75;

    private final NormalizedNameCache nameCache;
    private final NgramIndex ngramIndex;

    /** Creates a token match engine with shared name cache and n-gram index. */
    public TokenMatchEngine(NormalizedNameCache nameCache, NgramIndex ngramIndex) {
        this.nameCache = nameCache;
        this.ngramIndex = ngramIndex;
    }

    /** Creates a token match engine with a shared name cache (no n-gram filtering). */
    public TokenMatchEngine(NormalizedNameCache nameCache) {
        this(nameCache, new NgramIndex());
    }

    /** Creates a token match engine with its own name cache and n-gram index. */
    public TokenMatchEngine() {
        this(new NormalizedNameCache());
    }

    @Override
    public List<MatchResult> screen(ScreeningRequest request, EntityIndex index) {
        nameCache.ensureBuilt(index);
        ngramIndex.ensureBuilt(index, nameCache);

        String normalizedQuery = NameNormalizer.normalize(request.name());
        String[] queryTokens = tokenize(normalizedQuery);

        if (queryTokens.length < 2) {
            // Single-token queries are already well-served by Exact/Fuzzy engines
            return List.of();
        }

        Collection<SanctionedEntity> candidates =
                resolveCandidates(request, index, normalizedQuery);
        List<MatchResult> results = new ArrayList<>();

        for (SanctionedEntity entity : candidates) {
            if (request.entityType().isPresent()
                    && request.entityType().get() != entity.entityType()) {
                continue;
            }

            MatchResult result = scoreEntity(queryTokens, entity, request.threshold());
            if (result != null) {
                results.add(result);
            }
        }

        results.sort(null);
        log.debug(
                "Token match screening [query={}, candidates={}, matches={}]",
                request.name(),
                candidates.size(),
                results.size());
        return results;
    }

    private MatchResult scoreEntity(
            String[] queryTokens, SanctionedEntity entity, double threshold) {
        NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);

        double bestScore = tokenSetScore(queryTokens, tokenize(cached.primaryName()));
        String bestField = "primaryName";

        if (bestScore < 1.0) {
            List<String> aliases = cached.aliases();
            for (int i = 0; i < aliases.size(); i++) {
                double aliasScore = tokenSetScore(queryTokens, tokenize(aliases.get(i)));
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

    /**
     * Computes a token-set similarity score between query tokens and candidate tokens.
     *
     * <p>For each query token, finds the best fuzzy match among all candidate tokens (regardless of
     * position). The final score is the average of the best per-token matches, weighted by:
     *
     * <ul>
     *   <li>The proportion of query tokens matched (coverage penalty for unmatched tokens)
     *   <li>An overlap bonus when most tokens are matched
     * </ul>
     */
    static double tokenSetScore(String[] queryTokens, String[] candidateTokens) {
        if (queryTokens.length == 0 || candidateTokens.length == 0) {
            return 0.0;
        }

        double totalBestScore = 0.0;
        int matchedTokens = 0;
        boolean[] used = new boolean[candidateTokens.length];

        for (String queryToken : queryTokens) {
            double bestTokenScore = 0.0;
            int bestIndex = -1;

            for (int j = 0; j < candidateTokens.length; j++) {
                if (used[j]) continue;
                double sim = JaroWinkler.similarity(queryToken, candidateTokens[j]);
                if (sim > bestTokenScore) {
                    bestTokenScore = sim;
                    bestIndex = j;
                }
            }

            if (bestTokenScore >= TOKEN_MATCH_FLOOR && bestIndex >= 0) {
                used[bestIndex] = true;
                matchedTokens++;
                totalBestScore += bestTokenScore;
            }
        }

        if (matchedTokens == 0) {
            return 0.0;
        }

        // Average score of matched tokens
        double avgScore = totalBestScore / queryTokens.length;

        // Coverage factor: penalize when query tokens are not fully matched
        double coverage = (double) matchedTokens / queryTokens.length;

        return avgScore * coverage;
    }

    /**
     * Tokenizes a normalized name into individual words, stripping commas and short noise tokens.
     */
    static String[] tokenize(String name) {
        if (name == null || name.isEmpty()) {
            return new String[0];
        }
        // Remove commas, hyphens → spaces, then split
        String cleaned = name.replace(',', ' ').replace('-', ' ');
        String[] raw = cleaned.split("\\s+");

        // Filter out empty tokens and very short noise (single chars that aren't initials)
        List<String> tokens = new ArrayList<>(raw.length);
        for (String token : raw) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens.toArray(String[]::new);
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
