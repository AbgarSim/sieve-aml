package dev.sieve.cli.command;

import dev.sieve.cli.CliContext;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.IndexStats;
import picocli.CommandLine.Command;

/**
 * CLI command to display index statistics.
 */
@Command(
        name = "stats",
        mixinStandardHelpOptions = true,
        description = "Show index statistics")
public class StatsCommand implements Runnable {

    @Override
    public void run() {
        CliContext ctx = CliContext.instance();
        EntityIndex index = ctx.entityIndex();
        IndexStats stats = index.stats();

        System.out.println("@|bold Sieve Index Statistics|@");
        System.out.println("========================");
        System.out.printf("Total entities: %d%n", stats.totalEntities());
        System.out.printf("Last updated:   %s%n", stats.lastUpdated());
        System.out.println();

        if (!stats.countBySource().isEmpty()) {
            System.out.println("@|bold By Source:|@");
            stats.countBySource()
                    .forEach(
                            (source, count) ->
                                    System.out.printf(
                                            "  %-20s %,d%n", source.displayName(), count));
            System.out.println();
        }

        if (!stats.countByType().isEmpty()) {
            System.out.println("@|bold By Type:|@");
            stats.countByType()
                    .forEach(
                            (type, count) ->
                                    System.out.printf(
                                            "  %-20s %,d%n", type.displayName(), count));
        }

        if (stats.totalEntities() == 0) {
            System.out.println();
            System.out.println(
                    "@|yellow Index is empty. Run 'sieve fetch' to load sanctions lists.|@");
        }
    }
}
