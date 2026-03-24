package dev.sieve.match.dedup;

import dev.sieve.core.dedup.CanonicalEntity;
import dev.sieve.core.dedup.DeduplicationConfig;
import dev.sieve.core.dedup.DeduplicationResult;
import dev.sieve.core.dedup.EntityDeduplicator;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.Identifier;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.NameNormalizer;
import dev.sieve.match.algorithm.JaroWinkler;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entity deduplicator that uses multi-signal similarity to identify duplicate entities across
 * different sanctions lists.
 *
 * <p>Inspired by the nomenklatura framework used by OpenSanctions, this implementation merges
 * entities based on:
 * <ul>
 *   <li><b>Name similarity</b> — Jaro-Winkler fuzzy matching across primary names and aliases</li>
 *   <li><b>Identifier overlap</b> — exact match on passport numbers, national IDs, etc.</li>
 *   <li><b>Date of birth overlap</b> — matching DOBs provide additional evidence</li>
 * </ul>
 *
 * <p>Only entities from <em>different</em> list sources are considered for merging. Entities
 * within the same source list are never merged (they are considered distinct by the issuing
 * authority).
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Blocking</b> — entities are grouped by (entity type, normalized name prefix) to avoid
 *       O(n²) pairwise comparisons</li>
 *   <li><b>Pairwise scoring</b> — candidate pairs from different list sources are scored using a
 *       weighted combination of name similarity, identifier overlap, and DOB overlap</li>
 *   <li><b>Transitive closure</b> — Union-Find merges transitive matches into clusters (if A≈B
 *       and B≈C, then A, B, and C form one canonical entity)</li>
 *   <li><b>Canonical creation</b> — each cluster is merged into a single {@link CanonicalEntity}
 *       with combined metadata</li>
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

        List<SanctionedEntity> entityList =
                entities instanceof List ? (List<SanctionedEntity>) entities : List.copyOf(entities);

        log.info("Starting entity deduplication [entities={}]", entityList.size());

        // Phase 1: Group by entity type
        Map<EntityType, List<SanctionedEntity>> byType = groupByType(entityList);

        // Phase 2: Build blocking groups and find matches using Union-Find
        UnionFind<String> unionFind = new UnionFind<>();
        Map<String, SanctionedEntity> entityById = new HashMap<>(entityList.size());

        for (SanctionedEntity entity : entityList) {
            entityById.put(entity.id(), entity);
            unionFind.makeSet(entity.id());
        }

        AtomicInteger pairsCompared = new AtomicInteger();
        AtomicInteger mergesPerformed = new AtomicInteger();

        for (Map.Entry<EntityType, List<SanctionedEntity>> entry : byType.entrySet()) {
            EntityType type = entry.getKey();
            List<SanctionedEntity> group = entry.getValue();

            // Phase 2a: Build blocking index
            Map<String, List<SanctionedEntity>> blocks = buildBlocks(group);

            log.debug(
                    "Processing entity type [type={}, entities={}, blocks={}]",
                    type,
                    group.size(),
                    blocks.size());

            // Phase 2b: Pairwise comparison within each block
            for (List<SanctionedEntity> block : blocks.values()) {
                compareBlock(block, unionFind, pairsCompared, mergesPerformed);
            }
        }

        // Phase 3: Build canonical entities from Union-Find clusters
        Map<String, List<String>> clusters = unionFind.clusters();
        Map<String, CanonicalEntity> canonicalEntities = new LinkedHashMap<>();
        Map<String, String> entityToCanonicalId = new HashMap<>();
        int mergedGroups = 0;
        int canonicalCounter = 0;

        for (Map.Entry<String, List<String>> clusterEntry : clusters.entrySet()) {
            List<String> memberIds = clusterEntry.getValue();
            List<SanctionedEntity> members = memberIds.stream().map(entityById::get).toList();

            String canonicalId = "canonical-" + canonicalCounter++;
            CanonicalEntity canonical = CanonicalEntity.merge(canonicalId, members);
            canonicalEntities.put(canonicalId, canonical);

            for (String memberId : memberIds) {
                entityToCanonicalId.put(memberId, canonicalId);
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
                        + "pairsCompared={}, duration={}ms]",
                result.totalSourceEntities(),
                result.totalCanonicalEntities(),
                result.mergedGroups(),
                result.duplicatesEliminated(),
                pairsCompared.get(),
                duration.toMillis());

        return result;
    }

    private Map<EntityType, List<SanctionedEntity>> groupByType(List<SanctionedEntity> entities) {
        Map<EntityType, List<SanctionedEntity>> groups = new LinkedHashMap<>();
        for (SanctionedEntity entity : entities) {
            groups.computeIfAbsent(entity.entityType(), k -> new ArrayList<>()).add(entity);
        }
        return groups;
    }

    /**
     * Builds blocking groups keyed by the first N characters of the normalized primary name.
     * Each entity may appear in multiple blocks (one per name variant) to handle spelling
     * differences.
     */
    private Map<String, List<SanctionedEntity>> buildBlocks(List<SanctionedEntity> entities) {
        Map<String, List<SanctionedEntity>> blocks = new HashMap<>();
        int prefixLen = config.blockingPrefixLength();

        for (SanctionedEntity entity : entities) {
            // Block on primary name prefix
            String normalizedPrimary = NameNormalizer.normalize(entity.primaryName().fullName());
            addToBlock(blocks, blockKey(normalizedPrimary, prefixLen), entity);

            // Also block on alias prefixes (catches spelling variants)
            for (NameInfo alias : entity.aliases()) {
                String normalizedAlias = NameNormalizer.normalize(alias.fullName());
                addToBlock(blocks, blockKey(normalizedAlias, prefixLen), entity);
            }

            // Block on family name prefix if available (handles "LAST, First" vs "First LAST")
            if (entity.primaryName().familyName() != null) {
                String normalizedFamily =
                        NameNormalizer.normalize(entity.primaryName().familyName());
                addToBlock(blocks, blockKey(normalizedFamily, prefixLen), entity);
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

    private static void addToBlock(
            Map<String, List<SanctionedEntity>> blocks, String key, SanctionedEntity entity) {
        if (!key.isBlank()) {
            blocks.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
    }

    /**
     * Compares all cross-source pairs within a block and merges matches.
     */
    private void compareBlock(
            List<SanctionedEntity> block,
            UnionFind<String> unionFind,
            AtomicInteger pairsCompared,
            AtomicInteger mergesPerformed) {

        for (int i = 0; i < block.size(); i++) {
            SanctionedEntity a = block.get(i);
            for (int j = i + 1; j < block.size(); j++) {
                SanctionedEntity b = block.get(j);

                // Only merge across different list sources
                if (a.listSource() == b.listSource()) {
                    continue;
                }

                // Skip if already in the same canonical group
                if (unionFind.find(a.id()).equals(unionFind.find(b.id()))) {
                    continue;
                }

                pairsCompared.incrementAndGet();
                double score = compositeScore(a, b);

                if (score >= config.mergeThreshold()) {
                    unionFind.union(a.id(), b.id());
                    mergesPerformed.incrementAndGet();
                    log.trace(
                            "Merged entities [a={} ({}), b={} ({}), score={}]",
                            a.id(),
                            a.listSource(),
                            b.id(),
                            b.listSource(),
                            score);
                }
            }
        }
    }

    /**
     * Computes a composite similarity score between two entities using multiple signals.
     *
     * @return composite score in [0.0, 1.0+] (may exceed 1.0 with bonus signals, clamped later)
     */
    double compositeScore(SanctionedEntity a, SanctionedEntity b) {
        // Signal 1: Best name similarity across all name combinations
        double nameSim = bestNameSimilarity(a, b);

        // Below minimum name threshold — not a match regardless of other signals
        if (nameSim < config.nameThreshold()) {
            // Exception: if identifiers match exactly, still consider it
            if (!hasMatchingIdentifier(a, b)) {
                return 0.0;
            }
            // Identifier match with weak name match — give it a chance
            nameSim = Math.max(nameSim, 0.5);
        }

        double score = nameSim;

        // Signal 2: Identifier overlap bonus
        if (hasMatchingIdentifier(a, b)) {
            score += config.identifierMatchWeight();
        }

        // Signal 3: Date of birth overlap bonus
        if (hasMatchingDob(a, b)) {
            score += config.dobMatchWeight();
        }

        return Math.min(score, 1.0);
    }

    /**
     * Computes the best Jaro-Winkler name similarity across all name combinations of two entities.
     */
    private static double bestNameSimilarity(SanctionedEntity a, SanctionedEntity b) {
        List<String> namesA = allNormalizedNames(a);
        List<String> namesB = allNormalizedNames(b);

        double best = 0.0;
        for (String nameA : namesA) {
            for (String nameB : namesB) {
                double sim = JaroWinkler.similarity(nameA, nameB);
                if (sim > best) {
                    best = sim;
                    if (best >= 1.0) return 1.0;
                }
            }
        }
        return best;
    }

    private static List<String> allNormalizedNames(SanctionedEntity entity) {
        List<String> names = new ArrayList<>(1 + entity.aliases().size());
        names.add(NameNormalizer.normalize(entity.primaryName().fullName()));

        // Add family name as standalone (handles "DOE, John" matching "John DOE")
        if (entity.primaryName().familyName() != null) {
            String family = NameNormalizer.normalize(entity.primaryName().familyName());
            String given = entity.primaryName().givenName() != null
                    ? NameNormalizer.normalize(entity.primaryName().givenName())
                    : "";
            if (!family.isEmpty() && !given.isEmpty()) {
                // Generate both orderings: "family given" and "given family"
                names.add(family + " " + given);
                names.add(given + " " + family);
            }
        }

        for (NameInfo alias : entity.aliases()) {
            String normalized = NameNormalizer.normalize(alias.fullName());
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
        return names;
    }

    /**
     * Checks if two entities share any matching identifier (exact value match for the same type).
     */
    private static boolean hasMatchingIdentifier(SanctionedEntity a, SanctionedEntity b) {
        if (a.identifiers().isEmpty() || b.identifiers().isEmpty()) {
            return false;
        }
        // Build a set of "type:normalizedValue" for entity A
        Set<String> aIds = new java.util.HashSet<>();
        for (Identifier id : a.identifiers()) {
            aIds.add(identifierKey(id));
        }
        for (Identifier id : b.identifiers()) {
            if (aIds.contains(identifierKey(id))) {
                return true;
            }
        }
        return false;
    }

    private static String identifierKey(Identifier id) {
        return id.type() + ":" + id.value().strip().toUpperCase();
    }

    /**
     * Checks if two entities share any matching date of birth.
     */
    private static boolean hasMatchingDob(SanctionedEntity a, SanctionedEntity b) {
        if (a.datesOfBirth().isEmpty() || b.datesOfBirth().isEmpty()) {
            return false;
        }
        for (LocalDate dobA : a.datesOfBirth()) {
            for (LocalDate dobB : b.datesOfBirth()) {
                if (dobA.equals(dobB)) {
                    return true;
                }
            }
        }
        return false;
    }
}
