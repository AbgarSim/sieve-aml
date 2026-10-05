package dev.sieve.benchmark;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.eu.EuConsolidatedProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.ingest.uk.UkHmtProvider;
import dev.sieve.ingest.un.UnConsolidatedProvider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.CommandLineOptionException;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for running Sieve benchmarks.
 *
 * <p>Usage:
 *
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
 *
 *   # Every list fetched live: memory, latency, throughput and a labelled matching evaluation
 *   java -jar sieve-benchmark.jar real --exclude UA_NSDC --out report
 *
 *   # HTTP load test against a running server, cycling through the names real wrote
 *   java -jar sieve-benchmark.jar http --url http://localhost:8080/api/v1/screen --names report/names.txt
 *
 *   # HTTP load testing — use JMeter (see README)
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
        if (args.length > 0 && "real".equals(args[0])) {
            RealDataBenchmark.fromArgs(Arrays.copyOfRange(args, 1, args.length)).run();
            return;
        }
        if (args.length > 0 && "http".equals(args[0])) {
            HttpLoadBenchmark.fromArgs(Arrays.copyOfRange(args, 1, args.length)).run();
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
                default -> log.warn("Unknown argument: {}", arg);
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

    /**
     * Runs the JMH suite. Extra arguments are standard JMH options, for example {@code -f 1 -wi 2
     * -i 3 -rf json -rff results.json} for a short run that writes machine-readable results.
     */
    private static void runJmh(String[] jmhArgs)
            throws RunnerException, CommandLineOptionException {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║           Sieve AML — JMH Microbenchmarks                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();

        Options opts =
                new OptionsBuilder()
                        .parent(new CommandLineOptions(jmhArgs))
                        .include(MatchingJmhBenchmark.class.getSimpleName())
                        .build();
        new Runner(opts).run();
    }

    private static List<SanctionedEntity> fetchAllProviders() {
        List<SanctionedEntity> all = new ArrayList<>();
        List<ListProvider> providers =
                List.of(
                        new OfacSdnProvider(
                                URI.create(
                                        "https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN.XML")),
                        new UkHmtProvider(
                                URI.create(
                                        "https://ofsistorage.blob.core.windows.net/publishlive/2022format/ConList.xml")),
                        new EuConsolidatedProvider(
                                URI.create(
                                        "https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw")),
                        new UnConsolidatedProvider(
                                URI.create(
                                        "https://scsanctions.un.org/resources/xml/en/consolidated.xml")));

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

    private static void printUsage() {
        System.out.println("Sieve AML Benchmark Suite");
        System.out.println();
        System.out.println("Usage: java -jar sieve-benchmark.jar [COMMAND] [OPTIONS]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println(
                "  jmh [JMH options]  Run JMH microbenchmarks (synthetic data, reproducible)");
        System.out.println(
                "                     e.g. jmh -f 1 -wi 2 -i 3 -rf json -rff jmh-results.json");
        System.out.println(
                "  real [options]     Fetch every list, then measure memory, latency, throughput"
                        + " and matching accuracy");
        System.out.println(
                "                     --exclude A,B | --source A,B, --out dir, --seed n,"
                        + " --queries n, --load-seconds n");
        System.out.println(
                "  http [options]     HTTP load test against a running server: --url, --names"
                        + " file, --concurrency 1,4,16, --seconds n, --threshold t");
        System.out.println();
        System.out.println("HTTP load testing with JMeter:");
        System.out.println("  Use JMeter test plans in src/test/jmeter/ (see README.md)");
        System.out.println();
        System.out.println("Options (offline benchmark mode):");
        System.out.println("  --download    Run provider download speed benchmark");
        System.out.println("  --match       Run matching engine stress test under load");
        System.out.println("  --help, -h    Show this help");
        System.out.println();
        System.out.println("If no options are given, both --download and --match are run.");
    }
}
