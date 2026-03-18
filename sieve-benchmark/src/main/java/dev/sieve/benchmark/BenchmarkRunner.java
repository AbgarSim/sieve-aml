package dev.sieve.benchmark;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.ingest.uk.UkHmtProvider;
import dev.sieve.ingest.un.UnConsolidatedProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for running Sieve benchmarks.
 *
 * <p>Usage:
 * <pre>
 *   # Run all benchmarks (download + matching stress test)
 *   java -jar sieve-benchmark.jar
 *
 *   # Download benchmark only
 *   java -jar sieve-benchmark.jar --download
 *
 *   # Matching stress test only (fetches lists first)
 *   java -jar sieve-benchmark.jar --match
 *
 *   # JMH microbenchmarks (synthetic data, reproducible)
 *   java -jar sieve-benchmark.jar jmh
 * </pre>
 */
public final class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    public static void main(String[] args) throws Exception {
        // Check for subcommands first
        if (args.length > 0 && "jmh".equals(args[0])) {
            runJmh(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "stress".equals(args[0])) {
            runStress(Arrays.copyOfRange(args, 1, args.length));
            return;
        }

        boolean runDownload = false;
        boolean runMatch = false;

        for (String arg : args) {
            switch (arg) {
                case "--download" -> runDownload = true;
                case "--match" -> runMatch = true;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
            }
        }

        // Default: run all if nothing specified
        if (!runDownload && !runMatch) {
            runDownload = true;
            runMatch = true;
        }

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║               Sieve AML — Benchmark Suite                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        List<SanctionedEntity> allEntities = new ArrayList<>();

        if (runDownload) {
            ProviderDownloadBenchmark downloadBench = new ProviderDownloadBenchmark();
            downloadBench.run();

            // Collect entities for matching benchmark
            allEntities.addAll(fetchAllProviders());
        } else if (runMatch) {
            // Need entities for matching — fetch them without benchmarking
            System.out.println("\nFetching sanctions lists for matching benchmark...");
            allEntities.addAll(fetchAllProviders());
        }

        if (runMatch) {
            MatchingBenchmark matchBench = new MatchingBenchmark();
            matchBench.run(allEntities);
        }

        System.out.println("Benchmark suite complete.");
    }

    private static void runJmh(String[] jmhArgs) throws RunnerException {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║           Sieve AML — JMH Microbenchmarks                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        Options opts = new OptionsBuilder()
                .include(MatchingJmhBenchmark.class.getSimpleName())
                .build();
        new Runner(opts).run();
    }

    private static List<SanctionedEntity> fetchAllProviders() {
        HttpClient httpClient = TrustAllHttpClient.create();
        List<SanctionedEntity> all = new ArrayList<>();
        List<ListProvider> providers = List.of(
                new OfacSdnProvider(URI.create(
                        "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML"),
                        httpClient),
                new UkHmtProvider(URI.create(
                        "https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml"),
                        httpClient),
                new EuConsolidatedProvider(URI.create(
                        "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw"),
                        httpClient),
                new UnConsolidatedProvider(URI.create(
                        "https://scsanctions.un.org/resources/xml/en/consolidated.xml"),
                        httpClient));

        for (ListProvider provider : providers) {
            try {
                List<SanctionedEntity> entities = provider.fetch();
                all.addAll(entities);
                log.info("Fetched {} entities from {}", entities.size(), provider.source());
            } catch (ListIngestionException e) {
                log.error("Failed to fetch from {}: {}", provider.source(), e.getMessage());
            }
        }
        return all;
    }

    private static void runStress(String[] args) {
        String baseUrl = "http://localhost:8080";
        int totalRequests = 200_000;
        int concurrency = 1_000;

        if (args.length >= 1) baseUrl = args[0];
        if (args.length >= 2) totalRequests = Integer.parseInt(args[1]);
        if (args.length >= 3) concurrency = Integer.parseInt(args[2]);

        HttpStressTest stressTest = new HttpStressTest(baseUrl, totalRequests, concurrency);
        stressTest.run();
    }

    private static void printUsage() {
        System.out.println("Sieve AML Benchmark Suite");
        System.out.println();
        System.out.println("Usage: java -jar sieve-benchmark.jar [COMMAND] [OPTIONS]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  jmh           Run JMH microbenchmarks (synthetic data, reproducible)");
        System.out.println("  stress        HTTP stress test against a running Sieve server");
        System.out.println("                Usage: stress [baseUrl] [totalRequests] [concurrency]");
        System.out.println("                Defaults: http://localhost:8080  200000  1000");
        System.out.println();
        System.out.println("Options (offline benchmark mode):");
        System.out.println("  --download    Run provider download speed benchmark");
        System.out.println("  --match       Run matching engine stress test under load");
        System.out.println("  --help, -h    Show this help");
        System.out.println();
        System.out.println("If no options are given, both --download and --match are run.");
    }
}
