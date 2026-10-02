package dev.sieve.match.dedup;

import dev.sieve.core.dedup.CanonicalEntity;
import dev.sieve.core.dedup.DeduplicationConfig;
import dev.sieve.core.dedup.DeduplicationResult;
import dev.sieve.core.dedup.EntityDeduplicator;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.NameNormalizer;
import dev.sieve.match.algorithm.JaroWinkler;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity deduplicator that uses multi-signal similarity to identify duplicate entities across
 * different sanctions lists.
 *
 * <p>Inspired by the nomenklatura framework used by OpenSanctions, this implementation merges
 * entities based on:
 *
 * <ul>
 *   <li><b>Name similarity</b> — Jaro-Winkler matching across primary names and aliases, after
 *       folding diacritics and punctuation, both as written and token-sorted (so "DOE, John" equals
 *       "John Doe")
 *   <li><b>Identifier overlap</b> — same type and value ignoring formatting, with no conflicting
 *       issuing country
 *   <li><b>Date of birth</b> — a shared DOB adds evidence; DOBs with no year in common are proof of
 *       different people and veto a name-only merge
 * </ul>
 *
 * <p>Entities within the same source list are never merged, directly or transitively: they are
 * considered distinct by the issuing authority.
 *
 * <h3>Algorithm</h3>
 *
 * <ol>
 *   <li><b>Blocking</b> — entities are grouped by (entity type, normalized name prefix) to avoid
 *       O(n²) pairwise comparisons
 *   <li><b>Pairwise scoring</b> — candidate pairs from different list sources are scored using a
 *       weighted combination of name similarity, identifier overlap, and DOB evidence
 *   <li><b>Constrained clustering</b> — pairs are merged strongest first with Union-Find. A merge
 *       that would put two entities from one list, or provably different DOBs, into one cluster is
 *       refused, so a loose transitive chain (A≈B, B≈C) cannot join two distinct people
 *   <li><b>Canonical creation</b> — each cluster is merged into a single {@link CanonicalEntity}
 *       with combined metadata
 * </ol>
 */
