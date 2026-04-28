package dev.sieve.benchmark;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stress-tests the matching engine under concurrent load.
 *
 * <p>Loads a real sanctions index from all providers, then fires concurrent screening requests
 * using virtual threads. Measures throughput, latency percentiles, and correctness.
 */
public final class MatchingBenchmark {

    /** Sample names to screen — a mix of real-ish sanctions names and innocent names. */
    private static final String[] SCREEN_NAMES = {
        // Likely matches (variants of common sanctions list names)
        "Saddam Hussein",
        "Osama Bin Laden",
        "Abu Ali",
        "Vladimir Putin",
        "Sergei Ivanov",
        "Kim Jong Un",
        "ACME Holdings",
        "Al-Qaeda",
        "Hezbollah",
        "Islamic State",
        // Unlikely matches (innocent names)
        "John Smith",
        "Jane Doe",
        "Microsoft Corporation",
        "Alice Johnson",
        "Bob Williams",
        "Tokyo Motors Ltd",
        "Sarah Connor",
        "David Brown",
        "Emily Davis",
        "Global Trading Co",
    };

    private static final double[] THRESHOLDS = {0.70, 0.80, 0.85, 0.90, 0.95};

    /**
     * Runs the matching benchmark against the provided index.
     *
     * @param entities the entities to load into the index
     */
    public void run(List<SanctionedEntity> entities) {
        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("  Matching Engine Benchmark");
        System.out.println("=".repeat(72));
        System.out.println();

        if (entities.isEmpty()) {
            System.out.println("  No entities available — skipping matching benchmark.");
            System.out.println("  Run with --download first to fetch sanctions lists.");
            return;
        }

        EntityIndex index = new InMemoryEntityIndex();
        Instant loadStart = Instant.now();
        index.addAll(entities);
        long loadMs = Duration.between(loadStart, Instant.now()).toMillis();
        System.out.printf("Index loaded: %,d entities in %,d ms%n%n", index.size(), loadMs);

        MatchEngine exactEngine = new ExactMatchEngine();
        MatchEngine fuzzyEngine = new FuzzyMatchEngine();
        MatchEngine compositeEngine =
                new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));

        // --- Single-threaded latency by engine type ---
        System.out.println("--- Single-Threaded Latency (per screening call) ---");
        System.out.printf(
                "%-20s %10s %10s %10s %10s %10s%n",
                "Engine", "Queries", "Mean (µs)", "P50 (µs)", "P99 (µs)", "Max (µs)");
        System.out.println("-".repeat(74));

        benchmarkEngine("Exact", exactEngine, index, 0.95);
        benchmarkEngine("Fuzzy (t=0.70)", fuzzyEngine, index, 0.70);
        benchmarkEngine("Fuzzy (t=0.85)", fuzzyEngine, index, 0.85);
        benchmarkEngine("Composite (t=0.80)", compositeEngine, index, 0.80);

        // --- Throughput at varying thresholds ---
        System.out.println();
        System.out.println("--- Fuzzy Match Throughput vs Threshold ---");
        System.out.printf(
                "%-12s %12s %12s %12s%n", "Threshold", "Queries/sec", "Avg matches", "Avg (µs)");
        System.out.println("-".repeat(52));

        for (double threshold : THRESHOLDS) {
            throughputAtThreshold(fuzzyEngine, index, threshold);
        }

        // --- Concurrent stress test ---
        System.out.println();
        System.out.println("--- Concurrent Stress Test ---");

        int[] concurrencyLevels = {1, 4, 16, 64, 256};
        int requestsPerLevel = 1000;

        System.out.printf(
                "%-12s %12s %12s %12s %12s%n",
                "Threads", "Total reqs", "Throughput", "Avg (µs)", "P99 (µs)");
        System.out.println("-".repeat(64));

        for (int concurrency : concurrencyLevels) {
            concurrentStressTest(compositeEngine, index, concurrency, requestsPerLevel);
        }

        System.out.println();
    }

    // ---- Single-threaded latency measurement ----

    private void benchmarkEngine(
            String label, MatchEngine engine, EntityIndex index, double threshold) {
        // Warm up
        for (int i = 0; i < 5; i++) {
            for (String name : SCREEN_NAMES) {
                engine.screen(ScreeningRequest.of(name, threshold), index);
            }
        }

        // Measure
        List<Long> latencies = new ArrayList<>();
        for (int round = 0; round < 10; round++) {
            for (String name : SCREEN_NAMES) {
                long start = System.nanoTime();
                engine.screen(ScreeningRequest.of(name, threshold), index);
                long elapsed = System.nanoTime() - start;
                latencies.add(elapsed / 1_000); // convert to µs
            }
        }

        latencies.sort(null);
        int n = latencies.size();
        long mean = latencies.stream().mapToLong(Long::longValue).sum() / n;
        long p50 = latencies.get(n / 2);
        long p99 = latencies.get((int) (n * 0.99));
        long max = latencies.getLast();

        System.out.printf("%-20s %10d %10d %10d %10d %10d%n", label, n, mean, p50, p99, max);
    }

    // ---- Throughput vs threshold ----

    private void throughputAtThreshold(MatchEngine engine, EntityIndex index, double threshold) {
        int iterations = 100;
        int totalMatches = 0;

        long start = System.nanoTime();
        for (int round = 0; round < iterations; round++) {
            for (String name : SCREEN_NAMES) {
                List<MatchResult> results =
                        engine.screen(ScreeningRequest.of(name, threshold), index);
                totalMatches += results.size();
            }
        }
        long elapsedNs = System.nanoTime() - start;

        int totalQueries = iterations * SCREEN_NAMES.length;
        double elapsedSec = elapsedNs / 1_000_000_000.0;
        double qps = totalQueries / elapsedSec;
        double avgMatches = (double) totalMatches / totalQueries;
        long avgUs = (elapsedNs / totalQueries) / 1_000;

        System.out.printf("%-12.2f %12.0f %12.1f %12d%n", threshold, qps, avgMatches, avgUs);
    }

    // ---- Concurrent stress test ----

    private void concurrentStressTest(
            MatchEngine engine, EntityIndex index, int concurrency, int totalRequests) {
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicLong startTime = new AtomicLong();

        int requestsPerThread = totalRequests / concurrency;

        for (int t = 0; t < concurrency; t++) {
            final int threadId = t;
            Thread.ofVirtual()
                    .start(
                            () -> {
                                ready.countDown();
                                try {
                                    go.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }

                                for (int i = 0; i < requestsPerThread; i++) {
                                    String name =
                                            SCREEN_NAMES[(threadId + i) % SCREEN_NAMES.length];
                                    long s = System.nanoTime();
                                    engine.screen(ScreeningRequest.of(name, 0.80), index);
                                    long elapsed = (System.nanoTime() - s) / 1_000;
                                    latencies.add(elapsed);
                                    completed.incrementAndGet();
                                }

                                done.countDown();
                            });
        }

        try {
            ready.await();
            startTime.set(System.nanoTime());
            go.countDown();
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        long wallNs = System.nanoTime() - startTime.get();
        double wallSec = wallNs / 1_000_000_000.0;
        int total = completed.get();
        double throughput = total / wallSec;

        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(null);
        long avg = sorted.stream().mapToLong(Long::longValue).sum() / sorted.size();
        long p99 = sorted.get((int) (sorted.size() * 0.99));

        System.out.printf(
                "%-12d %12d %12.0f %12d %12d%n", concurrency, total, throughput, avg, p99);
    }
}
