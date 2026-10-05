package dev.sieve.cli.command;

import dev.sieve.cli.snapshot.FetchedSource;
import dev.sieve.cli.snapshot.SnapshotFetcher;
import dev.sieve.cli.snapshot.SnapshotWriter;
import dev.sieve.cli.snapshot.WikidataStep;
import dev.sieve.core.geo.CountryNormalizer;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.stats.DatasetStats;
import dev.sieve.ingest.ProviderRegistry;
import dev.sieve.ingest.wikidata.WikidataLinks;
import dev.sieve.match.dedup.SimilarityDeduplicator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "snapshot",
        mixinStandardHelpOptions = true,
        description = {
            "Fetch all lists and write dashboard data: aggregate JSON, entity shards and a search"
                    + " index, with the records of one entity matched across lists.",
            "Exit code 0 when at least one list loaded, 2 when none did."
        })
public class SnapshotCommand implements Callable<Integer> {

    private static final String NSDC_KEY_ENV = "SIEVE_NSDC_API_KEY";

    @Option(
            names = {"-o", "--out"},
            defaultValue = "snapshot",
            description = "Output directory (default: ${DEFAULT-VALUE})")
    private Path out;

    @Option(
            names = {"-s", "--source"},
            split = ",",
            description = "Only fetch these lists, e.g. OFAC_SDN,UN_CONSOLIDATED (default: all)")
    private List<String> sources = List.of();

    @Option(
            names = "--shard-size",
            defaultValue = "500",
            description = "Entity records per shard file (default: ${DEFAULT-VALUE})")
    private int shardSize;

    @Option(
            names = "--no-wikidata",
            description =
                    "Skip looking up pictures and Wikipedia articles on Wikidata for records it"
                            + " knows by LEI, IMO number, BIC or ISIN")
    private boolean noWikidata;

    @Override
    public Integer call() throws Exception {
        Set<ListSource> only = EnumSet.noneOf(ListSource.class);
        sources.forEach(s -> only.add(ListSource.fromString(s)));

        String nsdcKey = System.getenv(NSDC_KEY_ENV);
        boolean nsdcKeyMissing = nsdcKey == null || nsdcKey.isBlank();
        SnapshotFetcher fetcher =
                new SnapshotFetcher(
                        ProviderRegistry.defaults(),
                        source -> source == ListSource.UA_NSDC && nsdcKeyMissing);
        List<FetchedSource> fetched = fetcher.fetch(only);
        if (!noWikidata) {
            fetched = WikidataStep.addTo(fetched, new WikidataLinks());
        }

        SnapshotWriter writer =
                new SnapshotWriter(
                        CountryNormalizer.standard(),
                        new SimilarityDeduplicator(),
                        shardSize,
                        Clock.systemUTC());
        DatasetStats stats =
                writer.write(fetched, out, Optional.ofNullable(System.getenv("GITHUB_SHA")));

        long loaded =
                fetched.stream().filter(f -> f.status() == FetchedSource.Status.LOADED).count();
        System.out.printf(
                "Snapshot written to %s: %,d entities from %d lists, %d countries%n",
                out.toAbsolutePath(), stats.totalEntities(), loaded, stats.byCountry().size());
        fetched.stream()
                .filter(f -> f.status() != FetchedSource.Status.LOADED)
                .forEach(
                        f ->
                                System.out.printf(
                                        "  %-20s %s%s%n",
                                        f.source().name(),
                                        f.status(),
                                        f.error().map(e -> ": " + e).orElse("")));
        return loaded > 0 ? 0 : 2;
    }
}
