package dev.sieve.ingest;

import dev.sieve.core.model.ListSource;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Summary report produced by the {@link IngestionOrchestrator} after a fetch cycle.
 *
 * @param results per-provider outcome keyed by {@link ListSource}
 * @param totalEntitiesLoaded total number of entities successfully loaded across all providers
 * @param totalDuration wall-clock time for the entire ingestion cycle
 */
public record IngestionReport(
        Map<ListSource, ProviderResult> results, int totalEntitiesLoaded, Duration totalDuration) {

    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException if {@code results} or {@code totalDuration} is {@code null}
     */
    public IngestionReport {
        Objects.requireNonNull(results, "results must not be null");
        Objects.requireNonNull(totalDuration, "totalDuration must not be null");
        results = Map.copyOf(results);
    }
}