public final class SimilarityDeduplicator implements EntityDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(SimilarityDeduplicator.class);

    private final DeduplicationConfig config;

    /**
     * Creates a deduplicator with the given configuration.
     *
     * @param config the deduplication configuration
     */
    public SimilarityDeduplicator(DeduplicationConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /** Creates a deduplicator with default configuration. */
    public SimilarityDeduplicator() {
        this(DeduplicationConfig.DEFAULT);
    }

    @Override
    public DeduplicationResult deduplicate(Collection<SanctionedEntity> entities) {
        Objects.requireNonNull(entities, "entities must not be null");
        Instant start = Instant.now();

        if (entities.isEmpty()) {
            return new DeduplicationResult(Map.of(), Map.of(), 0, 0, 0, Duration.ZERO);
        }

        List<SanctionedEntity> entityList = List.copyOf(entities);
        log.info("Starting entity deduplication [entities={}]", entityList.size());

        // Clustering addresses entities by position, so two lists reusing one ID can't be fused
        List<NameProfile> profiles = new ArrayList<>(entityList.size());
        for (SanctionedEntity entity : entityList) {
            profiles.add(NameProfile.of(entity));
        }

        // Phase 1: Blocking + pairwise scoring, collecting every candidate pair above threshold
        Map<EntityType, List<Integer>> byType = groupByType(entityList);
        Set<Long> seenPairs = new HashSet<>();
        List<CandidatePair> candidates = new ArrayList<>();
        int pairsCompared = 0;

        for (Map.Entry<EntityType, List<Integer>> entry : byType.entrySet()) {
            Map<String, List<Integer>> blocks = buildBlocks(entry.getValue(), profiles);
            log.debug(
                    "Processing entity type [type={}, entities={}, blocks={}]",
                    entry.getKey(),
                    entry.getValue().size(),
                    blocks.size());

            for (List<Integer> block : blocks.values()) {
                for (int x = 0; x < block.size(); x++) {
                    int i = block.get(x);
                    for (int y = x + 1; y < block.size(); y++) {
                        int j = block.get(y);
                        SanctionedEntity a = entityList.get(i);
                        SanctionedEntity b = entityList.get(j);
                        // Same-source entities are distinct by the issuing authority
                        if (a.listSource() == b.listSource()) {
                            continue;
                        }
                        // An entity can share several blocks with another — score each pair once
                        if (!seenPairs.add(pairKey(i, j))) {
                            continue;
                        }
                        pairsCompared++;
                        PairEvidence evidence = evaluate(a, profiles.get(i), b, profiles.get(j));
                        if (evidence.rawScore() >= config.mergeThreshold()) {
                            candidates.add(
                                    new CandidatePair(
                                            Math.min(i, j),
                                            Math.max(i, j),
                                            evidence.rawScore(),
                                            evidence.identifierMatch()));
                        }
                    }
                }
            }
        }

        // Phase 2: Constrained clustering. Strongest evidence first, so an entity that resembles
        // several candidates joins the one it matches best; a union is refused when the merged
        // cluster would hold two entities from one list or provably different dates of birth.
        candidates.sort(CandidatePair.STRONGEST_FIRST);
        UnionFind<Integer> unionFind = new UnionFind<>();
        Map<Integer, ClusterState> clusterStates = new HashMap<>();
        for (int i = 0; i < entityList.size(); i++) {
            unionFind.makeSet(i);
            clusterStates.put(i, ClusterState.of(entityList.get(i)));
        }

        int rejectedUnions = 0;
        for (CandidatePair pair : candidates) {
            int rootA = unionFind.find(pair.first());
            int rootB = unionFind.find(pair.second());
            if (rootA == rootB) {
                continue;
            }
            ClusterState stateA = clusterStates.get(rootA);
            ClusterState stateB = clusterStates.get(rootB);
            if (!stateA.compatibleWith(stateB, pair.identifierMatch())) {
                rejectedUnions++;
                log.trace(
                        "Merge rejected by cluster constraints [a={}, b={}, score={}]",
                        entityList.get(pair.first()).id(),
                        entityList.get(pair.second()).id(),
                        pair.score());
                continue;
            }
            unionFind.union(rootA, rootB);
            int newRoot = unionFind.find(rootA);
            clusterStates.remove(rootA);
            clusterStates.remove(rootB);
            clusterStates.put(newRoot, stateA.mergedWith(stateB));
        }

        // Phase 3: Build canonical entities from clusters, in input order
        Map<String, CanonicalEntity> canonicalEntities = new LinkedHashMap<>();
        Map<String, String> entityToCanonicalId = new HashMap<>();
        int mergedGroups = 0;
        int canonicalCounter = 0;

        for (List<Integer> memberIndexes : unionFind.clusters().values()) {
            List<SanctionedEntity> members = memberIndexes.stream().map(entityList::get).toList();

            String canonicalId = "canonical-" + canonicalCounter++;
            canonicalEntities.put(canonicalId, CanonicalEntity.merge(canonicalId, members));

            for (SanctionedEntity member : members) {
                entityToCanonicalId.put(member.id(), canonicalId);
            }
            if (members.size() > 1) {
                mergedGroups++;
            }
        }

        Duration duration = Duration.between(start, Instant.now());
        DeduplicationResult result =
                new DeduplicationResult(
                        canonicalEntities,
                        entityToCanonicalId,
                        entityList.size(),
                        canonicalEntities.size(),
                        mergedGroups,
                        duration);

        log.info(
                "Deduplication complete [source={}, canonical={}, merged={}, eliminated={}, "
                        + "pairsCompared={}, candidatePairs={}, rejectedUnions={}, duration={}ms]",
                result.totalSourceEntities(),
                result.totalCanonicalEntities(),
                result.mergedGroups(),
                result.duplicatesEliminated(),
                pairsCompared,
                candidates.size(),
                rejectedUnions,
                duration.toMillis());

        return result;
    }

    private static Map<EntityType, List<Integer>> groupByType(List<SanctionedEntity> entities) {
        Map<EntityType, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            groups.computeIfAbsent(entities.get(i).entityType(), k -> new ArrayList<>()).add(i);
        }
        return groups;
    }

    /**
     * Builds blocking groups keyed by name prefixes. Each entity is placed in one block per
     * distinct prefix of its name variants (as written and token-sorted) and of its family name, so
     * reordered and alias spellings still meet in at least one block.
     */
    private Map<String, List<Integer>> buildBlocks(
            List<Integer> indexes, List<NameProfile> profiles) {
        Map<String, List<Integer>> blocks = new HashMap<>();
        int prefixLen = config.blockingPrefixLength();

        for (int index : indexes) {
            Set<String> keys = new HashSet<>();
            for (String name : profiles.get(index).blockingNames()) {
                keys.add(blockKey(name, prefixLen));
            }
            String family = profiles.get(index).familyName();
            if (!family.isEmpty()) {
                keys.add(blockKey(family, prefixLen));
            }
            for (String key : keys) {
                if (!key.isBlank()) {
                    blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
                }
            }
        }
        return blocks;
    }

    private static String blockKey(String normalized, int prefixLen) {
        if (normalized.length() <= prefixLen) {
            return normalized;
        }
        return normalized.substring(0, prefixLen);
    }

    private static long pairKey(int i, int j) {
        int lo = Math.min(i, j);
        int hi = Math.max(i, j);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    /**
     * Computes a composite similarity score between two entities using multiple signals.
     *
     * <p>Name similarity is the base score. A shared identifier or date of birth adds the
     * configured bonus. Dates of birth that provably differ veto the merge unless an identifier
     * corroborates it, and an identifier can only rescue a name match that is at least {@link
     * #MIN_NAME_SIMILARITY_WITH_IDENTIFIER}.
     *
     * @return composite score in [0.0, 1.0]
     */
    double compositeScore(SanctionedEntity a, SanctionedEntity b) {
        return Math.min(evaluate(a, NameProfile.of(a), b, NameProfile.of(b)).rawScore(), 1.0);
    }

    private PairEvidence evaluate(
            SanctionedEntity a, NameProfile profileA, SanctionedEntity b, NameProfile profileB) {
        double nameSim = bestNameSimilarity(profileA, profileB);
        boolean identifierMatch = hasMatchingIdentifier(a, b);
        DobEvidence dobEvidence = DobEvidence.between(a.datesOfBirth(), b.datesOfBirth());

        if (nameSim < config.nameThreshold()
                && !(identifierMatch && nameSim >= MIN_NAME_SIMILARITY_WITH_IDENTIFIER)) {
            return PairEvidence.NONE;
        }
        if (dobEvidence == DobEvidence.CONFLICT && !identifierMatch) {
            return PairEvidence.NONE;
        }

        double score = nameSim;
        if (identifierMatch) {
            score += config.identifierMatchWeight();
        }
        if (dobEvidence == DobEvidence.EXACT) {
            score += config.dobMatchWeight();
        }
        return new PairEvidence(score, identifierMatch);
    }

    /**
     * Outcome of scoring one pair. The raw score is not clamped to 1.0, so an exact name match that
     * is also backed by a DOB outranks a bare exact name match during clustering.
     */
    private record PairEvidence(double rawScore, boolean identifierMatch) {
        static final PairEvidence NONE = new PairEvidence(0.0, false);
    }

    /** Best token-aligned similarity across all names of two entities. */
    private static double bestNameSimilarity(NameProfile a, NameProfile b) {
        double best = 0.0;
        for (String[] nameA : a.tokenizedNames()) {
            for (String[] nameB : b.tokenizedNames()) {
                double sim = tokenAlignedSimilarity(nameA, nameB);
                if (sim > best) {
                    best = sim;
                    if (best >= 1.0) {
                        return 1.0;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Similarity of two tokenized names, independent of token order.
     *
     * <p>Tokens are paired greedily, most similar pair first, each token used at most once. The
     * Jaro-Winkler scores of the pairs are averaged weighted by the lengths of both tokens, and
     * tokens left without a partner count as zero, so a missing or extra name part lowers the
     * score. Whole-string Jaro-Winkler is not used: its prefix bonus rates "doe john" vs "doe jane"
     * at 0.90, which would merge two different people sharing a surname.
     */
    static double tokenAlignedSimilarity(String[] tokensA, String[] tokensB) {
        if (tokensA.length == 0 || tokensB.length == 0) {
            return 0.0;
        }
        double[][] sims = new double[tokensA.length][tokensB.length];
        int totalLength = 0;
        for (int i = 0; i < tokensA.length; i++) {
            totalLength += tokensA[i].length();
            for (int k = 0; k < tokensB.length; k++) {
                sims[i][k] = tokenSimilarity(tokensA[i], tokensB[k]);
            }
        }
        for (String token : tokensB) {
            totalLength += token.length();
        }

        boolean[] usedA = new boolean[tokensA.length];
        boolean[] usedB = new boolean[tokensB.length];
        double weightedSum = 0.0;
        for (int pairs = Math.min(tokensA.length, tokensB.length); pairs > 0; pairs--) {
            int bestI = -1;
            int bestK = -1;
            for (int i = 0; i < tokensA.length; i++) {
                if (usedA[i]) {
                    continue;
                }
                for (int k = 0; k < tokensB.length; k++) {
                    if (!usedB[k] && (bestI < 0 || sims[i][k] > sims[bestI][bestK])) {
                        bestI = i;
                        bestK = k;
                    }
                }
            }
            usedA[bestI] = true;
            usedB[bestK] = true;
            weightedSum += sims[bestI][bestK] * (tokensA[bestI].length() + tokensB[bestK].length());
        }
        return weightedSum / totalLength;
    }

    /** Jaro-Winkler between tokens; an initial only half-matches a name with that first letter. */
    private static double tokenSimilarity(String a, String b) {
        if (a.length() == 1 || b.length() == 1) {
            return a.charAt(0) == b.charAt(0) ? INITIAL_MATCH_SIMILARITY : 0.0;
        }
        return JaroWinkler.similarity(a, b);
    }

    /**
     * Checks whether two entities share an identifier: same type, same value ignoring case and
     * formatting, and no conflicting issuing country when both name one.
     */
    private static boolean hasMatchingIdentifier(SanctionedEntity a, SanctionedEntity b) {
        for (Identifier idA : a.identifiers()) {
            String valueA = normalizeIdentifierValue(idA.value());
            if (valueA.isEmpty()) {
                continue;
            }
            for (Identifier idB : b.identifiers()) {
                if (idA.type() == idB.type()
                        && valueA.equals(normalizeIdentifierValue(idB.value()))
                        && compatibleCountries(idA.issuingCountry(), idB.issuingCountry())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizeIdentifierValue(String value) {
        return NON_ALPHANUMERIC.matcher(value).replaceAll("").toUpperCase(Locale.ROOT);
    }

    private static boolean compatibleCountries(String countryA, String countryB) {
        if (countryA == null || countryA.isBlank() || countryB == null || countryB.isBlank()) {
            return true;
        }
        return countryA.strip().equalsIgnoreCase(countryB.strip());
    }

    /**
     * Normalizes a name for deduplication: folds diacritics, lowercases, and turns punctuation into
     * word breaks, so "MÜLLER-García, José" and "Muller Garcia Jose" compare token for token.
     */
    static String normalizeForDedup(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String folded =
                COMBINING_MARKS
                        .matcher(Normalizer.normalize(name, Normalizer.Form.NFD))
                        .replaceAll("");
        String cleaned =
                NON_LETTER_OR_DIGIT.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll(" ");
        return NameNormalizer.normalize(cleaned);
    }

    private static String sortTokens(String normalized) {
        String[] tokens = normalized.split(" ");
        Arrays.sort(tokens);
        return String.join(" ", tokens);
    }

    /** Minimum name similarity for a shared identifier to stand in for a strong name match. */
    static final double MIN_NAME_SIMILARITY_WITH_IDENTIFIER = 0.75;

    /** Similarity of an initial ("j") to a full name token with the same first letter. */
    private static final double INITIAL_MATCH_SIMILARITY = 0.5;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]");

    /**
     * All comparable name forms of one entity, computed once per deduplication run.
     *
     * @param tokenizedNames each distinct normalized name, split into tokens
     * @param blockingNames each name as written and token-sorted, used only to pick blocks
     * @param familyName normalized primary family name, or empty
     */
    private record NameProfile(
            List<String[]> tokenizedNames, List<String> blockingNames, String familyName) {

        static NameProfile of(SanctionedEntity entity) {
            Set<String> names = new LinkedHashSet<>();
            NameInfo primary = entity.primaryName();
            addName(names, primary.fullName());
            if (primary.givenName() != null && primary.familyName() != null) {
                addName(names, primary.givenName() + " " + primary.familyName());
            }
            for (NameInfo alias : entity.aliases()) {
                addName(names, alias.fullName());
            }

            List<String[]> tokenized = new ArrayList<>(names.size());
            Set<String> blocking = new LinkedHashSet<>();
            for (String name : names) {
                tokenized.add(name.split(" "));
                blocking.add(name);
                blocking.add(sortTokens(name));
            }
            return new NameProfile(
                    List.copyOf(tokenized),
                    List.copyOf(blocking),
                    normalizeForDedup(primary.familyName()));
        }

        private static void addName(Set<String> names, String name) {
            String normalized = normalizeForDedup(name);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
    }

    /** A pair of entity positions whose composite score reached the merge threshold. */
    private record CandidatePair(int first, int second, double score, boolean identifierMatch) {

        static final Comparator<CandidatePair> STRONGEST_FIRST =
                Comparator.comparingDouble(CandidatePair::score)
                        .reversed()
                        .thenComparingInt(CandidatePair::first)
                        .thenComparingInt(CandidatePair::second);
    }

    /** What date-of-birth data says about two entities being the same person. */
    private enum DobEvidence {
        /** At least one side has no date of birth. */
        UNKNOWN,
        /** A date of birth appears on both sides. */
        EXACT,
        /** No identical date, but a shared year (year-only DOBs are stored as 1 January). */
        SAME_YEAR,
        /** Both sides have dates of birth and no year in common. */
        CONFLICT;

        static DobEvidence between(Collection<LocalDate> a, Collection<LocalDate> b) {
            if (a.isEmpty() || b.isEmpty()) {
                return UNKNOWN;
            }
            boolean sameYear = false;
            for (LocalDate dobA : a) {
                for (LocalDate dobB : b) {
                    if (dobA.equals(dobB)) {
                        return EXACT;
                    }
                    sameYear |= dobA.getYear() == dobB.getYear();
                }
            }
            return sameYear ? SAME_YEAR : CONFLICT;
        }
    }

    /** Facts about a cluster that every future union must respect. */
    private record ClusterState(Set<ListSource> sources, Set<LocalDate> datesOfBirth) {

        static ClusterState of(SanctionedEntity entity) {
            return new ClusterState(
                    EnumSet.of(entity.listSource()), new HashSet<>(entity.datesOfBirth()));
        }

        /**
         * A shared identifier outweighs conflicting dates of birth (lists often disagree on DOB),
         * but nothing allows one cluster to hold two entities from the same list.
         */
        boolean compatibleWith(ClusterState other, boolean identifierMatch) {
            if (!Collections.disjoint(sources, other.sources)) {
                return false;
            }
            return identifierMatch
                    || DobEvidence.between(datesOfBirth, other.datesOfBirth)
                            != DobEvidence.CONFLICT;
        }

        ClusterState mergedWith(ClusterState other) {
            Set<ListSource> mergedSources = EnumSet.copyOf(sources);
            mergedSources.addAll(other.sources);
            Set<LocalDate> mergedDobs = new HashSet<>(datesOfBirth);
            mergedDobs.addAll(other.datesOfBirth);
            return new ClusterState(mergedSources, mergedDobs);
        }
    }
}
