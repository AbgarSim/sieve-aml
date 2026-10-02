package dev.sieve.cli.snapshot;

import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches lists in parallel for a snapshot, keeping each list's entities apart.
 *
 * <p>Unlike {@link dev.sieve.ingest.IngestionOrchestrator}, this does not load an index, so
 * entities with the same raw id on different lists never overwrite each other.
 */
public final class SnapshotFetcher {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFetcher.class);

    private final List<ListProvider> providers;
    private final Predicate<ListSource> needsMissingKey;

    /**
     * Creates a fetcher.
     *
     * @param providers the providers to run
     * @param needsMissingKey true for lists whose API key is not configured; they are not fetched
     */
    public SnapshotFetcher(List<ListProvider> providers, Predicate<ListSource> needsMissingKey) {
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers"));
        this.needsMissingKey = Objects.requireNonNull(needsMissingKey, "needsMissingKey");
    }

    /**
     * Fetches the selected lists.
     *
     * @param only lists to fetch, or empty for all
     * @return one outcome per provider, in provider order
     */
    public List<FetchedSource> fetch(Set<ListSource> only) {
        Objects.requireNonNull(only, "only must not be null");
        List<Future<FetchedSource>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ListProvider provider : providers) {
                futures.add(executor.submit(() -> fetchOne(provider, only)));
            }
            List<FetchedSource> results = new ArrayList<>(futures.size());
            for (Future<FetchedSource> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Snapshot fetch interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Snapshot fetch failed", e.getCause());
        }
    }

    private FetchedSource fetchOne(ListProvider provider, Set<ListSource> only) {
        ListSource source = provider.source();
        if (!only.isEmpty() && !only.contains(source)) {
            return outcome(
                    source, FetchedSource.Status.SKIPPED, List.of(), null, Duration.ZERO, null);
        }
        if (needsMissingKey.test(source)) {
            log.warn("Skipping provider without API key [source={}]", source);
            return outcome(
                    source,
                    FetchedSource.Status.NEEDS_KEY,
                    List.of(),
                    provider,
                    Duration.ZERO,
                    null);
        }
        Instant start = Instant.now();
        try {
            List<SanctionedEntity> entities = provider.fetch();
            Duration duration = Duration.between(start, Instant.now());
            log.info(
                    "Provider fetched [source={}, entities={}, duration={}ms]",
                    source,
                    entities.size(),
                    duration.toMillis());
            FetchedSource.Status status =
                    entities.isEmpty() ? FetchedSource.Status.EMPTY : FetchedSource.Status.LOADED;
            return outcome(source, status, entities, provider, duration, null);
        } catch (Exception e) {
            Duration duration = Duration.between(start, Instant.now());
            log.error("Provider failed [source={}, duration={}ms]", source, duration.toMillis(), e);
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return outcome(
                    source, FetchedSource.Status.FAILED, List.of(), provider, duration, message);
        }
    }

    private static FetchedSource outcome(
            ListSource source,
            FetchedSource.Status status,
            List<SanctionedEntity> entities,
            ListProvider provider,
            Duration duration,
            String error) {
        return new FetchedSource(
                source,
                status,
                entities,
                Optional.ofNullable(provider).map(ListProvider::metadata),
                duration,
                Optional.ofNullable(error));
    }
}
