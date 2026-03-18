package dev.sieve.ingest;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the ingestion of sanctions lists from all registered {@link ListProvider}s.
 *
 * <p>Runs each provider, merges results into the {@link EntityIndex}, and produces a detailed
 * {@link IngestionReport}. Supports both full and selective (source-filtered) ingestion runs.
 */
public final class IngestionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestionOrchestrator.class);

    private final List<ListProvider> providers;
    private final Map<ListSource, ListMetadata> metadataCache = new EnumMap<>(ListSource.class);

    /**
     * Creates an orchestrator with the given list of providers.
     *
     * @param providers the providers to orchestrate, must not be {@code null} or empty
     * @throws NullPointerException if {@code providers} is {@code null}
     * @throws IllegalArgumentException if {@code providers} is empty
     */
    public IngestionOrchestrator(List<ListProvider> providers) {
        Objects.requireNonNull(providers, "providers must not be null");
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("At least one ListProvider is required");
        }
        this.providers = List.copyOf(providers);
    }

    /**
     * Runs all registered providers and loads their entities into the given index.
     *
     * @param index the entity index to populate
     * @return an ingestion report summarizing the results
     */
    public IngestionReport ingest(EntityIndex index) {
        return ingest(index, null);
    }

    /**
     * Runs only the providers matching the given sources and loads their entities into the index.
     *
     * <p>Providers not in the {@code sources} set are reported as {@link
     * ProviderResult.Status#SKIPPED}.
     *
     * @param index the entity index to populate
     * @param sources the sources to include, or {@code null} to run all providers
     * @return an ingestion report summarizing the results
     */
    public IngestionReport ingest(EntityIndex index, Set<ListSource> sources) {
        Objects.requireNonNull(index, "index must not be null");
        Instant start = Instant.now();
        Map<ListSource, ProviderResult> results = new EnumMap<>(ListSource.class);
        int totalEntities = 0;

        log.info(
                "Starting ingestion cycle [providers={}, filter={}]",
                providers.size(),
                sources == null ? "ALL" : sources);

        for (ListProvider provider : providers) {
            ListSource source = provider.source();

            if (sources != null && !sources.contains(source)) {
                results.put(source, ProviderResult.skipped(source));
                log.debug("Skipping provider [source={}]", source);
                continue;
            }

            Instant providerStart = Instant.now();
            try {
                List<SanctionedEntity> entities = provider.fetch();
                index.addAll(entities);
                Duration providerDuration = Duration.between(providerStart, Instant.now());

                results.put(
                        source, ProviderResult.success(source, entities.size(), providerDuration));
                metadataCache.put(source, provider.metadata());
                totalEntities += entities.size();

                log.info(
                        "Provider complete [source={}, entities={}, duration={}ms]",
                        source,
                        entities.size(),
                        providerDuration.toMillis());

            } catch (Exception e) {
                Duration providerDuration = Duration.between(providerStart, Instant.now());
                results.put(
                        source, ProviderResult.failed(source, providerDuration, e.getMessage()));
                log.error(
                        "Provider failed [source={}, duration={}ms]",
                        source,
                        providerDuration.toMillis(),
                        e);
            }
        }

        Duration totalDuration = Duration.between(start, Instant.now());
        IngestionReport report = new IngestionReport(results, totalEntities, totalDuration);

        log.info(
                "Ingestion cycle complete [totalEntities={}, duration={}ms, indexSize={}]",
                totalEntities,
                totalDuration.toMillis(),
                index.size());

        return report;
    }

    /**
     * Returns cached metadata for the given source from the last successful fetch.
     *
     * @param source the list source
     * @return the metadata, or {@code null} if the source has not been fetched
     */
    public ListMetadata getMetadata(ListSource source) {
        return metadataCache.get(source);
    }
}
