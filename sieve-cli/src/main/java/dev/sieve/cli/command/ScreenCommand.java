package dev.sieve.cli.command;

import dev.sieve.cli.CliContext;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.ListSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI command to screen a name against loaded sanctions lists.
 *
 * <p>Exit codes: 0 = no match, 1 = match found, 2 = error. This makes the command suitable for
 * CI/CD pipeline integration.
 */
@Command(
        name = "screen",
        mixinStandardHelpOptions = true,
        description = "Screen a name against sanctions lists")
public class ScreenCommand implements Runnable {

    @Parameters(index = "0", description = "Name to screen")
    private String name;

    @Option(
            names = {"--threshold", "-t"},
            description = "Minimum match score (0.0-1.0, default: 0.80)",
            defaultValue = "0.80")
    private double threshold;

    @Option(
            names = {"--list", "-l"},
            description = "Screen against a specific list (e.g., ofac-sdn)")
    private String list;

    @Option(
            names = {"--max-results", "-n"},
            description = "Maximum results to display (default: 20)",
            defaultValue = "20")
    private int maxResults;

    private int exitCode = ExitCode.OK;

    @Override
    public void run() {
        CliContext ctx = CliContext.instance();
        EntityIndex index = ctx.entityIndex();

        if (index.size() == 0) {
            System.out.println("Index is empty — fetching lists first...");
            System.out.println();
            ctx.orchestrator().ingest(index);
            System.out.println();
        }

        Optional<Set<ListSource>> sources =
                list != null
                        ? Optional.of(Set.of(ListSource.fromString(list)))
                        : Optional.empty();

        ScreeningRequest request =
                new ScreeningRequest(name, Optional.empty(), sources, threshold);

        MatchEngine engine = ctx.matchEngine();
        List<MatchResult> results = engine.screen(request, index);

        if (results.isEmpty()) {
            System.out.printf("@|green No matches found|@ for \"%s\" (threshold=%.2f)%n", name, threshold);
            exitCode = ExitCode.OK;
            return;
        }

        exitCode = 1;
        System.out.printf(
                "@|bold,yellow %d match(es) found|@ for \"%s\" (threshold=%.2f)%n%n",
                results.size(), name, threshold);

        printResultsTable(results.stream().limit(maxResults).toList());
    }

    private void printResultsTable(List<MatchResult> results) {
        String header =
                String.format(
                        "  %-6s  %-40s  %-15s  %-12s  %-15s",
                        "Score", "Name", "Source", "Type", "Algorithm");
        System.out.println(header);
        System.out.println("  " + "-".repeat(header.length() - 2));

        for (MatchResult result : results) {
            System.out.printf(
                    "  @|bold %.4f|@  %-40s  %-15s  %-12s  %-15s%n",
                    result.score(),
                    truncate(result.entity().primaryName().fullName(), 40),
                    result.entity().listSource().name(),
                    result.entity().entityType().name(),
                    result.matchAlgorithm());
        }
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen - 3) + "...";
    }

    /**
     * Returns the exit code for this command.
     *
     * @return 0 for no match, 1 for match found
     */
    public int getExitCode() {
        return exitCode;
    }
}
