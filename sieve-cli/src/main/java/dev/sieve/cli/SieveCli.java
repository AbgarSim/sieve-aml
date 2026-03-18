package dev.sieve.cli;

import dev.sieve.cli.command.ExportCommand;
import dev.sieve.cli.command.FetchCommand;
import dev.sieve.cli.command.ScreenCommand;
import dev.sieve.cli.command.StatsCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for the Sieve command-line interface.
 *
 * <p>Provides subcommands for fetching sanctions lists, screening names, viewing statistics, and
 * exporting data. Does not depend on Spring Boot — runs as a standalone CLI application.
 */
@Command(
        name = "sieve",
        mixinStandardHelpOptions = true,
        version = "Sieve 0.1.0-SNAPSHOT",
        description = "Open-source sanctions screening platform",
        subcommands = {
            FetchCommand.class,
            ScreenCommand.class,
            StatsCommand.class,
            ExportCommand.class,
            CommandLine.HelpCommand.class
        })
public class SieveCli implements Runnable {

    /**
     * When invoked without a subcommand, prints usage help.
     */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    /**
     * CLI entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new SieveCli()).execute(args);
        System.exit(exitCode);
    }
}
