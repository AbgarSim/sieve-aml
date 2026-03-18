package dev.sieve.benchmark;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP-level stress test for the Sieve screening API.
 *
 * <p>Fires massive concurrent POST requests at {@code /api/v1/screen} using virtual threads
 * and multiple pooled {@link HttpClient} instances. Designed to push toward 200k queries/sec.
 *
 * <p>Usage:
 * <pre>
 *   java -jar sieve-benchmark.jar stress [baseUrl] [totalRequests] [concurrency]
 *
 *   # Defaults: http://localhost:8080, 200000 requests, 1000 virtual threads
 *   java -jar sieve-benchmark.jar stress
 *
 *   # Custom target
 *   java -jar sieve-benchmark.jar stress http://sieve:8080 500000 2000
 * </pre>
 */
public final class HttpStressTest {

    private static final Logger log = LoggerFactory.getLogger(HttpStressTest.class);

    private static final String[] SCREEN_NAMES = {
            "Saddam Hussein", "Osama Bin Laden", "Vladimir Putin", "Kim Jong Un",
            "Abu Ali", "Sergei Ivanov", "ACME Holdings", "Al-Qaeda",
            "Hezbollah", "Islamic State", "John Smith", "Jane Doe",
            "Microsoft Corporation", "Alice Johnson", "Bob Williams",
            "Tokyo Motors Ltd", "Sarah Connor", "David Brown",
            "Global Trading Co", "Emily Davis", "Ali Hassan",
            "Bank of Iran", "Gazprom", "Rosneft", "Sberbank",
            "Viktor Bout", "El Chapo", "Dawood Ibrahim",
            "National Iranian Oil", "Russian Direct Investment Fund",
    };

    private static final double[] THRESHOLDS = {0.70, 0.80, 0.85, 0.90};

    private final String baseUrl;
    private final int totalRequests;
    private final int concurrency;

