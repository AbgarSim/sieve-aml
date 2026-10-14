package dev.sieve.beam;

import dev.sieve.core.model.ListSource;
import dev.sieve.risk.RiskCsv;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;

/**
 * A batch pipeline that reads transactions, screens every counterparty and scores each subject's
 * behaviour, and writes one assessment per subject.
 *
 * <p>It runs on Beam's direct runner by default; {@code --runner} picks another runner when its
 * dependency is on the classpath. Example:
 *
 * <pre>
 * java -cp ... dev.sieve.beam.TransactionRiskPipeline \
 *     --input=transactions.csv --listedNames=names.txt --output=assessments
 * </pre>
 */
public final class TransactionRiskPipeline {

    private TransactionRiskPipeline() {}

    /** Command-line options of the pipeline. */
    public interface Options extends PipelineOptions {

        @Description("Transactions CSV file or glob, in the sieve risk format")
        @Validation.Required
        String getInput();

        void setInput(String value);

        @Description("Prefix of the assessments CSV output")
        @Validation.Required
        String getOutput();

        void setOutput(String value);

        @Description("File of listed names, one per line, counterparties are screened against")
        @Default.String("")
        String getListedNames();

        void setListedNames(String value);

        @Description("Lowest match score that counts a counterparty as listed")
        @Default.Double(0.85)
        double getThreshold();

        void setThreshold(double value);
    }

    /**
     * Adds the pipeline's steps.
     *
     * @param pipeline the pipeline to add to
     * @param options where to read and write, and what to screen against
     * @return the pipeline
     */
    public static Pipeline build(Pipeline pipeline, Options options) {
        List<String> names = readNames(options.getListedNames());
        pipeline.apply("ReadTransactions", TextIO.read().from(options.getInput()))
                .apply(
                        "ScoreTransactions",
                        new ScoreTransactions(
                                new ListedNamesScorerFactory(
                                        names, ListSource.OFAC_SDN, options.getThreshold())))
                .apply(
                        "WriteAssessments",
                        TextIO.write()
                                .to(options.getOutput())
                                .withHeader(RiskCsv.ASSESSMENT_HEADER)
                                .withSuffix(".csv")
                                .withoutSharding());
        return pipeline;
    }

    /**
     * Runs the pipeline and waits for it to finish.
     *
     * @param args pipeline options
     */
    public static void main(String[] args) {
        Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
        build(Pipeline.create(options), options).run().waitUntilFinish();
    }

    private static List<String> readNames(String file) {
        if (file == null || file.isBlank()) {
            return List.of();
        }
        try {
            return Files.readAllLines(Path.of(file), StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read listed names from " + file, e);
        }
    }
}
