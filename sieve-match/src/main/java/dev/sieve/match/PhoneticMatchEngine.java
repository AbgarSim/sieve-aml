package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.algorithm.DoubleMetaphone;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Match engine that uses Double Metaphone phonetic encoding for name matching.
 *
 * <p>Catches transliteration variants that fuzzy string matching may miss, such as:
 *
 * <ul>
 *   <li>"Muammar Gaddafi" vs "Moammar Qadhafi"
 *   <li>"Osama" vs "Usama"
 *   <li>"Chechen" vs "Chechen"
 * </ul>
 *
 * <p>Compares phonetic codes of the query against phonetic codes of each entity's names. If any
 * code combination matches, the entity is returned with a fixed score of 0.95 (phonetic matches are
 * high-confidence but not exact). Matches that cover only part of a name, such as a lone first
 * name, are discounted by {@link PartialNameMatch} and dropped when they fall below the request
 * threshold.
 *
 * <p>This engine is designed to be used alongside {@link FuzzyMatchEngine} inside a {@link
 * CompositeMatchEngine}, where the highest score per entity wins.
 */
public final class PhoneticMatchEngine implements MatchEngine {

    private static final Logger log = LoggerFactory.getLogger(PhoneticMatchEngine.class);
    private static final String ALGORITHM_NAME = "DOUBLE_METAPHONE";

    /**
     * Score assigned to phonetic matches. Set below 1.0 (exact) but above typical fuzzy thresholds
     * to reflect high confidence.
     */
    private static final double PHONETIC_MATCH_SCORE = 0.95;

    /** Score assigned to phonetic matches that cover only part of a name. */
    private static final double PARTIAL_MATCH_SCORE =
            PartialNameMatch.discount(PHONETIC_MATCH_SCORE);

    private final NormalizedNameCache nameCache;
    private final NgramIndex ngramIndex;

    /** Creates a phonetic match engine with shared name cache and n-gram index. */
    public PhoneticMatchEngine(NormalizedNameCache nameCache, NgramIndex ngramIndex) {
        this.nameCache = nameCache;
        this.ngramIndex = ngramIndex;
    }

    /** Creates a phonetic match engine with a shared name cache (no n-gram filtering). */
    public PhoneticMatchEngine(NormalizedNameCache nameCache) {
        this(nameCache, new NgramIndex());
    }

    /** Creates a phonetic match engine with its own name cache and n-gram index. */
    public PhoneticMatchEngine() {
        this(new NormalizedNameCache());
    }

    @Override
    public List<MatchResult> screen(ScreeningRequest request, EntityIndex index) {
        nameCache.ensureBuilt(index);
        ngramIndex.ensureBuilt(index, nameCache);

        String normalizedQuery =
                NameNormalizer.normalizeQuery(request.name(), request.entityType());
        String[] queryTokens = normalizedQuery.split("\\s+");
        DoubleMetaphone.PhoneticCode[] queryCodes = encodeTokens(queryTokens);
        int queryTokenCount = PartialNameMatch.tokenCount(normalizedQuery);

        Collection<SanctionedEntity> entities = resolveEntities(request, index);
        List<MatchResult> results = new ArrayList<>();

        for (SanctionedEntity entity : entities) {
            if (request.entityType().isPresent()
                    && !request.entityType().get().isCompatibleWith(entity.entityType())) {
                continue;
            }

            MatchResult result = findPhoneticMatch(entity, queryCodes, queryTokenCount);
            if (result != null && result.score() >= request.threshold()) {
                results.add(result);
            }
        }

        results.sort(null);
        log.debug(
                "Phonetic match screening [query={}, entities={}, matches={}]",
                request.name(),
                entities.size(),
                results.size());
        return results;
    }

    /**
     * Finds the best phonetic match between the query codes and the entity's names.
     *
     * <p>A full-name match scores {@link #PHONETIC_MATCH_SCORE}; a match that covers only part of a
     * name (a single name component, or a single-token query against a longer name) is discounted
     * by {@link PartialNameMatch}.
     *
     * @return the best match, or {@code null} if no name matches
     */
    private MatchResult findPhoneticMatch(
            SanctionedEntity entity,
            DoubleMetaphone.PhoneticCode[] queryCodes,
            int queryTokenCount) {
        NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);
        double bestScore = 0.0;
        String bestField = null;

