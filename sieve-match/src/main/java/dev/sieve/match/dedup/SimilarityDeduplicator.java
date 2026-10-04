package dev.sieve.match.dedup;

import dev.sieve.core.dedup.CanonicalEntity;
import dev.sieve.core.dedup.DeduplicationConfig;
import dev.sieve.core.dedup.DeduplicationResult;
import dev.sieve.core.dedup.EntityDeduplicator;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.IdentifierType;
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
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
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
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
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
 * <p>A name match that nothing else corroborates must be closer than one backed by a shared date of
 * birth or identifier: with no date of birth to compare, "Ivan Petrov" and "Petr Ivanov" are too
 * alike for the name score to tell them apart (see {@link
 * DeduplicationConfig#uncorroboratedNameThreshold()}).
 *
 * <p>Entities within the same source list are never merged, directly or transitively: they are
 * considered distinct by the issuing authority.
 *
 * <h3>Algorithm</h3>
 *
 * <ol>
 *   <li><b>Blocking</b> — entities of one type are grouped by the first characters of every token
 *       of every name, so two entities are compared when any of their name parts start alike,
 *       whatever the token order or alias spelling. A block on a common prefix ("moh", "abd") would
 *       hold thousands of entities, so blocks over {@link #MAX_BLOCK_SIZE} are split by finer keys
 *       (the neighbouring token's prefix, then the whole name)
 *   <li><b>Pairwise scoring</b> — candidate pairs from different list sources are scored, in
 *       parallel, using a weighted combination of name similarity, identifier overlap, and DOB
 *       evidence
 *   <li><b>Constrained clustering</b> — pairs are merged strongest first with Union-Find. A merge
 *       that would put two entities from one list, or provably different DOBs, into one cluster is
 *       refused, so a loose transitive chain (A≈B, B≈C) cannot join two distinct people
 *   <li><b>Canonical creation</b> — each cluster is merged into a single {@link CanonicalEntity}
 *       with combined metadata
 * </ol>
 */
public final class SimilarityDeduplicator implements EntityDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(SimilarityDeduplicator.class);

    /** Minimum name similarity for a shared identifier to stand in for a strong name match. */
    static final double MIN_NAME_SIMILARITY_WITH_IDENTIFIER = 0.75;

    /**
     * Entities a block may hold before it is split by a finer key. Within a block every pair is
     * compared, so this bounds the cost of common name prefixes.
     */
    static final int MAX_BLOCK_SIZE = 3000;

    /** Blocking keys per name form, from the coarsest (a short prefix) to the whole name. */
    private static final int KEY_LEVELS = 3;

    /** Entities whose candidates one unit of parallel scoring work covers. */
    private static final int SCORING_CHUNK = 256;

    /** Similarity of an initial ("j") to a full name token with the same first letter. */
    private static final double INITIAL_MATCH_SIMILARITY = 0.5;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]");

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
        int size = entityList.size();
        log.info("Starting entity deduplication [entities={}]", size);

        // Clustering addresses entities by position, so two lists reusing one ID can't be fused
        List<Profile> profiles = new ArrayList<>(size);
        for (SanctionedEntity entity : entityList) {
            profiles.add(Profile.of(entity, config.blockingPrefixLength()));
        }

        // Phase 1: Blocking
        List<int[]> blocks = buildBlocks(entityList, profiles);
        int[][] blocksOf = blocksOf(blocks, size);
        int largestBlock = blocks.stream().mapToInt(block -> block.length).max().orElse(0);

        // Phase 2: Pairwise scoring, collecting every candidate pair above threshold. Each chunk of
        // entities gathers the candidates from all its blocks, so a pair is scored once.
        LongAdder pairsCompared = new LongAdder();
        int chunks = (size + SCORING_CHUNK - 1) / SCORING_CHUNK;
        List<CandidatePair> candidates =
                IntStream.range(0, chunks)
                        .parallel()
                        .mapToObj(
                                chunk ->
                                        score(
                                                chunk * SCORING_CHUNK,
                                                Math.min(size, (chunk + 1) * SCORING_CHUNK),
                                                entityList,
                                                profiles,
                                                blocks,
                                                blocksOf,
                                                pairsCompared))
                        .flatMap(List::stream)
                        .collect(ArrayList::new, List::add, List::addAll);

        // Phase 3: Constrained clustering. Strongest evidence first, so an entity that resembles
        // several candidates joins the one it matches best; a union is refused when the merged
        // cluster would hold two entities from one list or provably different dates of birth.
        candidates.sort(CandidatePair.STRONGEST_FIRST);
        UnionFind<Integer> unionFind = new UnionFind<>();
        Map<Integer, ClusterState> clusterStates = new HashMap<>();
        for (int i = 0; i < size; i++) {
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

        // Phase 4: Build canonical entities from clusters, in input order
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
                        size,
                        canonicalEntities.size(),
                        mergedGroups,
                        duration);

        log.info(
                "Deduplication complete [source={}, canonical={}, merged={}, eliminated={}, "
                        + "blocks={}, largestBlock={}, pairsCompared={}, candidatePairs={}, "
                        + "rejectedUnions={}, duration={}ms]",
                result.totalSourceEntities(),
                result.totalCanonicalEntities(),
                result.mergedGroups(),
                result.duplicatesEliminated(),
                blocks.size(),
                largestBlock,
                pairsCompared.sum(),
                candidates.size(),
                rejectedUnions,
                duration.toMillis());

        return result;
    }

    /**
     * Groups entities that could be the same. Each entity joins one block per token of each of its
     * names, so two spellings of one person meet as long as one name part starts alike. Entities of
     * different types never share a block.
     *
     * @return the blocks, each the ascending positions of at least two entities
     */
    private List<int[]> buildBlocks(List<SanctionedEntity> entities, List<Profile> profiles) {
        Map<EntityType, List<Membership>> byType = new EnumMap<>(EntityType.class);
        for (int i = 0; i < entities.size(); i++) {
            List<Membership> members =
                    byType.computeIfAbsent(entities.get(i).entityType(), t -> new ArrayList<>());
            for (String[] keys : profiles.get(i).keyChains()) {
                members.add(new Membership(i, keys));
            }
        }
        List<int[]> blocks = new ArrayList<>();
        for (List<Membership> members : byType.values()) {
            split(members, 0, blocks);
        }
        return blocks;
    }

    /**
     * Blocks the memberships by their key at the given level. A block over {@link #MAX_BLOCK_SIZE}
     * is blocked again by the next, finer level while one exists.
     */
    private static void split(List<Membership> members, int level, List<int[]> blocks) {
        Map<String, List<Membership>> groups = new HashMap<>();
        for (Membership member : members) {
            groups.computeIfAbsent(member.keys()[level], k -> new ArrayList<>()).add(member);
        }
        for (List<Membership> group : groups.values()) {
            if (group.size() > MAX_BLOCK_SIZE && level < KEY_LEVELS - 1) {
                split(group, level + 1, blocks);
                continue;
            }
            int[] indexes =
                    group.stream().mapToInt(Membership::entity).distinct().sorted().toArray();
            if (indexes.length > 1) {
                blocks.add(indexes);
            }
        }
    }

    /** Positions in {@code blocks} of the blocks each entity belongs to. */
    private static int[][] blocksOf(List<int[]> blocks, int size) {
        int[] counts = new int[size];
        for (int[] block : blocks) {
            for (int entity : block) {
                counts[entity]++;
            }
        }
        int[][] blocksOf = new int[size][];
        for (int i = 0; i < size; i++) {
            blocksOf[i] = new int[counts[i]];
        }
        int[] filled = new int[size];
        for (int b = 0; b < blocks.size(); b++) {
            for (int entity : blocks.get(b)) {
                blocksOf[entity][filled[entity]++] = b;
            }
        }
        return blocksOf;
    }

    /**
     * Scores the entities at positions {@code [from, to)} against every later entity they share a
     * block with, and returns the pairs that reach the merge threshold.
     */
    private List<CandidatePair> score(
            int from,
            int to,
            List<SanctionedEntity> entities,
            List<Profile> profiles,
            List<int[]> blocks,
            int[][] blocksOf,
            LongAdder pairsCompared) {
        BitSet candidates = new BitSet(entities.size());
        List<CandidatePair> found = new ArrayList<>();
        long compared = 0;
        for (int i = from; i < to; i++) {
            candidates.clear();
            for (int block : blocksOf[i]) {
                for (int j : blocks.get(block)) {
                    if (j > i) {
                        candidates.set(j);
                    }
                }
            }
            SanctionedEntity a = entities.get(i);
            Profile profileA = profiles.get(i);
            for (int j = candidates.nextSetBit(0); j >= 0; j = candidates.nextSetBit(j + 1)) {
                SanctionedEntity b = entities.get(j);
                // Same-source entities are distinct by the issuing authority
                if (a.listSource() == b.listSource()) {
                    continue;
                }
                compared++;
                PairEvidence evidence = evaluate(a, profileA, b, profiles.get(j));
                if (evidence.rawScore() >= config.mergeThreshold()) {
                    found.add(
                            new CandidatePair(
                                    i, j, evidence.rawScore(), evidence.identifierMatch()));
                }
            }
        }
        pairsCompared.add(compared);
        return found;
    }

    /**
     * Computes a composite similarity score between two entities using multiple signals.
     *
     * <p>Name similarity is the base score. A shared identifier or date of birth adds the
     * configured bonus. Dates of birth that provably differ veto the merge unless an identifier
     * corroborates it; an identifier can rescue a name match down to {@link
     * #MIN_NAME_SIMILARITY_WITH_IDENTIFIER}, and a name match with neither an identifier nor a date
     * of birth in common must reach the uncorroborated threshold instead.
     *
     * @return composite score in [0.0, 1.0]
     */
    double compositeScore(SanctionedEntity a, SanctionedEntity b) {
        int prefixLength = config.blockingPrefixLength();
        return Math.min(
                evaluate(a, Profile.of(a, prefixLength), b, Profile.of(b, prefixLength)).rawScore(),
                1.0);
    }

    private PairEvidence evaluate(
            SanctionedEntity a, Profile profileA, SanctionedEntity b, Profile profileB) {
        // The cheap signals first: most candidate pairs never need their names compared
        boolean identifierMatch = hasMatchingIdentifier(profileA, profileB);
        DobEvidence dobEvidence = DobEvidence.between(a.datesOfBirth(), b.datesOfBirth());
        if (dobEvidence == DobEvidence.CONFLICT && !identifierMatch) {
            return PairEvidence.NONE;
        }

        double required;
        if (identifierMatch) {
            required = MIN_NAME_SIMILARITY_WITH_IDENTIFIER;
        } else if (dobEvidence == DobEvidence.UNKNOWN) {
            required = config.uncorroboratedNameThreshold();
        } else {
            required = config.nameThreshold();
        }
        double nameSim = bestNameSimilarity(profileA, profileB, required);
        if (nameSim < required) {
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

    /**
     * Best token-aligned similarity across all names of two entities. Name pairs whose token counts
     * differ by more than the required score allows are skipped without comparing: the extra tokens
     * count as unmatched, so "vladimir vladimirovich putin" can never score 0.9 against "vladimir
     * putin".
     */
    private static double bestNameSimilarity(Profile a, Profile b, double required) {
        double best = 0.0;
        for (int i = 0; i < a.tokenizedNames().size(); i++) {
            String[] nameA = a.tokenizedNames().get(i);
            for (int k = 0; k < b.tokenizedNames().size(); k++) {
                String[] nameB = b.tokenizedNames().get(k);
                if (bestPossibleSimilarity(
                                nameA, a.unmatchable().get(i), nameB, b.unmatchable().get(k))
                        < required) {
                    continue;
                }
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
     * Upper bound of {@link #tokenAlignedSimilarity} for two names, from their token counts: when
     * one name has more tokens, at least its shortest extra tokens stay unmatched.
     *
     * @param shortestA cumulative lengths of the shortest tokens of the first name
     * @param shortestB cumulative lengths of the shortest tokens of the second name
     */
    private static double bestPossibleSimilarity(
            String[] tokensA, int[] shortestA, String[] tokensB, int[] shortestB) {
        int extra = tokensA.length - tokensB.length;
        if (extra == 0) {
            return 1.0;
        }
        int unmatched = extra > 0 ? shortestA[extra - 1] : shortestB[-extra - 1];
        int total = shortestA[tokensA.length - 1] + shortestB[tokensB.length - 1];
        return 1.0 - (double) unmatched / total;
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
    private static boolean hasMatchingIdentifier(Profile a, Profile b) {
        for (IdentifierKey idA : a.identifiers()) {
            for (IdentifierKey idB : b.identifiers()) {
                if (idA.type() == idB.type()
                        && idA.value().equals(idB.value())
                        && compatibleCountries(idA.country(), idB.country())) {
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

    private static String prefix(String normalized, int length) {
        if (normalized.length() <= length) {
            return normalized;
        }
        return normalized.substring(0, length);
    }

    /**
     * All comparable name forms of one entity, computed once per deduplication run.
     *
     * @param tokenizedNames each distinct normalized name, split into tokens
     * @param unmatchable per name, the cumulative lengths of its tokens shortest first, for the
     *     bound on what a comparison can score
     * @param keyChains blocking keys, coarse to fine, of each token of each name
     * @param identifiers identifiers with their values normalized for comparison
     */
    private record Profile(
            List<String[]> tokenizedNames,
            List<int[]> unmatchable,
            List<String[]> keyChains,
            List<IdentifierKey> identifiers) {

        static Profile of(SanctionedEntity entity, int prefixLength) {
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
            List<int[]> unmatchable = new ArrayList<>(names.size());
            Set<List<String>> chains = new LinkedHashSet<>();
            for (String name : names) {
                String[] tokens = name.split(" ");
                tokenized.add(tokens);
                unmatchable.add(cumulativeShortest(tokens));
                for (int i = 0; i < tokens.length; i++) {
                    // An initial alone can't carry a match, so it keys no block
                    if (tokens[i].length() > 1) {
                        chains.add(keyChain(name, tokens, i, prefixLength));
                    }
                }
            }

            List<IdentifierKey> identifiers = new ArrayList<>();
            for (Identifier identifier : entity.identifiers()) {
                String value = normalizeIdentifierValue(identifier.value());
                if (!value.isEmpty()) {
                    identifiers.add(
                            new IdentifierKey(
                                    identifier.type(), value, identifier.issuingCountry()));
                }
            }

            return new Profile(
                    List.copyOf(tokenized),
                    List.copyOf(unmatchable),
                    chains.stream().map(chain -> chain.toArray(String[]::new)).toList(),
                    List.copyOf(identifiers));
        }

        /**
         * Blocking keys of one token of a name: the token's prefix, then that with the prefix of
         * the token next to it, then the whole name.
         */
        private static List<String> keyChain(
                String name, String[] tokens, int index, int prefixLength) {
            String token = prefix(tokens[index], prefixLength);
            String neighbour =
                    tokens.length == 1
                            ? token
                            : prefix(
                                    tokens[index + 1 < tokens.length ? index + 1 : index - 1],
                                    prefixLength);
            return List.of(token, token + "|" + neighbour, name);
        }

        /**
         * Cumulative lengths of the tokens, shortest first; the last entry is the name's length.
         */
        private static int[] cumulativeShortest(String[] tokens) {
            int[] lengths = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                lengths[i] = tokens[i].length();
            }
            Arrays.sort(lengths);
            for (int i = 1; i < lengths.length; i++) {
                lengths[i] += lengths[i - 1];
            }
            return lengths;
        }

        private static void addName(Set<String> names, String name) {
            String normalized = normalizeForDedup(name);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
    }

    /** An identifier reduced to what the comparison looks at. */
    private record IdentifierKey(IdentifierType type, String value, String country) {}

    /** One entity's claim to a block, with its keys from the coarsest level to the finest. */
    private record Membership(int entity, String[] keys) {}

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
