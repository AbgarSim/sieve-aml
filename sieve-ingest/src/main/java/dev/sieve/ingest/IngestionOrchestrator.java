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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final Map<ListSource, ListMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Map<ListSource, ProviderResult> lastResults = new ConcurrentHashMap<>();

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

        List<ListProvider> toFetch =
                providers.stream()
                        .filter(p -> sources == null || sources.contains(p.source()))
                        .toList();

        log.info(
                "Starting parallel ingestion cycle [providers={}, active={}, filter={}]",
                providers.size(),
                toFetch.size(),
                sources == null ? "ALL" : sources);

        Map<ListSource, ProviderResult> results = new ConcurrentHashMap<>();
        AtomicInteger totalEntities = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(toFetch.size());

        // Mark skipped providers
        for (ListProvider provider : providers) {
            if (sources != null && !sources.contains(provider.source())) {
                results.put(provider.source(), ProviderResult.skipped(provider.source()));
                log.debug("Skipping provider [source={}]", provider.source());
            }
        }

        // Fetch all active providers in parallel using virtual threads
        for (ListProvider provider : toFetch) {
            Thread.ofVirtual()
                    .name("ingest-" + provider.source().name().toLowerCase())
                    .start(
                            () -> {
                                ListSource source = provider.source();
                                Instant providerStart = Instant.now();
                                try {
                                    List<SanctionedEntity> entities = provider.fetch();
                                    index.addAll(entities);
                                    Duration providerDuration =
                                            Duration.between(providerStart, Instant.now());

                                    ProviderResult success =
                                            ProviderResult.success(
                                                    source, entities.size(), providerDuration);
                                    results.put(source, success);
                                    lastResults.put(source, success);
                                    ListMetadata metadata = provider.metadata();
                                    if (metadata != null) {
                                        metadataCache.put(source, metadata);
                                    }
                                    totalEntities.addAndGet(entities.size());

                                    log.info(
                                            "Provider complete [source={}, entities={}, duration={}ms]",
                                            source,
                                            entities.size(),
                                            providerDuration.toMillis());

                                } catch (Exception e) {
                                    Duration providerDuration =
                                            Duration.between(providerStart, Instant.now());
                                    ProviderResult failure =
                                            ProviderResult.failed(
                                                    source,
                                                    providerDuration,
                                                    e.getMessage() != null
                                                            ? e.getMessage()
                                                            : e.getClass().getSimpleName());
                                    results.put(source, failure);
                                    lastResults.put(source, failure);
                                    log.error(
                                            "Provider failed [source={}, duration={}ms]",
                                            source,
                                            providerDuration.toMillis(),
                                            e);
                                } finally {
                                    latch.countDown();
                                }
                            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Ingestion interrupted while waiting for providers");
        }

        Duration totalDuration = Duration.between(start, Instant.now());
        IngestionReport report =
                new IngestionReport(new EnumMap<>(results), totalEntities.get(), totalDuration);

        log.info(
                "Ingestion cycle complete [totalEntities={}, duration={}ms, indexSize={}]",
                totalEntities.get(),
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

    /**
     * Returns the outcome of the most recent fetch attempt for a list.
     *
     * @param source the list
     * @return the last success or failure, or empty if the list was never fetched
     */
    public Optional<ProviderResult> lastResult(ListSource source) {
        return Optional.ofNullable(lastResults.get(source));
    }

    /**
     * Returns the status of a list for status endpoints: {@code FAILED} when the most recent fetch
     * failed (entities from an earlier load may still be served), otherwise {@code LOADED} or
     * {@code EMPTY} by entity count.
     *
     * @param source the list
     * @param entityCount entities of the list currently in the index
     * @return {@code LOADED}, {@code EMPTY} or {@code FAILED}
     */
    public String status(ListSource source, int entityCount) {
        boolean failed =
                lastResult(source)
                        .map(r -> r.status() == ProviderResult.Status.FAILED)
                        .orElse(false);
        if (failed) {
            return "FAILED";
        }
        return entityCount > 0 ? "LOADED" : "EMPTY";
    }
}
