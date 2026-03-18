package dev.sieve.cli.command;

import dev.sieve.cli.CliContext;
import dev.sieve.core.model.ListSource;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.ingest.ProviderResult;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI command to fetch sanctions lists and load them into the in-memory index.
 */
@Command(
        name = "fetch",
        mixinStandardHelpOptions = true,
        description = "Fetch sanctions lists and load into the index")
public class FetchCommand implements Runnable {

    @Option(
            names = {"--list", "-l"},
            description = "Specific list to fetch (e.g., ofac-sdn). Fetches all if omitted.")
    private String list;

    @Override
    public void run() {
        CliContext ctx = CliContext.instance();
        IngestionOrchestrator orchestrator = ctx.orchestrator();

        System.out.println("@|bold Fetching sanctions lists...|@");
        System.out.println();

        IngestionReport report;
        if (list != null) {
            ListSource source = ListSource.fromString(list);
            report = orchestrator.ingest(ctx.entityIndex(), Set.of(source));
        } else {
            report = orchestrator.ingest(ctx.entityIndex());
        }

        for (var entry : report.results().entrySet()) {
            ProviderResult result = entry.getValue();
            String icon =
                    switch (result.status()) {
                        case SUCCESS -> "@|green ✓|@";
                        case FAILED -> "@|red ✗|@";
                        case SKIPPED -> "@|yellow ⊘|@";
                    };
            System.out.printf(
                    "  %s %s — %s (%d entities, %dms)%n",
                    icon,
                    entry.getKey().displayName(),
                    result.status(),
                    result.entityCount(),
                    result.duration().toMillis());
            result.error().ifPresent(err -> System.out.printf("      Error: %s%n", err));
        }

        System.out.println();
        System.out.printf(
                "@|bold Total:|@ %d entities loaded in %dms%n",
                report.totalEntitiesLoaded(), report.totalDuration().toMillis());
    }
}
