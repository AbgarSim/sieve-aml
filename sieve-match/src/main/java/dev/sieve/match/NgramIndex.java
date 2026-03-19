package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.SanctionedEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Trigram-based inverted index for fast candidate selection.
 *
 * <p>At build time, every entity's normalized primary name and alias names are decomposed into
 * overlapping 3-character trigrams. An inverted map is constructed from each trigram to the set of
 * entity IDs containing that trigram.
 *
 * <p>At query time, the query string is decomposed into trigrams, the candidate entity IDs are
 * collected by trigram overlap, and only entities sharing a minimum fraction of trigrams are
 * returned. This typically reduces the candidate set from tens of thousands to tens of entities.
 *
 * <p>Thread-safe. Automatically rebuilds when the underlying index size changes.
 */
public final class NgramIndex {

    private static final Logger log = LoggerFactory.getLogger(NgramIndex.class);

    /** Trigram length. */
    private static final int N = 3;

    /**
     * Minimum fraction of query trigrams that a candidate must share to be returned. Lower values
     * cast a wider net (more candidates, fewer misses).
     */
    private static final double MIN_OVERLAP_RATIO = 0.3;

    /** trigram → set of entity IDs */
    private volatile Map<String, List<String>> trigramToEntityIds = Map.of();

    /** entity ID → entity (for fast lookup after candidate selection) */
    private volatile Map<String, SanctionedEntity> entityById = Map.of();

    private volatile int lastKnownSize = -1;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /**
     * Ensures the index is built and up-to-date for the given entity index.
     *
     * <p>Only one thread performs the rebuild; concurrent callers continue using the previous
     * (stale but valid) snapshot, avoiding any blocking on the screening hot path.
     *
     * @param index the entity index
     * @param nameCache the pre-normalized name cache (must already be built)
     */
    public void ensureBuilt(EntityIndex index, NormalizedNameCache nameCache) {
        int currentSize = index.size();
        if (currentSize != lastKnownSize) {
            if (rebuilding.compareAndSet(false, true)) {
                try {
                    rebuild(index, nameCache);
                } finally {
                    rebuilding.set(false);
                }
            }
            // Other threads continue using the stale (but valid) snapshot
        }
    }

    /**
     * Returns candidate entities whose names share trigrams with the given normalized query.
     *
     * <p>Candidates are ranked by the number of shared trigrams and filtered by a minimum overlap
     * ratio. The result is a subset of all entities — typically {@literal <}1%.
     *
     * @param normalizedQuery the query string, already normalized
     * @return candidate entities, never {@code null}
     */
    public Collection<SanctionedEntity> candidates(String normalizedQuery) {
        Set<String> queryTrigrams = trigrams(normalizedQuery);
        if (queryTrigrams.isEmpty()) {
            // Very short query — fall back to all entities
            return entityById.values();
        }

        HashMap<String, int[]> hitCounts = countTrigramHits(queryTrigrams);
        int minHits = Math.max(1, (int) (queryTrigrams.size() * MIN_OVERLAP_RATIO));
        return filterCandidates(hitCounts, minHits);
    }

    private HashMap<String, int[]> countTrigramHits(Set<String> queryTrigrams) {
        HashMap<String, int[]> hitCounts = new HashMap<>();
        Map<String, List<String>> snapshot = trigramToEntityIds;

        for (String trigram : queryTrigrams) {
            List<String> entityIds = snapshot.get(trigram);
            if (entityIds != null) {
                for (String entityId : entityIds) {
                    hitCounts.computeIfAbsent(entityId, k -> new int[1])[0]++;
                }
            }
        }
        return hitCounts;
    }

    private List<SanctionedEntity> filterCandidates(HashMap<String, int[]> hitCounts, int minHits) {
        List<SanctionedEntity> result = new ArrayList<>();
        Map<String, SanctionedEntity> entitySnapshot = entityById;

        for (Map.Entry<String, int[]> entry : hitCounts.entrySet()) {
            if (entry.getValue()[0] < minHits) {
                continue;
            }
            SanctionedEntity entity = entitySnapshot.get(entry.getKey());
            if (entity != null) {
                result.add(entity);
            }
        }
        return result;
    }

    /** Returns the total number of indexed entities. */
    public int size() {
        return entityById.size();
    }

    private void rebuild(EntityIndex index, NormalizedNameCache nameCache) {
        int currentSize = index.size();
        if (currentSize == lastKnownSize) {
            return;
        }

        // Build entirely new structures — old volatile refs stay live for concurrent readers
        HashMap<String, List<String>> newTrigramMap = new HashMap<>();
        HashMap<String, SanctionedEntity> newEntityById = HashMap.newHashMap(currentSize);
        indexAllEntities(index, nameCache, newTrigramMap, newEntityById);
        deduplicateTrigramEntries(newTrigramMap);

        // Atomic swap — both volatile refs update together
        trigramToEntityIds = newTrigramMap;
        entityById = newEntityById;
        lastKnownSize = currentSize;

        log.info(
                "N-gram index rebuilt [entities={}, uniqueTrigrams={}]",
                newEntityById.size(),
                newTrigramMap.size());
    }

    private void indexAllEntities(
            EntityIndex index,
            NormalizedNameCache nameCache,
            HashMap<String, List<String>> trigramMap,
            HashMap<String, SanctionedEntity> entityMap) {
        for (SanctionedEntity entity : index.all()) {
            String entityId = entity.id();
            entityMap.put(entityId, entity);
            NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);
            indexEntityTrigrams(entityId, cached, trigramMap);
        }
    }

    private static void indexEntityTrigrams(
            String entityId,
            NormalizedNameCache.NormalizedEntry cached,
            HashMap<String, List<String>> trigramMap) {
        for (String trigram : trigrams(cached.primaryName())) {
            trigramMap.computeIfAbsent(trigram, k -> new ArrayList<>()).add(entityId);
        }
        for (String alias : cached.aliases()) {
            for (String trigram : trigrams(alias)) {
                trigramMap.computeIfAbsent(trigram, k -> new ArrayList<>()).add(entityId);
            }
        }
        for (String component : cached.nameComponents()) {
            for (String trigram : trigrams(component)) {
                trigramMap.computeIfAbsent(trigram, k -> new ArrayList<>()).add(entityId);
            }
        }
    }

    private static void deduplicateTrigramEntries(HashMap<String, List<String>> trigramMap) {
        for (Map.Entry<String, List<String>> entry : trigramMap.entrySet()) {
            List<String> ids = entry.getValue();
            if (ids.size() > 1) {
                entry.setValue(List.copyOf(new HashSet<>(ids)));
            }
        }
    }

    /**
     * Extracts the set of character trigrams from a string.
     *
     * <p>For example, {@code "putin"} yields {@code {"put", "uti", "tin"}}.
     *
     * @param s the input string
     * @return the set of trigrams, may be empty for strings shorter than {@link #N}
     */
    static Set<String> trigrams(String s) {
        if (s == null || s.length() < N) {
            return Set.of();
        }
        int count = s.length() - N + 1;
        HashSet<String> result = HashSet.newHashSet(count);
        for (int i = 0; i < count; i++) {
            result.add(s.substring(i, i + N));
        }
        return result;
    }
}
