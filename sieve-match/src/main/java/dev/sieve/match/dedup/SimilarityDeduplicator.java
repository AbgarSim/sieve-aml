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
 *       issuing country; a value shared by more than {@link #MAX_IDENTIFIER_HOLDERS} entities is
 *       not an identifier and is ignored
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
 *       (that prefix paired with each other token's prefix, then the whole name). Entities with a
 *       date of birth are blocked again within their birth year, where the blocks stay small enough
 *       for spelling variants of a common name to meet; entities sharing an identifier always meet
 *   <li><b>Pairwise scoring</b> — candidate pairs from different list sources are scored, in
 *       parallel, using a weighted combination of name similarity, identifier overlap, and DOB
 *       evidence
 *   <li><b>Constrained clustering</b> — pairs are merged strongest first with Union-Find. A merge
 *       that would put two entities from one list, or provably different DOBs, into one cluster is
 *       refused, so a loose transitive chain (A≈B, B≈C) cannot join two distinct people. Equally
 *       strong pairs are taken in list and id order, so the result does not depend on the order the
 *       entities arrive in
 *   <li><b>Canonical creation</b> — each cluster is merged into a single {@link CanonicalEntity}
 *       with combined metadata
 * </ol>
 */
public final class SimilarityDeduplicator implements EntityDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(SimilarityDeduplicator.class);

    /** Minimum name similarity for a shared identifier to stand in for a strong name match. */
    static final double MIN_NAME_SIMILARITY_WITH_IDENTIFIER = 0.75;

    /**
     * Entities that may share an identifier value before it stops counting as one. A person or
     * company has at most a few dozen records across the lists; a value on thousands is a gender or
     * a sanctions-programme flag that a list stored as an identifier.
     */
    static final int MAX_IDENTIFIER_HOLDERS = 50;

    /**
     * Entities a block may hold before it is split by a finer key. Within a block every pair is
     * compared, so this bounds the cost of common name parts.
     */
    static final int MAX_BLOCK_SIZE = 500;

    /** The finest blocking level, keyed by the whole name; its blocks are never split. */
    private static final int LAST_LEVEL = 2;

    /** Channel of the blocks every name joins; a date of birth adds channels named by year. */
    private static final String NAME_CHANNEL = "";

    /** Entities whose candidates one unit of parallel scoring work covers. */
    private static final int SCORING_CHUNK = 256;

    /** Similarity of an initial ("j") to a full name token with the same first letter. */
    private static final double INITIAL_MATCH_SIMILARITY = 0.5;

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

        // Ties between equally strong candidates fall to the earlier position, so the input is put
        // in a fixed order first: the lists are fetched in parallel and arrive in any order
        List<SanctionedEntity> entityList =
                entities.stream()
                        .sorted(
                                Comparator.comparing(SanctionedEntity::listSource)
                                        .thenComparing(SanctionedEntity::id))
                        .toList();
        int size = entityList.size();
        log.info("Starting entity deduplication [entities={}]", size);

        // Clustering addresses entities by position, so two lists reusing one ID can't be fused
        List<Profile> profiles = new ArrayList<>(size);
        for (SanctionedEntity entity : entityList) {
            profiles.add(Profile.of(entity, config.blockingPrefixLength()));
        }
        discountCommonIdentifiers(profiles);

        // Phase 1: Blocking
        List<int[]> blocks = buildBlocks(entityList, profiles);
        int[][] blocksOf = blocksOf(blocks, size);
        int largestBlock = blocks.stream().mapToInt(block -> block.length).max().orElse(0);
        if (log.isDebugEnabled()) {
            log.debug(
                    "Blocking done [blocks={}, largestBlock={}, bySize={}, elapsed={}ms]",
                    blocks.size(),
                    largestBlock,
                    blockHistogram(blocks),
                    Duration.between(start, Instant.now()).toMillis());
        }

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
        log.debug(
                "Scoring done [pairsCompared={}, candidatePairs={}, elapsed={}ms]",
                pairsCompared.sum(),
                candidates.size(),
                Duration.between(start, Instant.now()).toMillis());

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
     * Strips the identifiers whose value more than {@link #MAX_IDENTIFIER_HOLDERS} entities share.
     * Such a value identifies nobody, and letting it stand in for an identifier would merge any two
     * people it describes on a loose name match.
     */
    private static void discountCommonIdentifiers(List<Profile> profiles) {
        Map<String, Integer> holders = new HashMap<>();
        for (Profile profile : profiles) {
            profile.identifiers().stream()
                    .map(IdentifierKey::key)
                    .distinct()
                    .forEach(key -> holders.merge(key, 1, Integer::sum));
        }
        Set<String> common = new HashSet<>();
        holders.forEach(
                (key, count) -> {
                    if (count > MAX_IDENTIFIER_HOLDERS) {
                        common.add(key);
                    }
                });
        if (common.isEmpty()) {
            return;
        }
        log.warn(
                "Ignoring {} identifier values shared by more than {} entities each, such as {}",
                common.size(),
                MAX_IDENTIFIER_HOLDERS,
                common.stream().sorted().limit(3).toList());
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            if (profile.identifiers().stream().anyMatch(id -> common.contains(id.key()))) {
                profiles.set(i, profile.withoutIdentifiers(common));
            }
        }
    }

    /**
     * Groups entities that could be the same. Each entity joins one block per token of each of its
     * names, so two spellings of one person meet as long as one name part starts alike; an entity
     * with a date of birth joins those blocks again within each of its birth years, and entities
     * sharing an identifier form a block of their own. Entities of different types never share a
     * block.
     *
     * @return the blocks, each the ascending positions of at least two entities
     */
    private List<int[]> buildBlocks(List<SanctionedEntity> entities, List<Profile> profiles) {
        Map<EntityType, List<Membership>> byType = new EnumMap<>(EntityType.class);
        Map<EntityType, Map<String, List<Integer>>> byIdentifier = new EnumMap<>(EntityType.class);
        for (int i = 0; i < entities.size(); i++) {
            EntityType type = entities.get(i).entityType();
            Profile profile = profiles.get(i);
            List<Membership> members = byType.computeIfAbsent(type, t -> new ArrayList<>());
            for (int name = 0; name < profile.nameTokens().length; name++) {
                int[] positions = profile.nameTokens()[name];
                for (int token = 0; token < positions.length; token++) {
                    // An initial alone can't carry a match, so it keys no block
                    if (profile.tokens()[positions[token]].length() > 1) {
                        members.add(new Membership(i, name, token, NAME_CHANNEL));
                        for (String year : profile.dobChannels()) {
                            members.add(new Membership(i, name, token, year));
                        }
                    }
                }
            }
            Map<String, List<Integer>> holders =
                    byIdentifier.computeIfAbsent(type, t -> new HashMap<>());
            for (IdentifierKey identifier : profile.identifiers()) {
                holders.computeIfAbsent(identifier.key(), k -> new ArrayList<>()).add(i);
            }
        }

        List<int[]> blocks = new ArrayList<>();
        for (List<Membership> members : byType.values()) {
            split(members, 0, profiles, blocks);
        }
        for (Map<String, List<Integer>> holders : byIdentifier.values()) {
            for (List<Integer> sharing : holders.values()) {
                int[] indexes = sharing.stream().mapToInt(Integer::intValue).distinct().toArray();
                if (indexes.length > 1) {
                    blocks.add(indexes);
                }
            }
        }
        return blocks;
    }

    /**
     * Blocks the memberships by their keys at the given level. A block over {@link #MAX_BLOCK_SIZE}
     * is blocked again by the next, finer level while one exists.
     */
    private void split(
            List<Membership> members, int level, List<Profile> profiles, List<int[]> blocks) {
        Map<String, List<Membership>> groups = new HashMap<>();
        for (Membership member : members) {
            for (String key : keys(member, level, profiles.get(member.entity()))) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(member);
            }
        }
        for (List<Membership> group : groups.values()) {
            int[] indexes =
                    group.stream().mapToInt(Membership::entity).distinct().sorted().toArray();
            if (indexes.length < 2) {
                continue;
            }
            if (indexes.length > MAX_BLOCK_SIZE && level < LAST_LEVEL) {
                split(group, level + 1, profiles, blocks);
            } else {
                blocks.add(indexes);
            }
        }
    }

    /**
     * Blocking keys of one membership at a level, coarse to fine: the token's prefix; that prefix
     * paired with the prefix of each other token of the name, in either order (the whole token for
     * a one-word name); the whole name. Every key carries the membership's channel.
     */
    private List<String> keys(Membership member, int level, Profile profile) {
        int[] positions = profile.nameTokens()[member.name()];
        String token = profile.tokens()[positions[member.token()]];
        String channel = member.channel();
        int prefixLength = config.blockingPrefixLength();
        return switch (level) {
            case 0 -> List.of(channel + prefix(token, prefixLength));
            case 1 -> {
                if (positions.length == 1) {
                    yield List.of(channel + "=" + token);
                }
                String own = prefix(token, prefixLength);
                List<String> keys = new ArrayList<>(positions.length - 1);
                for (int t = 0; t < positions.length; t++) {
                    if (t == member.token()) {
                        continue;
                    }
                    String other = prefix(profile.tokens()[positions[t]], prefixLength);
                    keys.add(
                            own.compareTo(other) <= 0
                                    ? channel + own + "|" + other
                                    : channel + other + "|" + own);
                }
                yield keys;
            }
            default -> List.of(channel + "~" + profile.names()[member.name()]);
        };
    }

    /** Blocks and pairs to compare by block size, for tuning {@link #MAX_BLOCK_SIZE}. */
    private static String blockHistogram(List<int[]> blocks) {
        int[] limits = {10, 50, 100, 250, MAX_BLOCK_SIZE, Integer.MAX_VALUE};
        long[] count = new long[limits.length];
        long[] pairs = new long[limits.length];
        for (int[] block : blocks) {
            int bucket = 0;
            while (block.length > limits[bucket]) {
                bucket++;
            }
            count[bucket]++;
            pairs[bucket] += (long) block.length * (block.length - 1) / 2;
        }
        StringBuilder histogram = new StringBuilder();
        int from = 2;
        for (int i = 0; i < limits.length; i++) {
            if (i > 0) {
                histogram.append(", ");
            }
            histogram
                    .append(from)
                    .append(limits[i] == Integer.MAX_VALUE ? "+" : "-" + limits[i])
                    .append(": ")
                    .append(count[i])
                    .append(" blocks/")
                    .append(pairs[i])
                    .append(" pairs");
            from = limits[i] + 1;
        }
        return histogram.toString();
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
        TokenSimilarities tokenSimilarities = new TokenSimilarities();
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
                PairEvidence evidence =
                        evaluate(a, profileA, b, profiles.get(j), tokenSimilarities);
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
                evaluate(
                                a,
                                Profile.of(a, prefixLength),
                                b,
                                Profile.of(b, prefixLength),
                                new TokenSimilarities())
                        .rawScore(),
                1.0);
    }

    private PairEvidence evaluate(
            SanctionedEntity a,
            Profile profileA,
            SanctionedEntity b,
            Profile profileB,
            TokenSimilarities tokenSimilarities) {
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
        double nameSim =
                bestNameSimilarity(
                        profileA, profileB, required, identifierMatch, tokenSimilarities);
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
     * Best token-aligned similarity across all names of two entities. Two entities with a name in
     * common score 1.0 without comparing. Otherwise name pairs that cannot reach the required score
     * are skipped without comparing: when the token counts differ, the extra tokens count as
     * unmatched, so "vladimir vladimirovich putin" can never score 0.9 against "vladimir putin";
     * unless an identifier vouches for the pair, two names with no token starting alike are too far
     * apart for the 0.9 a date of birth needs, let alone an uncorroborated match; and a name pair
     * whose tokens, by their letters alone, cannot be similar enough is not aligned.
     */
    private static double bestNameSimilarity(
            Profile a,
            Profile b,
            double required,
            boolean identifierMatch,
            TokenSimilarities tokenSimilarities) {
        if (a.sharesNameWith(b)) {
            return 1.0;
        }
        tokenSimilarities.reset(a, b);
        double best = 0.0;
        for (int i = 0; i < a.nameTokens().length; i++) {
            int[] nameA = a.nameTokens()[i];
            int[] lengthsA = a.nameLengths()[i];
            long prefixesA = a.namePrefixes()[i];
            for (int k = 0; k < b.nameTokens().length; k++) {
                int[] nameB = b.nameTokens()[k];
                int[] lengthsB = b.nameLengths()[k];
                if (!identifierMatch && (prefixesA & b.namePrefixes()[k]) == 0) {
                    continue;
                }
                if (bestPossibleSimilarity(
                                nameA.length, a.unmatchable()[i], nameB.length, b.unmatchable()[k])
                        < required) {
                    continue;
                }
                int totalLength =
                        a.unmatchable()[i][nameA.length - 1] + b.unmatchable()[k][nameB.length - 1];
                // A hair below the threshold is left to the exact score, in case rounding differs
                if (boundedSimilarity(
                                nameA, lengthsA, nameB, lengthsB, totalLength, tokenSimilarities)
                        < required - 1e-9) {
                    continue;
                }
                double[] sims = new double[nameA.length * nameB.length];
                for (int x = 0; x < nameA.length; x++) {
                    for (int y = 0; y < nameB.length; y++) {
                        sims[x * nameB.length + y] = tokenSimilarities.of(nameA[x], nameB[y]);
                    }
                }
                best = Math.max(best, align(sims, lengthsA, lengthsB));
            }
        }
        return best;
    }

    /**
     * Upper bound of the aligned similarity of two names: every token takes the partner it could
     * score best against, by {@link #tokenSimilarityBound}, as if partners could be reused.
     */
    private static double boundedSimilarity(
            int[] nameA,
            int[] lengthsA,
            int[] nameB,
            int[] lengthsB,
            int totalLength,
            TokenSimilarities tokenSimilarities) {
        double sum = 0.0;
        for (int x = 0; x < nameA.length; x++) {
            double best = 0.0;
            for (int y = 0; y < nameB.length; y++) {
                double bound =
                        tokenSimilarities.atMost(nameA[x], nameB[y]) * (lengthsA[x] + lengthsB[y]);
                if (bound > best) {
                    best = bound;
                }
            }
            sum += best;
        }
        return sum / totalLength;
    }

    /**
     * Upper bound of {@link #tokenSimilarity} from the tokens' lengths, letters and common prefix.
     * Jaro counts characters matched one to one, so no more can match than the shorter token has,
     * nor than the two share, counting a shared letter once per repeat in the token with fewer
     * repeats; transpositions can only lower the score. Letters are compared as bits of a mask, so
     * two letters may share a bit, which only loosens the bound.
     */
    static double tokenSimilarityBound(String a, long maskA, String b, long maskB) {
        if (a.length() == 1 || b.length() == 1) {
            return a.charAt(0) == b.charAt(0) ? INITIAL_MATCH_SIMILARITY : 0.0;
        }
        int lengthA = a.length();
        int lengthB = b.length();
        int shared = Long.bitCount(maskA & maskB);
        int repeats = Math.min(lengthA - Long.bitCount(maskA), lengthB - Long.bitCount(maskB));
        int matches = Math.min(Math.min(lengthA, lengthB), shared + repeats);
        if (matches == 0) {
            return 0.0;
        }
        double jaro = ((double) matches / lengthA + (double) matches / lengthB + 1.0) / 3.0;
        int prefix = 0;
        while (prefix < JaroWinkler.MAX_PREFIX_LENGTH
                && prefix < lengthA
                && prefix < lengthB
                && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * JaroWinkler.DEFAULT_PREFIX_SCALE * (1.0 - jaro);
    }

    /** The letters of a token as bits, for {@link #tokenSimilarityBound}. */
    static long letterMask(String token) {
        long mask = 0L;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            int bit;
            if (c >= 'a' && c <= 'z') {
                bit = c - 'a';
            } else if (c >= '0' && c <= '9') {
                bit = 26 + c - '0';
            } else {
                bit = 36 + ((c * 0x9E3779B9) >>> 27) % 28;
            }
            mask |= 1L << bit;
        }
        return mask;
    }

    /**
     * Upper bound of {@link #tokenAlignedSimilarity} for two names, from their token counts: when
     * one name has more tokens, at least its shortest extra tokens stay unmatched.
     *
     * @param shortestA cumulative lengths of the shortest tokens of the first name
     * @param shortestB cumulative lengths of the shortest tokens of the second name
     */
    private static double bestPossibleSimilarity(
            int tokensA, int[] shortestA, int tokensB, int[] shortestB) {
        int extra = tokensA - tokensB;
        if (extra == 0) {
            return 1.0;
        }
        int unmatched = extra > 0 ? shortestA[extra - 1] : shortestB[-extra - 1];
        int total = shortestA[tokensA - 1] + shortestB[tokensB - 1];
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
        double[] sims = new double[tokensA.length * tokensB.length];
        int[] lengthsA = new int[tokensA.length];
        int[] lengthsB = new int[tokensB.length];
        for (int i = 0; i < tokensA.length; i++) {
            lengthsA[i] = tokensA[i].length();
            for (int k = 0; k < tokensB.length; k++) {
                sims[i * tokensB.length + k] = tokenSimilarity(tokensA[i], tokensB[k]);
            }
        }
        for (int k = 0; k < tokensB.length; k++) {
            lengthsB[k] = tokensB[k].length();
        }
        return align(sims, lengthsA, lengthsB);
    }

    /**
     * The greedy alignment behind {@link #tokenAlignedSimilarity}.
     *
     * @param sims similarity of each token of the first name (rows) to each of the second (columns)
     * @param lengthsA lengths of the first name's tokens
     * @param lengthsB lengths of the second name's tokens
     */
    private static double align(double[] sims, int[] lengthsA, int[] lengthsB) {
        int countA = lengthsA.length;
        int countB = lengthsB.length;
        int totalLength = 0;
        for (int length : lengthsA) {
            totalLength += length;
        }
        for (int length : lengthsB) {
            totalLength += length;
        }

        boolean[] usedA = new boolean[countA];
        boolean[] usedB = new boolean[countB];
        double weightedSum = 0.0;
        for (int pairs = Math.min(countA, countB); pairs > 0; pairs--) {
            int bestI = -1;
            int bestK = -1;
            double bestSim = -1.0;
            for (int i = 0; i < countA; i++) {
                if (usedA[i]) {
                    continue;
                }
                for (int k = 0; k < countB; k++) {
                    if (!usedB[k] && sims[i * countB + k] > bestSim) {
                        bestSim = sims[i * countB + k];
                        bestI = i;
                        bestK = k;
                    }
                }
            }
            usedA[bestI] = true;
            usedB[bestK] = true;
            weightedSum += bestSim * (lengthsA[bestI] + lengthsB[bestK]);
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
     * Normalizes a name for deduplication with the same keys the match engines use, so
     * "MÜLLER-García, José" and "Muller Garcia Jose" compare token for token, and "Путин" meets
     * "Putin".
     */
    /** TEMP: 0 = new, 1 = main's normalisation, 2 = new without legal forms. */
    public static volatile int TEMP_MODE = 0;

    static String normalizeForDedup(String name, EntityType type) {
        if (TEMP_MODE == 1) {
            if (name == null || name.isBlank()) {
                return "";
            }
            String folded =
                    java.util.regex.Pattern.compile("\\p{M}+")
                            .matcher(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD))
                            .replaceAll("");
            return folded.toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]+", " ")
                    .strip();
        }
        if (TEMP_MODE == 2) {
            return NameNormalizer.normalize(name);
        }
        return NameNormalizer.normalize(name, type);
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
     * @param names each distinct normalized name
     * @param nameSet the same names, for the exact-match shortcut
     * @param tokens the distinct tokens of all the names; aliases repeat name parts, so a pair of
     *     entities compares each pair of tokens once
     * @param tokenMasks per token, its letters as bits, see {@link #letterMask}
     * @param nameTokens per name, the positions in {@code tokens} of its tokens, in order
     * @param nameLengths per name, the lengths of its tokens, in order
     * @param namePrefixes per name, a bit set over the blocking prefixes of its tokens: two names
     *     with no bit in common share no prefix (the converse may not hold)
     * @param unmatchable per name, the cumulative lengths of its tokens shortest first, for the
     *     bound on what a comparison can score
     * @param dobChannels blocking channel of each year the entity may have been born in
     * @param identifiers identifiers with their values normalized for comparison
     */
    private record Profile(
            String[] names,
            Set<String> nameSet,
            String[] tokens,
            long[] tokenMasks,
            int[][] nameTokens,
            int[][] nameLengths,
            long[] namePrefixes,
            int[][] unmatchable,
            String[] dobChannels,
            List<IdentifierKey> identifiers) {

        static Profile of(SanctionedEntity entity, int prefixLength) {
            Set<String> names = new LinkedHashSet<>();
            NameInfo primary = entity.primaryName();
            EntityType type = entity.entityType();
            addName(names, primary.fullName(), type);
            if (primary.givenName() != null && primary.familyName() != null) {
                addName(names, primary.givenName() + " " + primary.familyName(), type);
            }
            for (NameInfo alias : entity.aliases()) {
                addName(names, alias.fullName(), type);
            }

            Map<String, Integer> tokenPositions = new LinkedHashMap<>();
            int[][] nameTokens = new int[names.size()][];
            int[][] nameLengths = new int[names.size()][];
            long[] namePrefixes = new long[names.size()];
            int[][] unmatchable = new int[names.size()][];
            int n = 0;
            for (String name : names) {
                String[] tokens = name.split(" ");
                int[] positions = new int[tokens.length];
                int[] lengths = new int[tokens.length];
                long prefixes = 0L;
                for (int t = 0; t < tokens.length; t++) {
                    Integer position = tokenPositions.get(tokens[t]);
                    if (position == null) {
                        position = tokenPositions.size();
                        tokenPositions.put(tokens[t], position);
                    }
                    positions[t] = position;
                    lengths[t] = tokens[t].length();
                    if (tokens[t].length() > 1) {
                        prefixes |= prefixBit(prefix(tokens[t], prefixLength));
                    }
                }
                nameTokens[n] = positions;
                nameLengths[n] = lengths;
                namePrefixes[n] = prefixes;
                unmatchable[n] = cumulativeShortest(tokens);
                n++;
            }
            String[] tokens = tokenPositions.keySet().toArray(String[]::new);
            long[] tokenMasks = new long[tokens.length];
            for (int t = 0; t < tokens.length; t++) {
                tokenMasks[t] = letterMask(tokens[t]);
            }

            String[] dobChannels =
                    entity.datesOfBirth().stream()
                            .mapToInt(LocalDate::getYear)
                            .distinct()
                            .sorted()
                            .mapToObj(year -> year + "|")
                            .toArray(String[]::new);

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
                    names.toArray(String[]::new),
                    names,
                    tokens,
                    tokenMasks,
                    nameTokens,
                    nameLengths,
                    namePrefixes,
                    unmatchable,
                    dobChannels,
                    List.copyOf(identifiers));
        }

        /** The same profile without the identifiers whose {@link IdentifierKey#key} is given. */
        Profile withoutIdentifiers(Set<String> keys) {
            return new Profile(
                    names,
                    nameSet,
                    tokens,
                    tokenMasks,
                    nameTokens,
                    nameLengths,
                    namePrefixes,
                    unmatchable,
                    dobChannels,
                    identifiers.stream().filter(id -> !keys.contains(id.key())).toList());
        }

        /** One of 64 bits for a blocking prefix; distinct prefixes may share a bit. */
        private static long prefixBit(String prefix) {
            return 1L << ((prefix.hashCode() * 0x9E3779B9) >>> 26);
        }

        boolean sharesNameWith(Profile other) {
            Profile fewer = names.length <= other.names.length ? this : other;
            Profile more = fewer == this ? other : this;
            for (String name : fewer.names) {
                if (more.nameSet.contains(name)) {
                    return true;
                }
            }
            return false;
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

        private static void addName(Set<String> names, String name, EntityType type) {
            String normalized = normalizeForDedup(name, type);
            // A "name" without a letter, such as a stray row number, can't identify anyone
            if (normalized.chars().anyMatch(Character::isLetter)) {
                names.add(normalized);
            }
        }
    }

    /**
     * Token similarities of one entity pair, and their cheap upper bounds, each computed when a
     * name pair first needs it. One instance serves a whole scoring chunk; a stamp marks which
     * entries belong to the current pair, so switching pairs costs nothing.
     */
    private static final class TokenSimilarities {
        private double[] values = new double[0];
        private double[] bounds = new double[0];
        private int[] valueStamps = new int[0];
        private int[] boundStamps = new int[0];
        private int stamp;
        private Profile a;
        private Profile b;
        private int width;

        void reset(Profile a, Profile b) {
            this.a = a;
            this.b = b;
            width = b.tokens().length;
            int needed = a.tokens().length * width;
            if (needed > values.length) {
                values = new double[needed];
                bounds = new double[needed];
                valueStamps = new int[needed];
                boundStamps = new int[needed];
                stamp = 0;
            }
            stamp++;
        }

        /** Similarity of token {@code x} of the first profile to token {@code y} of the second. */
        double of(int x, int y) {
            int index = x * width + y;
            if (valueStamps[index] != stamp) {
                valueStamps[index] = stamp;
                values[index] = tokenSimilarity(a.tokens()[x], b.tokens()[y]);
            }
            return values[index];
        }

        /** Upper bound of {@link #of}, see {@link #tokenSimilarityBound}. */
        double atMost(int x, int y) {
            int index = x * width + y;
            if (boundStamps[index] != stamp) {
                boundStamps[index] = stamp;
                bounds[index] =
                        tokenSimilarityBound(
                                a.tokens()[x], a.tokenMasks()[x], b.tokens()[y], b.tokenMasks()[y]);
            }
            return bounds[index];
        }
    }

    /** An identifier reduced to what the comparison looks at. */
    private record IdentifierKey(IdentifierType type, String value, String country) {

        /** The type and value, by which identifiers are blocked and counted. */
        String key() {
            return type + ":" + value;
        }
    }

    /**
     * One token of one name of an entity, which claims a block at each level of {@link #keys}. The
     * channel keeps blocks apart that must not mix: names as such, and names of entities born in
     * one year, so that a common name split into exact spellings can still meet its variants among
     * the few entities sharing its birth year.
     */
    private record Membership(int entity, int name, int token, String channel) {}

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
