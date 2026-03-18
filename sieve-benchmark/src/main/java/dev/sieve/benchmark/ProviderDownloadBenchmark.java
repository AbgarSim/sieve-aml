package dev.sieve.benchmark;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.ingest.uk.UkHmtProvider;
import dev.sieve.ingest.un.UnConsolidatedProvider;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Benchmarks full download + parse time for all four sanctions list providers.
 *
 * <p>This is not a JMH benchmark — it performs real HTTP downloads and measures wall-clock time.
 * Each provider is fetched once sequentially, then once in parallel.
 */
public final class ProviderDownloadBenchmark {

    private static final Logger log = LoggerFactory.getLogger(ProviderDownloadBenchmark.class);

    private final Map<String, ListProvider> providers;

    public ProviderDownloadBenchmark() {
        this.providers = new LinkedHashMap<>();
        providers.put(
                "OFAC SDN",
                new OfacSdnProvider(
                        URI.create(
                                "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML")));
        providers.put(
                "UK HMT",
                new UkHmtProvider(
                        URI.create(
                                "https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml")));
        providers.put(
                "EU Consolidated",
                new EuConsolidatedProvider(
                        URI.create(
                                "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw")));
        providers.put(
                "UN Consolidated",
                new UnConsolidatedProvider(
                        URI.create(
                                "https://scsanctions.un.org/resources/xml/en/consolidated.xml")));
    }

    /**
     * Runs the download benchmark.
     *
     * @return total entities fetched across all providers
     */
    public int run() {
        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("  Provider Download Benchmark");
        System.out.println("=".repeat(72));
        System.out.println();

        // --- Sequential downloads ---
        System.out.println("--- Sequential Downloads ---");
        System.out.printf(
                "%-22s %10s %10s %12s%n", "Provider", "Entities", "Time (ms)", "Rate (e/s)");
        System.out.println("-".repeat(58));

        Map<String, List<SanctionedEntity>> results = new LinkedHashMap<>();
        Instant seqStart = Instant.now();

        for (var entry : providers.entrySet()) {
            String name = entry.getKey();
            ListProvider provider = entry.getValue();
            try {
                Instant start = Instant.now();
                List<SanctionedEntity> entities = provider.fetch();
                long ms = Duration.between(start, Instant.now()).toMillis();
                long rate = ms > 0 ? (entities.size() * 1000L / ms) : entities.size();
                System.out.printf("%-22s %10d %10d %12d%n", name, entities.size(), ms, rate);
                results.put(name, entities);
            } catch (ListIngestionException e) {
                System.out.printf("%-22s %10s %10s %12s%n", name, "FAILED", "-", "-");
                log.error("Failed to fetch {}: {}", name, e.getMessage());
            }
        }

        long seqTotalMs = Duration.between(seqStart, Instant.now()).toMillis();
        int totalEntities = results.values().stream().mapToInt(List::size).sum();
        System.out.println("-".repeat(58));
        System.out.printf("%-22s %10d %10d%n", "TOTAL (sequential)", totalEntities, seqTotalMs);

        // --- Parallel downloads ---
        System.out.println();
        System.out.println("--- Parallel Downloads ---");

        Instant parStart = Instant.now();
        Map<String, ProviderResult> parallelResults = new ConcurrentHashMap<>();

        List<Thread> threads =
                providers.entrySet().stream()
                        .map(
                                entry ->
                                        Thread.ofPlatform()
                                                .start(
                                                        () -> {
                                                            String name = entry.getKey();
                                                            ListProvider provider =
                                                                    entry.getValue();
                                                            try {
                                                                Instant start = Instant.now();
                                                                List<SanctionedEntity> entities =
                                                                        provider.fetch();
                                                                long ms =
                                                                        Duration.between(
                                                                                        start,
                                                                                        Instant
                                                                                                .now())
                                                                                .toMillis();
                                                                parallelResults.put(
                                                                        name,
                                                                        new ProviderResult(
                                                                                entities.size(),
                                                                                ms,
                                                                                null));
                                                            } catch (ListIngestionException e) {
                                                                parallelResults.put(
                                                                        name,
                                                                        new ProviderResult(
                                                                                0,
                                                                                0,
                                                                                e.getMessage()));
                                                            }
                                                        }))
                        .toList();

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        long parTotalMs = Duration.between(parStart, Instant.now()).toMillis();

        System.out.printf(
                "%-22s %10s %10s %12s%n", "Provider", "Entities", "Time (ms)", "Rate (e/s)");
        System.out.println("-".repeat(58));
        int parTotalEntities = 0;
        for (var entry : parallelResults.entrySet()) {
            ProviderResult r = entry.getValue();
            if (r.error != null) {
                System.out.printf("%-22s %10s %10s %12s%n", entry.getKey(), "FAILED", "-", "-");
            } else {
                long rate = r.ms > 0 ? (r.entities * 1000L / r.ms) : r.entities;
                System.out.printf("%-22s %10d %10d %12d%n", entry.getKey(), r.entities, r.ms, rate);
                parTotalEntities += r.entities;
            }
        }
        System.out.println("-".repeat(58));
        System.out.printf("%-22s %10d %10d%n", "TOTAL (parallel)", parTotalEntities, parTotalMs);
        double speedup = seqTotalMs > 0 ? (double) seqTotalMs / parTotalMs : 1.0;
        System.out.printf("Parallel speedup: %.2fx%n", speedup);

        System.out.println();
        return totalEntities;
    }

    private record ProviderResult(int entities, long ms, String error) {}
}
