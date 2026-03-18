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

    /**
     * Maximum allowed ratio between query length and candidate name length. Names differing by more
     * than this factor cannot produce high Jaro-Winkler scores.
     */
    private static final double MAX_LENGTH_RATIO = 3.0;

    /** trigram → set of entity IDs */
    private volatile Map<String, List<String>> trigramToEntityIds = Map.of();

    /** entity ID → entity (for fast lookup after candidate selection) */
    private volatile Map<String, SanctionedEntity> entityById = Map.of();

    /** entity ID → shortest normalized name length (primary or alias) */
    private volatile Map<String, int[]> entityNameLengths = Map.of();

    private volatile int lastKnownSize = -1;

    /**
     * Ensures the index is built and up-to-date for the given entity index.
     *
     * @param index the entity index
     * @param nameCache the pre-normalized name cache (must already be built)
     */
    public void ensureBuilt(EntityIndex index, NormalizedNameCache nameCache) {
        int currentSize = index.size();
        if (currentSize != lastKnownSize) {
            rebuild(index, nameCache);
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

        // Count trigram hits per entity ID
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

        // Filter by minimum overlap and length ratio
        int minHits = Math.max(1, (int) (queryTrigrams.size() * MIN_OVERLAP_RATIO));
        int queryLen = normalizedQuery.length();
        List<SanctionedEntity> result = new ArrayList<>();
        Map<String, SanctionedEntity> entitySnapshot = entityById;
        Map<String, int[]> lengthSnapshot = entityNameLengths;

        for (Map.Entry<String, int[]> entry : hitCounts.entrySet()) {
            if (entry.getValue()[0] >= minHits) {
                // Length pre-filter: skip if name lengths diverge too much
                int[] lengths = lengthSnapshot.get(entry.getKey());
                if (lengths != null) {
                    int shortest = lengths[0];
                    int longest = lengths[1];
                    if (queryLen > 0 && shortest > 0) {
                        double ratio =
                                (double) Math.max(queryLen, longest) / Math.min(queryLen, shortest);
                        if (ratio > MAX_LENGTH_RATIO) {
                            continue;
                        }
                    }
                }
                SanctionedEntity entity = entitySnapshot.get(entry.getKey());
                if (entity != null) {
                    result.add(entity);
                }
            }
        }

        return result;
    }

    /** Returns the total number of indexed entities. */
    public int size() {
        return entityById.size();
    }

    private synchronized void rebuild(EntityIndex index, NormalizedNameCache nameCache) {
        int currentSize = index.size();
        if (currentSize == lastKnownSize) {
            return; // another thread already rebuilt
        }

        HashMap<String, List<String>> newTrigramMap = new HashMap<>();
        HashMap<String, SanctionedEntity> newEntityById = new HashMap<>(currentSize * 2);

        for (SanctionedEntity entity : index.all()) {
            String entityId = entity.id();
            newEntityById.put(entityId, entity);

            NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);

            // Index primary name trigrams
            for (String trigram : trigrams(cached.primaryName())) {
                newTrigramMap.computeIfAbsent(trigram, k -> new ArrayList<>()).add(entityId);
            }

            // Index alias trigrams
            for (String alias : cached.aliases()) {
                for (String trigram : trigrams(alias)) {
                    newTrigramMap.computeIfAbsent(trigram, k -> new ArrayList<>()).add(entityId);
                }
            }
        }

        // Deduplicate entity IDs per trigram (an entity may appear multiple times if
        // the same trigram occurs in primary name and an alias)
        for (Map.Entry<String, List<String>> entry : newTrigramMap.entrySet()) {
            List<String> ids = entry.getValue();
            if (ids.size() > 1) {
                entry.setValue(List.copyOf(new HashSet<>(ids)));
            }
        }

        // Build name length bounds per entity
        HashMap<String, int[]> newLengths = new HashMap<>(currentSize * 2);
        for (SanctionedEntity entity : index.all()) {
            NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);
            int shortest = cached.primaryName().length();
            int longest = shortest;
            for (String alias : cached.aliases()) {
                int len = alias.length();
                if (len < shortest) shortest = len;
                if (len > longest) longest = len;
            }
            newLengths.put(entity.id(), new int[] {shortest, longest});
        }

        trigramToEntityIds = newTrigramMap;
        entityById = newEntityById;
        entityNameLengths = newLengths;
        lastKnownSize = currentSize;

        log.info(
                "N-gram index rebuilt [entities={}, uniqueTrigrams={}]",
                newEntityById.size(),
                newTrigramMap.size());
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
        HashSet<String> result = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            result.add(s.substring(i, i + N));
        }
        return result;
    }
}
