package dev.sieve.match;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.algorithm.DoubleMetaphone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Double Metaphone codes of every indexed name, encoded once per index version, with an inverted
 * map from each code to the entities that have a word with that code.
 *
 * <p>A phonetic match needs every query word to sound like some word of a name, so an entity can
 * only match if it holds a word for each query word. The entities holding a word for the rarest
 * query word are therefore all the candidates, which replaces a scan of the whole index.
 *
 * <p>Thread-safe. Rebuilds when the index content version changes; while one thread rebuilds,
 * others keep reading the previous snapshot.
 */
final class PhoneticIndex {

    private static final Logger log = LoggerFactory.getLogger(PhoneticIndex.class);

    /**
     * One entity's encoded names.
     *
     * @param entity the entity
     * @param fullNames codes of each full name (primary first, then aliases), one array per name
     *     with one code per word
     * @param components codes of each name component, in {@link
     *     NormalizedNameCache.NormalizedEntry#nameComponents()} order
     */
    record Entry(
            SanctionedEntity entity,
            DoubleMetaphone.PhoneticCode[][] fullNames,
            DoubleMetaphone.PhoneticCode[][] components) {}

    private volatile List<Entry> entries = List.of();
    private volatile Map<String, int[]> entriesByCode = Map.of();
    private volatile long lastKnownVersion = -1;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    /** Rebuilds the codes when the index changed; the name cache must already be built. */
    void ensureBuilt(EntityIndex index, NormalizedNameCache nameCache) {
        if (index.version() != lastKnownVersion && rebuilding.compareAndSet(false, true)) {
            try {
                rebuild(index, nameCache);
            } finally {
                rebuilding.set(false);
            }
        }
    }

    /**
     * Entries that hold a word sounding like each of the query words, narrowed to the rarest query
     * word. Empty when a query word has no code, since such a word can never match.
     */
    List<Entry> candidates(DoubleMetaphone.PhoneticCode[] queryCodes) {
        Map<String, int[]> byCode = entriesByCode;
        List<Entry> snapshot = entries;
        int[] best = null;
        for (DoubleMetaphone.PhoneticCode code : queryCodes) {
            if (code.primary().isEmpty()) {
                return List.of();
            }
            int[] ids = union(byCode.get(code.primary()), byCode.get(code.alternate()));
            if (best == null || ids.length < best.length) {
                best = ids;
            }
        }
        if (best == null) {
            return List.of();
        }
        List<Entry> result = new ArrayList<>(best.length);
        for (int id : best) {
            result.add(snapshot.get(id));
        }
        return result;
    }

    /** Encodes each word of a normalised name. */
    static DoubleMetaphone.PhoneticCode[] encode(String normalizedName) {
        String[] words = normalizedName.split("\\s+");
        DoubleMetaphone.PhoneticCode[] codes = new DoubleMetaphone.PhoneticCode[words.length];
        for (int i = 0; i < words.length; i++) {
            codes[i] = DoubleMetaphone.encode(words[i]);
        }
        return codes;
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) {
            return b == null ? new int[0] : b;
        }
        if (b == null || a == b) {
            return a;
        }
        int[] merged = new int[a.length + b.length];
        int i = 0;
        int j = 0;
        int n = 0;
        while (i < a.length || j < b.length) {
            int next;
            if (j >= b.length || (i < a.length && a[i] < b[j])) {
                next = a[i++];
            } else if (i >= a.length || b[j] < a[i]) {
                next = b[j++];
            } else {
                next = a[i++];
                j++;
            }
            merged[n++] = next;
        }
        return Arrays.copyOf(merged, n);
    }

    private void rebuild(EntityIndex index, NormalizedNameCache nameCache) {
        long version = index.version();
        if (version == lastKnownVersion) {
            return;
        }
        List<Entry> newEntries = new ArrayList<>(index.size());
        Map<String, List<Integer>> postings = new HashMap<>();
        for (SanctionedEntity entity : index.all()) {
            NormalizedNameCache.NormalizedEntry cached = nameCache.get(entity);
            List<String> names = new ArrayList<>(cached.aliases().size() + 1);
            names.add(cached.primaryName());
            names.addAll(cached.aliases());
            DoubleMetaphone.PhoneticCode[][] fullNames = encodeAll(names);
            DoubleMetaphone.PhoneticCode[][] components = encodeAll(cached.nameComponents());
            int id = newEntries.size();
            newEntries.add(new Entry(entity, fullNames, components));
            post(postings, fullNames, id);
            post(postings, components, id);
        }
        Map<String, int[]> newByCode = HashMap.newHashMap(postings.size());
        postings.forEach(
                (code, ids) ->
                        newByCode.put(
                                code,
                                ids.stream().mapToInt(Integer::intValue).distinct().toArray()));

        entries = newEntries;
        entriesByCode = newByCode;
        lastKnownVersion = version;
        log.info(
                "Phonetic index rebuilt [entities={}, codes={}]",
                newEntries.size(),
                newByCode.size());
    }

    private static DoubleMetaphone.PhoneticCode[][] encodeAll(List<String> names) {
        DoubleMetaphone.PhoneticCode[][] codes = new DoubleMetaphone.PhoneticCode[names.size()][];
        for (int i = 0; i < names.size(); i++) {
            codes[i] = encode(names.get(i));
        }
        return codes;
    }

    /** Adds the entry id under each word's codes; ids arrive in ascending order. */
    private static void post(
            Map<String, List<Integer>> postings, DoubleMetaphone.PhoneticCode[][] names, int id) {
        for (DoubleMetaphone.PhoneticCode[] name : names) {
            for (DoubleMetaphone.PhoneticCode code : name) {
                if (code.primary().isEmpty()) {
                    continue;
                }
                add(postings, code.primary(), id);
                if (!code.alternate().isEmpty() && !code.alternate().equals(code.primary())) {
                    add(postings, code.alternate(), id);
                }
            }
        }
    }

    private static void add(Map<String, List<Integer>> postings, String code, int id) {
        List<Integer> ids = postings.computeIfAbsent(code, k -> new ArrayList<>());
        if (ids.isEmpty() || ids.getLast() != id) {
            ids.add(id);
        }
    }
}
