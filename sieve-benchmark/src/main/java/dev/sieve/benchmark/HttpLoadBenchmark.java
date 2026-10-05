package dev.sieve.benchmark;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP load test against a running Sieve server: N clients post {@code /api/v1/screen} requests
 * back to back for a fixed time per concurrency level, cycling through a file of names (one per
 * line, as {@code real} writes to {@code names.txt}).
 */
final class HttpLoadBenchmark {

    private final URI url;
    private final List<String> names;
    private final int[] concurrency;
    private final Duration duration;
    private final double threshold;

    private HttpLoadBenchmark(
            URI url, List<String> names, int[] concurrency, Duration duration, double threshold) {
        this.url = url;
        this.names = names;
        this.concurrency = concurrency;
        this.duration = duration;
        this.threshold = threshold;
    }

    /**
     * Parses {@code http} options: {@code --url}, {@code --names file}, {@code --concurrency
     * 1,8,32}, {@code --seconds n}, {@code --threshold t}.
     */
    static HttpLoadBenchmark fromArgs(String[] args) throws IOException {
        URI url = URI.create("http://localhost:8080/api/v1/screen");
        Path namesFile = null;
        int[] concurrency = {1, 4, 16, 64, 256};
        int seconds = 20;
        double threshold = 0.80;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--url" -> url = URI.create(args[++i]);
                case "--names" -> namesFile = Path.of(args[++i]);
                case "--concurrency" ->
                        concurrency =
                                Arrays.stream(args[++i].split(","))
                                        .mapToInt(s -> Integer.parseInt(s.trim()))
                                        .toArray();
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--threshold" -> threshold = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (namesFile == null) {
            throw new IllegalArgumentException("--names is required");
        }
        List<String> names =
                Files.readAllLines(namesFile, StandardCharsets.UTF_8).stream()
                        .filter(s -> !s.isBlank())
                        .toList();
        return new HttpLoadBenchmark(
                url, names, concurrency, Duration.ofSeconds(seconds), threshold);
    }

    void run() throws InterruptedException {
        HttpClient client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .executor(Executors.newVirtualThreadPerTaskExecutor())
                        .build();
        System.out.println("## HTTP load test");
        System.out.println();
        System.out.println(
                "POST "
                        + url
                        + ", threshold "
                        + threshold
                        + ", "
                        + names.size()
                        + " distinct names, "
                        + duration.toSeconds()
                        + " s per level after a 5 s warm-up at the same level. Client and server"
                        + " share the machine.");
        System.out.println();
        System.out.println("| Clients | Requests/s | p50 | p95 | p99 | Errors |");
        System.out.println("| ---: | ---: | ---: | ---: | ---: | ---: |");
        for (int clients : concurrency) {
            level(client, clients, Duration.ofSeconds(5));
            Result r = level(client, clients, duration);
            System.out.println(
                    "| "
                            + clients
                            + " | "
                            + RealDataBenchmark.fmt(Math.round(r.requests() / r.seconds()))
                            + " | "
                            + RealDataBenchmark.ms(RealDataBenchmark.pctl(r.latencies(), 0.50))
                            + " | "
                            + RealDataBenchmark.ms(RealDataBenchmark.pctl(r.latencies(), 0.95))
                            + " | "
                            + RealDataBenchmark.ms(RealDataBenchmark.pctl(r.latencies(), 0.99))
                            + " | "
                            + r.errors()
                            + " |");
        }
        System.out.println();
    }

    private Result level(HttpClient client, int clients, Duration length)
            throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicInteger next = new AtomicInteger();
        AtomicLong errors = new AtomicLong();
        List<Future<long[]>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            long start = System.nanoTime();
            for (int c = 0; c < clients; c++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    long[] buf = new long[1 << 12];
                                    int n = 0;
                                    while (!stop.get()) {
                                        String name =
                                                names.get(
                                                        Math.floorMod(
                                                                next.getAndIncrement(),
                                                                names.size()));
                                        HttpRequest request =
                                                HttpRequest.newBuilder(url)
                                                        .timeout(Duration.ofSeconds(30))
                                                        .header("Content-Type", "application/json")
                                                        .POST(
                                                                HttpRequest.BodyPublishers.ofString(
                                                                        body(name)))
                                                        .build();
                                        long t0 = System.nanoTime();
                                        try {
                                            HttpResponse<Void> response =
                                                    client.send(
                                                            request,
                                                            HttpResponse.BodyHandlers.discarding());
                                            if (response.statusCode() != 200) {
                                                errors.incrementAndGet();
                                                continue;
                                            }
                                        } catch (IOException e) {
                                            errors.incrementAndGet();
                                            continue;
                                        }
                                        if (n == buf.length) {
                                            buf = Arrays.copyOf(buf, n * 2);
                                        }
                                        buf[n++] = System.nanoTime() - t0;
                                    }
                                    return Arrays.copyOf(buf, n);
                                }));
            }
            Thread.sleep(length.toMillis());
            stop.set(true);
            List<long[]> parts = new ArrayList<>();
            for (Future<long[]> f : futures) {
                try {
                    parts.add(f.get());
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            long[] all = parts.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            return new Result(all.length, seconds, all, errors.get());
        }
    }

    private String body(String name) {
        String escaped = name.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"name\":\"" + escaped + "\",\"threshold\":" + threshold + "}";
    }

    private record Result(long requests, double seconds, long[] latencies, long errors) {}
}
