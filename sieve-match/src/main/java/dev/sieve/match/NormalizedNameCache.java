package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache of pre-normalized entity names for use by match engines.
 *
 * <p>Normalizing names (lowercasing, trimming, collapsing whitespace) is expensive when repeated
 * for every entity on every query. This cache pre-computes normalized forms once when entities are
 * loaded and serves them on subsequent lookups, eliminating redundant work.
 *
 * <p>Thread-safe. Automatically rebuilds when the index size changes (indicating new data).
 */
public final class NormalizedNameCache {

    private static final Logger log = LoggerFactory.getLogger(NormalizedNameCache.class);

    private final ConcurrentHashMap<String, NormalizedEntry> cache = new ConcurrentHashMap<>();
    private volatile int lastKnownSize = -1;

    /**
     * Pre-normalized names for a single entity.
     *
     * @param primaryName the normalized primary name
     * @param aliases the normalized alias names, in the same order as the entity's alias list
     */
    public record NormalizedEntry(String primaryName, List<String> aliases) {}

    /**
     * Ensures the cache is built and up-to-date for the given index.
     *
     * <p>If the index size has changed since the last build, the cache is rebuilt. This method
     * should be called once at the start of each screening operation.
     *
     * @param index the entity index to cache names for
     */
    public void ensureBuilt(EntityIndex index) {
        int currentSize = index.size();
        if (currentSize != lastKnownSize) {
            rebuild(index);
        }
    }

    /**
     * Returns the pre-normalized names for the given entity, computing on cache miss.
     *
     * @param entity the entity to look up
     * @return the pre-normalized entry, never {@code null}
     */
    public NormalizedEntry get(SanctionedEntity entity) {
        return cache.computeIfAbsent(entity.id(), id -> computeEntry(entity));
    }

    /**
     * Clears the cache, forcing a full rebuild on the next {@link #ensureBuilt} call.
     */
    public void invalidate() {
        cache.clear();
        lastKnownSize = -1;
    }

    private synchronized void rebuild(EntityIndex index) {
        int currentSize = index.size();
        if (currentSize == lastKnownSize) {
            return; // another thread already rebuilt
        }

        cache.clear();
        for (SanctionedEntity entity : index.all()) {
            cache.put(entity.id(), computeEntry(entity));
        }
        lastKnownSize = currentSize;
        log.info("Normalized name cache rebuilt [entries={}]", cache.size());
    }

    private static NormalizedEntry computeEntry(SanctionedEntity entity) {
        String primary = NameNormalizer.normalize(entity.primaryName().fullName());

        List<NameInfo> aliasList = entity.aliases();
        String[] normalized = new String[aliasList.size()];
        for (int i = 0; i < aliasList.size(); i++) {
            normalized[i] = NameNormalizer.normalize(aliasList.get(i).fullName());
        }

        return new NormalizedEntry(primary, List.of(normalized));
    }
}