        List<String> fullNames = new ArrayList<>(cached.aliases().size() + 1);
        fullNames.add(cached.primaryName());
        fullNames.addAll(cached.aliases());
        for (int i = 0; i < fullNames.size() && bestScore < PHONETIC_MATCH_SCORE; i++) {
            String name = fullNames.get(i);
            if (matchesTokens(name, queryCodes)) {
                double score =
                        PartialNameMatch.isLoneToken(queryTokenCount, name)
                                ? PARTIAL_MATCH_SCORE
                                : PHONETIC_MATCH_SCORE;
                if (score > bestScore) {
                    bestScore = score;
                    bestField = i == 0 ? "primaryName" : "alias[" + (i - 1) + "]";
                }
            }
        }

        List<String> components = cached.nameComponents();
        for (int i = 0; i < components.size() && bestScore < PARTIAL_MATCH_SCORE; i++) {
            if (matchesTokens(components.get(i), queryCodes)) {
                bestScore = PARTIAL_MATCH_SCORE;
                bestField = "nameComponent[" + i + "]";
            }
        }

        return bestField == null
                ? null
                : new MatchResult(entity, bestScore, bestField, ALGORITHM_NAME);
    }

    /**
     * Checks if the phonetic codes of the candidate's tokens match the query codes.
     *
     * <p>A match requires that every query token has a phonetic match in the candidate tokens. This
     * handles multi-word names where each word must phonetically match some word in the candidate.
     */
    private static boolean matchesTokens(
            String candidateName, DoubleMetaphone.PhoneticCode[] queryCodes) {
        String[] candidateTokens = candidateName.split("\\s+");
        DoubleMetaphone.PhoneticCode[] candidateCodes = encodeTokens(candidateTokens);

        if (queryCodes.length == 0 || candidateCodes.length == 0) {
            return false;
        }

        // Single-token query: match against any candidate token
        if (queryCodes.length == 1) {
            for (DoubleMetaphone.PhoneticCode cc : candidateCodes) {
                if (codesMatch(queryCodes[0], cc)) {
                    return true;
                }
            }
            return false;
        }

        // Multi-token query: each query token must match some candidate token
        int matched = 0;
        boolean[] used = new boolean[candidateCodes.length];
        for (DoubleMetaphone.PhoneticCode queryCode : queryCodes) {
            for (int j = 0; j < candidateCodes.length; j++) {
                if (!used[j] && codesMatch(queryCode, candidateCodes[j])) {
                    used[j] = true;
                    matched++;
                    break;
                }
            }
        }
        return matched == queryCodes.length;
    }

    private static DoubleMetaphone.PhoneticCode[] encodeTokens(String[] tokens) {
        DoubleMetaphone.PhoneticCode[] codes = new DoubleMetaphone.PhoneticCode[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            codes[i] = DoubleMetaphone.encode(tokens[i]);
        }
        return codes;
    }

    private static boolean codesMatch(
            DoubleMetaphone.PhoneticCode a, DoubleMetaphone.PhoneticCode b) {
        if (a.primary().isEmpty() || b.primary().isEmpty()) {
            return false;
        }
        return a.primary().equals(b.primary())
                || a.primary().equals(b.alternate())
                || a.alternate().equals(b.primary())
                || a.alternate().equals(b.alternate());
    }

    private Collection<SanctionedEntity> resolveEntities(
            ScreeningRequest request, EntityIndex index) {
        if (request.sources().isPresent()) {
            return request.sources().get().stream()
                    .flatMap(source -> index.findBySource(source).stream())
                    .toList();
        }
        // Phonetic matching needs all entities — n-gram filtering could miss phonetic variants
        // since trigrams don't correlate with phonetic codes.
        return index.all();
    }
}
