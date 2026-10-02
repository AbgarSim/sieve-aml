package dev.sieve.core.stats;

import java.util.Map;

/**
 * Country values that could not be resolved to a country code, so data gaps stay visible.
 *
 * @param occurrences unresolved non-blank values seen, counted once per entity and field value
 * @param topValues the most frequent unresolved values and how often each was seen
 */
public record UnresolvedCountries(int occurrences, Map<String, Integer> topValues) {

    public UnresolvedCountries {
        topValues = topValues == null ? Map.of() : Map.copyOf(topValues);
    }
}
