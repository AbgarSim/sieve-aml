package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.SanctionedEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private volatile Map<String, NormalizedEntry> cache = Map.of();
    private volatile int lastKnownSize = -1;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /**
     * Pre-normalized names for a single entity.
     *
     * @param primaryName the normalized primary name
     * @param aliases the normalized alias names, in the same order as the entity's alias list
     * @param nameComponents normalized individual name parts (familyName, givenName) from primary
     *     and aliases, deduplicated, for component-level matching
     */
    public record NormalizedEntry(
            String primaryName, List<String> aliases, List<String> nameComponents) {}

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
            if (rebuilding.compareAndSet(false, true)) {
                try {
                    rebuild(index);
                } finally {
                    rebuilding.set(false);
                }
            }
            // Other threads continue using the stale (but valid) snapshot
        }
    }

    /**
     * Returns the pre-normalized names for the given entity, computing on cache miss.
     *
     * @param entity the entity to look up
     * @return the pre-normalized entry, never {@code null}
     */
    public NormalizedEntry get(SanctionedEntity entity) {
        NormalizedEntry entry = cache.get(entity.id());
        return entry != null ? entry : computeEntry(entity);
    }

    /** Clears the cache, forcing a full rebuild on the next {@link #ensureBuilt} call. */
    public void invalidate() {
        lastKnownSize = -1;
    }

    private void rebuild(EntityIndex index) {
        int currentSize = index.size();
        if (currentSize == lastKnownSize) {
            return;
        }

        // Build a completely new map — old map stays live for concurrent readers
        HashMap<String, NormalizedEntry> newCache = HashMap.newHashMap(currentSize);
        for (SanctionedEntity entity : index.all()) {
            newCache.put(entity.id(), computeEntry(entity));
        }

        // Atomic swap — readers instantly see the new snapshot
        cache = Map.copyOf(newCache);
        lastKnownSize = currentSize;
        log.info("Normalized name cache rebuilt [entries={}]", newCache.size());
    }

    private static NormalizedEntry computeEntry(SanctionedEntity entity) {
        String primary = NameNormalizer.normalize(entity.primaryName().fullName());

        List<NameInfo> aliasList = entity.aliases();
        String[] normalized = new String[aliasList.size()];
        for (int i = 0; i < aliasList.size(); i++) {
            normalized[i] = NameNormalizer.normalize(aliasList.get(i).fullName());
        }

        // Collect distinct normalized name components (familyName, givenName)
        Set<String> components = new LinkedHashSet<>();
        collectNameComponents(entity.primaryName(), components);
        for (NameInfo alias : aliasList) {
            collectNameComponents(alias, components);
        }
        // Remove components that are identical to the full primary name or an alias
        // (they'd already be matched at full-name level)
        components.remove(primary);
        for (String alias : normalized) {
            components.remove(alias);
        }

        return new NormalizedEntry(primary, List.of(normalized), List.copyOf(components));
    }

    private static void collectNameComponents(NameInfo name, Set<String> components) {
        addIfPresent(name.familyName(), components);
        addIfPresent(name.givenName(), components);
    }

    private static void addIfPresent(String value, Set<String> components) {
        if (value != null && !value.isBlank()) {
            String normalized = NameNormalizer.normalize(value);
            if (!normalized.isEmpty()) {
                components.add(normalized);
            }
        }
    }
}