    public HttpStressTest(String baseUrl, int totalRequests, int concurrency) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.totalRequests = totalRequests;
        this.concurrency = concurrency;
    }

    public void run() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║          Sieve AML — HTTP Stress Test                            ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  Target:       %s/api/v1/screen%n", baseUrl);
        System.out.printf("  Total reqs:   %,d%n", totalRequests);
        System.out.printf("  Concurrency:  %,d virtual threads%n", concurrency);
        System.out.println();

        // Warm up — verify endpoint is reachable
        if (!warmUp()) {
            System.out.println("  ERROR: Cannot reach the server. Is it running?");
            return;
        }

        // Phase 1: Ramp-up — increasing concurrency
        System.out.println("═══ Phase 1: Ramp-Up (increasing concurrency) ═══");
        System.out.println();
        System.out.printf("%-14s %12s %14s %12s %12s %12s %10s%n",
                "Concurrency", "Requests", "Throughput", "Avg (µs)", "P50 (µs)", "P99 (µs)", "Errors");
        System.out.println("─".repeat(94));

        int[] rampLevels = {1, 10, 50, 100, 500, concurrency};
        int rampRequests = Math.min(1000, totalRequests / 4);
        for (int level : rampLevels) {
            if (level > concurrency) break;
            runPhase(level, rampRequests);
        }

        // Phase 2: Sustained load — full concurrency, full request count
        System.out.println();
        System.out.println("═══ Phase 2: Sustained Load ═══");
        System.out.println();
        System.out.printf("%-14s %12s %14s %12s %12s %12s %10s%n",
                "Concurrency", "Requests", "Throughput", "Avg (µs)", "P50 (µs)", "P99 (µs)", "Errors");
        System.out.println("─".repeat(94));

        StressResult sustained = runPhase(concurrency, totalRequests);

        // Phase 3: Threshold sensitivity under load
        System.out.println();
        System.out.println("═══ Phase 3: Threshold Sensitivity Under Load ═══");
        System.out.println();
        System.out.printf("%-12s %14s %12s %12s%n",
                "Threshold", "Throughput", "Avg (µs)", "P99 (µs)");
        System.out.println("─".repeat(54));

        int thresholdRequests = Math.min(50_000, totalRequests / 2);
        for (double threshold : THRESHOLDS) {
            runThresholdPhase(concurrency / 2, thresholdRequests, threshold);
        }

        // Summary
        System.out.println();
        System.out.println("═══ Summary ═══");
        System.out.println();
        System.out.printf("  Peak throughput:  %,.0f req/sec%n", sustained.throughput);
        System.out.printf("  Total requests:   %,d%n", sustained.completed);
        System.out.printf("  Errors:           %,d (%.2f%%)%n",
                sustained.errors, sustained.errorRate() * 100);
        System.out.printf("  Avg latency:      %,d µs%n", sustained.avgLatencyUs);
        System.out.printf("  P50 latency:      %,d µs%n", sustained.p50Us);
        System.out.printf("  P99 latency:      %,d µs%n", sustained.p99Us);
        System.out.printf("  Max latency:      %,d µs%n", sustained.maxUs);
        System.out.printf("  Wall time:        %.2f s%n", sustained.wallTimeSec);
        System.out.println();
    }

    private boolean warmUp() {
        System.out.print("  Warming up (5 requests)... ");
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            String body = toJson("warmup test", 0.80);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/screen"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            for (int i = 0; i < 5; i++) {
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    System.out.println("FAILED (status=" + resp.statusCode() + ")");
                    return false;
                }
            }
            System.out.println("OK");
            return true;
        } catch (Exception e) {
            System.out.println("FAILED (" + e.getMessage() + ")");
            return false;
        }
    }

    private StressResult runPhase(int threads, int requests) {
        // Use multiple HttpClient instances to avoid connection pool bottleneck
        int clientCount = Math.max(1, Math.min(threads / 10, 50));
        HttpClient[] clients = new HttpClient[clientCount];
        for (int i = 0; i < clientCount; i++) {
            clients[i] = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        int requestsPerThread = requests / threads;
        int remainder = requests % threads;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                final int myRequests = requestsPerThread + (t < remainder ? 1 : 0);
                final HttpClient client = clients[t % clientCount];

                executor.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        done.countDown();
                        return;
                    }

                    for (int i = 0; i < myRequests; i++) {
                        String name = SCREEN_NAMES[(threadId + i) % SCREEN_NAMES.length];
                        double threshold = THRESHOLDS[(threadId + i) % THRESHOLDS.length];
                        String body = toJson(name, threshold);

                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/api/v1/screen"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();

                        long start = System.nanoTime();
                        try {
                            HttpResponse<String> resp = client.send(req,
                                    HttpResponse.BodyHandlers.ofString());
                            long elapsedUs = (System.nanoTime() - start) / 1_000;
                            latencies.add(elapsedUs);
                            completedCount.incrementAndGet();

                            if (resp.statusCode() != 200) {
                                errorCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            long elapsedUs = (System.nanoTime() - start) / 1_000;
                            latencies.add(elapsedUs);
                            errorCount.incrementAndGet();
                            completedCount.incrementAndGet();
                        }
                    }

                    done.countDown();
                });
            }

            try {
                ready.await();
                Instant wallStart = Instant.now();
                go.countDown();
                done.await();
                double wallSec = Duration.between(wallStart, Instant.now()).toMillis() / 1000.0;

                // Compute stats
                List<Long> sorted = new ArrayList<>(latencies);
                sorted.sort(null);
                int n = sorted.size();

                if (n == 0) {
                    StressResult result = new StressResult(0, 0, 0, 0, 0, 0, 0, wallSec);
                    System.out.printf("%-14d %12d %14s %12s %12s %12s %10d%n",
                            threads, 0, "N/A", "N/A", "N/A", "N/A", errorCount.get());
                    return result;
                }

                long sum = sorted.stream().mapToLong(Long::longValue).sum();
                long avg = sum / n;
                long p50 = sorted.get(n / 2);
                long p99 = sorted.get((int) (n * 0.99));
                long max = sorted.getLast();
                double throughput = n / wallSec;
                int errors = errorCount.get();

                StressResult result = new StressResult(
                        n, throughput, avg, p50, p99, max, errors, wallSec);

                System.out.printf("%-14d %12d %,14.0f %,12d %,12d %,12d %10d%n",
                        threads, n, throughput, avg, p50, p99, errors);

                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new StressResult(0, 0, 0, 0, 0, 0, 0, 0);
            }
        } finally {
            for (HttpClient c : clients) {
                c.close();
            }
        }
    }

    private void runThresholdPhase(int threads, int requests, double threshold) {
        int clientCount = Math.max(1, Math.min(threads / 10, 50));
        HttpClient[] clients = new HttpClient[clientCount];
        for (int i = 0; i < clientCount; i++) {
            clients[i] = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        AtomicInteger completedCount = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        int requestsPerThread = requests / threads;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                final HttpClient client = clients[t % clientCount];

                executor.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        done.countDown();
                        return;
                    }

                    for (int i = 0; i < requestsPerThread; i++) {
                        String name = SCREEN_NAMES[(threadId + i) % SCREEN_NAMES.length];
                        String body = toJson(name, threshold);

                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + "/api/v1/screen"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();

                        long start = System.nanoTime();
                        try {
                            client.send(req, HttpResponse.BodyHandlers.ofString());
                            latencies.add((System.nanoTime() - start) / 1_000);
                            completedCount.incrementAndGet();
                        } catch (Exception e) {
                            latencies.add((System.nanoTime() - start) / 1_000);
                            completedCount.incrementAndGet();
                        }
                    }
                    done.countDown();
                });
            }

            try {
                ready.await();
                Instant wallStart = Instant.now();
                go.countDown();
                done.await();
                double wallSec = Duration.between(wallStart, Instant.now()).toMillis() / 1000.0;

                List<Long> sorted = new ArrayList<>(latencies);
                sorted.sort(null);
                int n = sorted.size();
                if (n == 0) return;

                long avg = sorted.stream().mapToLong(Long::longValue).sum() / n;
                long p99 = sorted.get((int) (n * 0.99));
                double throughput = n / wallSec;

                System.out.printf("%-12.2f %,14.0f %,12d %,12d%n", threshold, throughput, avg, p99);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            for (HttpClient c : clients) {
                c.close();
            }
        }
    }

    private static String toJson(String name, double threshold) {
        // Manual JSON to avoid dependency on Jackson in the benchmark module
        return "{\"name\":\"" + name.replace("\"", "\\\"")
                + "\",\"threshold\":" + threshold + "}";
    }

    record StressResult(
            int completed,
            double throughput,
            long avgLatencyUs,
            long p50Us,
            long p99Us,
            long maxUs,
            int errors,
            double wallTimeSec) {

        double errorRate() {
            return completed == 0 ? 0 : (double) errors / completed;
        }
    }
}
