package dev.sieve.benchmark;

import dev.sieve.benchmark.eval.EvaluationSet;
import dev.sieve.benchmark.eval.EvaluationSet.Kind;
import dev.sieve.benchmark.eval.EvaluationSet.Query;
import dev.sieve.benchmark.eval.NameSamples;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.ProviderRegistry;
import dev.sieve.ingest.ProviderResult;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import dev.sieve.match.NgramIndex;
import dev.sieve.match.NormalizedNameCache;
import dev.sieve.match.PhoneticMatchEngine;
import dev.sieve.match.TokenMatchEngine;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Benchmark and matching evaluation on the real lists: fetches them, builds the index with the same
 * matching engines as the servers, measures memory, latency and throughput, and scores a labelled
 * query set (see {@link EvaluationSet}).
 *
 * <p>Writes {@code report.md}, {@code queries.tsv} (the evaluation set with each query's outcome)
 * and {@code names.txt} (query names for the HTTP load test) to the output directory.
 */
final class RealDataBenchmark {

    /** Thresholds the evaluation reports; the servers default to 0.80. */
    private static final double[] THRESHOLDS = {0.70, 0.75, 0.80, 0.85, 0.88, 0.90, 0.92, 0.95};

    private static final double SERVER_DEFAULT_THRESHOLD = 0.80;

    private static final int[] CONCURRENCY = {1, 2, 4, 8, 16, 64};

    private final Set<ListSource> sources;
    private final Path out;
    private final long seed;
    private final int perKind;
    private final Duration loadDuration;

    RealDataBenchmark(
            Set<ListSource> sources, Path out, long seed, int perKind, Duration loadDuration) {
        this.sources = sources;
        this.out = out;
        this.seed = seed;
        this.perKind = perKind;
        this.loadDuration = loadDuration;
    }

    /**
     * Parses {@code real} options: {@code --exclude A,B}, {@code --source A,B}, {@code --out dir},
     * {@code --seed n}, {@code --queries n} (per kind), {@code --load-seconds n} (per concurrency
     * level).
     */
    static RealDataBenchmark fromArgs(String[] args) {
        Set<ListSource> sources = EnumSet.allOf(ListSource.class);
        Path out = Path.of("benchmark-report");
        long seed = 20261005L;
        int perKind = 2000;
        int loadSeconds = 10;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--exclude" -> sources.removeAll(parseSources(args[++i]));
                case "--source" -> sources = EnumSet.copyOf(parseSources(args[++i]));
                case "--out" -> out = Path.of(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--queries" -> perKind = Integer.parseInt(args[++i]);
                case "--load-seconds" -> loadSeconds = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        return new RealDataBenchmark(sources, out, seed, perKind, Duration.ofSeconds(loadSeconds));
    }

    private static List<ListSource> parseSources(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(ListSource::valueOf)
                .toList();
    }

    /** Fetches the lists and runs everything. */
    void run() throws IOException, InterruptedException {
        List<ListProvider> providers =
                ProviderRegistry.defaults().stream()
                        .filter(p -> sources.contains(p.source()))
                        .toList();
        EntityIndex index = new InMemoryEntityIndex();
        long heapBefore = usedHeapAfterGc();
        IngestionReport ingest = new IngestionOrchestrator(providers).ingest(index);
        run(index, ingest, heapBefore);
    }

    /**
     * Runs the measurements on an index that is already loaded.
     *
     * @param index the loaded index
     * @param ingest the report of the fetch that loaded it, or {@code null} when not fetched
     * @param heapBefore heap in use before loading, in bytes
     */
    void run(EntityIndex index, IngestionReport ingest, long heapBefore)
            throws IOException, InterruptedException {
        Files.createDirectories(out);
        Report report = new Report();
        report.line("# Sieve benchmark and matching evaluation");
        report.line("");
        report.line("- Run at: " + Instant.now());
        report.line(
                "- Machine: "
                        + Runtime.getRuntime().availableProcessors()
                        + " CPUs, max heap "
                        + mb(Runtime.getRuntime().maxMemory())
                        + " MB, "
                        + System.getProperty("os.name")
                        + " "
                        + System.getProperty("os.arch")
                        + ", Java "
                        + System.getProperty("java.version")
                        + " ("
                        + ManagementFactory.getRuntimeMXBean().getVmName()
                        + ")");
        report.line("- Seed: " + seed + ", up to " + perKind + " queries per kind");
        report.line("");

        if (ingest != null) {
            reportIngest(report, ingest);
        }

        // Index and matching structures: the same engines and shared caches as the servers
        NormalizedNameCache nameCache = new NormalizedNameCache();
        NgramIndex ngramIndex = new NgramIndex();
        MatchEngine engine =
                new CompositeMatchEngine(
                        List.of(
                                new ExactMatchEngine(nameCache, ngramIndex),
                                new FuzzyMatchEngine(nameCache, ngramIndex),
                                new PhoneticMatchEngine(nameCache, ngramIndex),
                                new TokenMatchEngine(nameCache, ngramIndex)));
        long buildStart = System.nanoTime();
        engine.screen(ScreeningRequest.of("warm up", SERVER_DEFAULT_THRESHOLD), index);
        long buildMs = (System.nanoTime() - buildStart) / 1_000_000;
        long heapAfter = usedHeapAfterGc();
        Collection<SanctionedEntity> entities = index.all();
        long names = entities.stream().mapToLong(e -> 1 + e.aliases().size()).sum();

        report.line("## Index");
        report.line("");
        report.line("| Measure | Value |");
        report.line("| --- | ---: |");
        report.line("| Records | " + fmt(entities.size()) + " |");
        report.line("| Names including aliases | " + fmt(names) + " |");
        report.line(
                "| Normalised name cache and trigram index built in | " + fmt(buildMs) + " ms |");
        report.line(
                "| Heap in use for records, name cache and trigram index (after GC) | "
                        + mb(heapAfter - heapBefore)
                        + " MB |");
        report.line("");

        // Evaluation set
        Random random = new Random(seed);
        List<Query> queries = new ArrayList<>();
        queries.addAll(EvaluationSet.sameEntityOtherList(entities, perKind, random));
        queries.addAll(EvaluationSet.spellingVariants(entities, perKind, random));
        queries.addAll(EvaluationSet.unlistedCustomers(NameSamples.bundled(), perKind, random));
        Map<String, SanctionedEntity> byId =
                entities.stream()
                        .collect(Collectors.toMap(SanctionedEntity::id, e -> e, (a, b) -> a));

        // Warm up the JIT on the query set, then time each query once at the server default
        for (int i = 0; i < Math.min(queries.size(), 2000); i++) {
            engine.screen(ScreeningRequest.of(queries.get(i).name(), 0.70), index);
        }
        double minThreshold = THRESHOLDS[0];
        List<Outcome> outcomes = new ArrayList<>(queries.size());
        long[] latencyNanos = new long[queries.size()];
        for (int i = 0; i < queries.size(); i++) {
            Query q = queries.get(i);
            long t0 = System.nanoTime();
            List<MatchResult> results =
                    engine.screen(ScreeningRequest.of(q.name(), minThreshold), index);
            latencyNanos[i] = System.nanoTime() - t0;
            outcomes.add(Outcome.of(q, results));
        }
        reportEvaluation(report, outcomes);
        reportLatency(report, latencyNanos);
        reportThroughput(report, engine, index, queries);
        writeQueries(outcomes, byId);
        Files.write(
                out.resolve("names.txt"),
                queries.stream().map(Query::name).toList(),
                StandardCharsets.UTF_8);

        Files.writeString(out.resolve("report.md"), report.text(), StandardCharsets.UTF_8);
        System.out.println(report.text());
    }

    private void reportIngest(Report report, IngestionReport ingest) {
        report.line("## Fetch and parse");
        report.line("");
        report.line(
                "All lists fetched in parallel from their publishers; total "
                        + fmt(ingest.totalDuration().toMillis())
                        + " ms, "
                        + fmt(ingest.totalEntitiesLoaded())
                        + " records.");
        report.line("");
        report.line("| List | Status | Records | Fetch and parse ms |");
        report.line("| --- | --- | ---: | ---: |");
        ingest.results().values().stream()
                .sorted((a, b) -> a.source().compareTo(b.source()))
                .forEach(
                        (ProviderResult r) ->
                                report.line(
                                        "| "
                                                + r.source()
                                                + " | "
                                                + r.status()
                                                + " | "
                                                + fmt(r.entityCount())
                                                + " | "
                                                + fmt(r.duration().toMillis())
                                                + " |"));
        report.line("");
    }

    private void reportEvaluation(Report report, List<Outcome> outcomes) {
        report.line("## Matching evaluation");
        report.line("");
        report.line(
                "Name-only screening with no date of birth, country or type, as the API's"
                        + " default request. A same-entity or spelling query counts as found when"
                        + " the expected record is among the results at or above the threshold.");
        report.line("");
        Map<String, List<Outcome>> groups = new java.util.LinkedHashMap<>();
        groups.put(
                "Same entity on another list, all",
                outcomes.stream()
                        .filter(o -> o.query().kind() == Kind.SAME_ENTITY_OTHER_LIST)
                        .toList());
        groups.put(
                "Same entity on another list, spelled differently",
                outcomes.stream()
                        .filter(o -> o.query().kind() == Kind.SAME_ENTITY_OTHER_LIST)
                        .filter(o -> o.query().differentSpelling())
                        .toList());
        groups.put(
                "Spelling variants",
                outcomes.stream().filter(o -> o.query().kind() == Kind.SPELLING_VARIANT).toList());

        StringBuilder header = new StringBuilder("| Recall (found) | Queries |");
        StringBuilder rule = new StringBuilder("| --- | ---: |");
        for (double t : THRESHOLDS) {
            header.append(" ").append(String.format(Locale.ROOT, "%.2f", t)).append(" |");
            rule.append(" ---: |");
        }
        report.line(header.toString());
        report.line(rule.toString());
        groups.forEach(
                (label, list) -> {
                    StringBuilder row =
                            new StringBuilder("| " + label + " | " + list.size() + " |");
                    for (double t : THRESHOLDS) {
                        long found = list.stream().filter(o -> o.expectedScore() >= t).count();
                        row.append(" ").append(pct(found, list.size())).append(" |");
                    }
                    report.line(row.toString());
                });
        report.line("");

        // Recall per change kind for spelling variants
        report.line("Spelling variants by change, found at 0.80 and 0.90:");
        report.line("");
        report.line("| Change | Queries | 0.80 | 0.90 |");
        report.line("| --- | ---: | ---: | ---: |");
        Map<String, List<Outcome>> byChange =
                outcomes.stream()
                        .filter(o -> o.query().kind() == Kind.SPELLING_VARIANT)
                        .collect(
                                Collectors.groupingBy(
                                        o ->
                                                o.query()
                                                        .note()
                                                        .substring(
                                                                0, o.query().note().indexOf(':')),
                                        java.util.TreeMap::new,
                                        Collectors.toList()));
        byChange.forEach(
                (change, list) ->
                        report.line(
                                "| "
                                        + change
                                        + " | "
                                        + list.size()
                                        + " | "
                                        + pct(
                                                list.stream()
                                                        .filter(o -> o.expectedScore() >= 0.80)
                                                        .count(),
                                                list.size())
                                        + " | "
                                        + pct(
                                                list.stream()
                                                        .filter(o -> o.expectedScore() >= 0.90)
                                                        .count(),
                                                list.size())
                                        + " |"));
        report.line("");

        List<Outcome> negatives =
                outcomes.stream().filter(o -> o.query().kind() == Kind.UNLISTED_CUSTOMER).toList();
        report.line(
                "Unlisted customer names: the share that raise at least one alert (the"
                        + " false-positive rate of name-only screening) and the mean number of hits"
                        + " per alerted name.");
        report.line("");
        StringBuilder nh = new StringBuilder("| Unlisted customers (" + negatives.size() + ") |");
        StringBuilder nr = new StringBuilder("| --- |");
        for (double t : THRESHOLDS) {
            nh.append(" ").append(String.format(Locale.ROOT, "%.2f", t)).append(" |");
            nr.append(" ---: |");
        }
        report.line(nh.toString());
        report.line(nr.toString());
        StringBuilder alert = new StringBuilder("| Alert rate |");
        StringBuilder hits = new StringBuilder("| Hits per alerted name |");
        for (double t : THRESHOLDS) {
            long alerted = negatives.stream().filter(o -> o.topScore() >= t).count();
            double meanHits =
                    negatives.stream()
                            .filter(o -> o.topScore() >= t)
                            .mapToLong(o -> o.hitsAtOrAbove(t))
                            .average()
                            .orElse(0);
            alert.append(" ").append(pct(alerted, negatives.size())).append(" |");
            hits.append(" ").append(String.format(Locale.ROOT, "%.1f", meanHits)).append(" |");
        }
        report.line(alert.toString());
        report.line(hits.toString());
        report.line("");
        long namesakes = negatives.stream().filter(o -> o.topScore() >= 0.9999).count();
        report.line(
                "Of these names, "
                        + pct(namesakes, negatives.size())
                        + " score 1.00 against a listed name: a namesake that is identical after"
                        + " normalisation, which only a date of birth, country or identifier can"
                        + " clear.");
        report.line("");
    }

    private void reportLatency(Report report, long[] latencyNanos) {
        long[] sorted = latencyNanos.clone();
        Arrays.sort(sorted);
        report.line("## Latency, one thread");
        report.line("");
        report.line(
                "Each evaluation query screened once against the whole index (all four engines),"
                        + " in process, no HTTP.");
        report.line("");
        report.line("| Queries | Mean | p50 | p95 | p99 | Max |");
        report.line("| ---: | ---: | ---: | ---: | ---: | ---: |");
        double mean = Arrays.stream(sorted).average().orElse(0);
        report.line(
                "| "
                        + sorted.length
                        + " | "
                        + ms(mean)
                        + " | "
                        + ms(pctl(sorted, 0.50))
                        + " | "
                        + ms(pctl(sorted, 0.95))
                        + " | "
                        + ms(pctl(sorted, 0.99))
                        + " | "
                        + ms(sorted.length == 0 ? 0 : sorted[sorted.length - 1])
                        + " |");
        report.line("");
    }

    private void reportThroughput(
            Report report, MatchEngine engine, EntityIndex index, List<Query> queries)
            throws InterruptedException {
        report.line("## Throughput");
        report.line("");
        report.line(
                "Queries from the evaluation set screened at threshold 0.80 by N threads for "
                        + loadDuration.toSeconds()
                        + " s each, in process.");
        report.line("");
        report.line("| Threads | Queries/s | p50 | p99 |");
        report.line("| ---: | ---: | ---: | ---: |");
        List<String> names = queries.stream().map(Query::name).toList();
        for (int threads : CONCURRENCY) {
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicInteger next = new AtomicInteger();
            List<long[]> perThread = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long start = System.nanoTime();
            List<java.util.concurrent.Future<long[]>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    long[] buf = new long[1 << 16];
                                    int n = 0;
                                    while (!stop.get()) {
                                        String name =
                                                names.get(
                                                        Math.floorMod(
                                                                next.getAndIncrement(),
                                                                names.size()));
                                        long t0 = System.nanoTime();
                                        engine.screen(
                                                ScreeningRequest.of(name, SERVER_DEFAULT_THRESHOLD),
                                                index);
                                        if (n == buf.length) {
                                            buf = Arrays.copyOf(buf, n * 2);
                                        }
                                        buf[n++] = System.nanoTime() - t0;
                                    }
                                    return Arrays.copyOf(buf, n);
                                }));
            }
            Thread.sleep(loadDuration.toMillis());
            stop.set(true);
            for (var f : futures) {
                try {
                    perThread.add(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            pool.shutdown();
            long[] all = perThread.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            report.line(
                    "| "
                            + threads
                            + " | "
                            + fmt(Math.round(all.length / seconds))
                            + " | "
                            + ms(pctl(all, 0.50))
                            + " | "
                            + ms(pctl(all, 0.99))
                            + " |");
        }
        report.line("");
    }

    private void writeQueries(List<Outcome> outcomes, Map<String, SanctionedEntity> byId)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(
                "kind\tquery\texpected_id\texpected_name\tsource_id\tdifferent_spelling\texpected_score\ttop_score\ttop_id\tnote");
        for (Outcome o : outcomes) {
            Query q = o.query();
            SanctionedEntity expected = byId.get(q.expectedId());
            lines.add(
                    String.join(
                            "\t",
                            q.kind().name(),
                            clean(q.name()),
                            q.expectedId(),
                            expected == null ? "" : clean(expected.primaryName().fullName()),
                            q.sourceId(),
                            String.valueOf(q.differentSpelling()),
                            String.format(Locale.ROOT, "%.4f", o.expectedScore()),
                            String.format(Locale.ROOT, "%.4f", o.topScore()),
                            o.topId(),
                            clean(q.note())));
        }
        Files.write(out.resolve("queries.tsv"), lines, StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    static long pctl(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int i = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    static String ms(double nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
    }

    private static String mb(long bytes) {
        return fmt(bytes / (1024 * 1024));
    }

    static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
    }

    /** Result of one evaluation query. */
    private record Outcome(
            Query query, double expectedScore, double topScore, String topId, double[] scores) {

        static Outcome of(Query q, List<MatchResult> results) {
            double expected = 0;
            double top = 0;
            String topId = "";
            Map<String, Double> best = new HashMap<>();
            for (MatchResult r : results) {
                best.merge(r.entity().id(), r.score(), Math::max);
                if (r.score() > top) {
                    top = r.score();
                    topId = r.entity().id();
                }
                if (r.entity().id().equals(q.expectedId())) {
                    expected = Math.max(expected, r.score());
                }
            }
            double[] scores = best.values().stream().mapToDouble(Double::doubleValue).toArray();
            return new Outcome(q, expected, top, topId, scores);
        }

        long hitsAtOrAbove(double t) {
            return Arrays.stream(scores).filter(s -> s >= t).count();
        }
    }

    /** Collects the markdown report. */
    private static final class Report {
        private final StringBuilder sb = new StringBuilder();

        void line(String s) {
            sb.append(s).append('\n');
        }

        String text() {
            return sb.toString();
        }
    }
}
