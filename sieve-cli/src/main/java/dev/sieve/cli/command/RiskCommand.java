package dev.sieve.cli.command;

import dev.sieve.cli.CliContext;
import dev.sieve.core.index.EntityIndex;
import dev.sieve.risk.BehaviouralRiskScorer;
import dev.sieve.risk.CounterpartyScreener;
import dev.sieve.risk.MatchEngineScreener;
import dev.sieve.risk.RiskAssessment;
import dev.sieve.risk.RiskConfig;
import dev.sieve.risk.RiskCsv;
import dev.sieve.risk.RiskLevel;
import dev.sieve.risk.RiskSignal;
import dev.sieve.risk.Transaction;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * CLI command that scores the behaviour of each subject in a transactions CSV.
 *
 * <p>Exit codes: 0 = every subject LOW, 1 = a subject MEDIUM or HIGH, 2 = error, so that the
 * command can gate a pipeline the way {@code screen} does.
 */
@Command(
        name = "risk",
        mixinStandardHelpOptions = true,
        description = "Score the behavioural risk of each subject in a transactions CSV")
public class RiskCommand implements Callable<Integer> {

    private static final Ansi ANSI = Ansi.AUTO;

    @Parameters(
            index = "0",
            description = "Transactions CSV with the columns " + RiskCsv.TRANSACTION_HEADER)
    private Path file;

    @Option(
            names = {"--threshold", "-t"},
            description = "Lowest match score that counts a counterparty as listed (default: 0.85)",
            defaultValue = "0.85")
    private double threshold;

    @Option(
            names = "--no-screening",
            description = "Score behaviour only, without fetching lists to screen counterparties")
    private boolean noScreening;

    @Option(names = "--csv", description = "Print one CSV row per subject instead of a table")
    private boolean csv;

    /** Scores the file and returns the exit code. */
    @Override
    public Integer call() throws IOException {
        List<Transaction> transactions;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            transactions = RiskCsv.readTransactions(reader);
        }

        CounterpartyScreener screener = CounterpartyScreener.none();
        if (!noScreening) {
            CliContext ctx = CliContext.instance();
            EntityIndex index = ctx.entityIndex();
            if (index.size() == 0) {
                System.err.println("Index is empty — fetching lists to screen counterparties...");
                ctx.orchestrator().ingest(index);
            }
            screener = new MatchEngineScreener(ctx.matchEngine(), index, threshold);
        }

        List<RiskAssessment> assessments =
                BehaviouralRiskScorer.withDefaultRules(RiskConfig.defaults(), screener)
                        .assessAll(transactions);

        if (csv) {
            System.out.println(RiskCsv.ASSESSMENT_HEADER);
            assessments.forEach(a -> System.out.println(RiskCsv.formatAssessment(a)));
        } else {
            printTable(assessments);
        }
        boolean flagged = assessments.stream().anyMatch(a -> a.level() != RiskLevel.LOW);
        return flagged ? 1 : ExitCode.OK;
    }

    private static void printTable(List<RiskAssessment> assessments) {
        System.out.printf(
                "  %-24s  %5s  %-6s  %12s%n", "Subject", "Score", "Level", "Transactions");
        System.out.println("  " + "-".repeat(53));
        for (RiskAssessment a : assessments) {
            String colour =
                    switch (a.level()) {
                        case HIGH -> "red";
                        case MEDIUM -> "yellow";
                        case LOW -> "green";
                    };
            System.out.println(
                    ANSI.string(
                            String.format(
                                    "  %-24s  %5d  @|bold,%s %-6s|@  %12d",
                                    a.subjectId(),
                                    a.score(),
                                    colour,
                                    a.level().name(),
                                    a.transactionCount())));
            for (RiskSignal signal : a.signals()) {
                System.out.printf(
                        "      +%-3d %s: %s%n", signal.points(), signal.rule(), signal.detail());
            }
        }
    }
}
