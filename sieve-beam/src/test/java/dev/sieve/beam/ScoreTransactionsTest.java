package dev.sieve.beam;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sieve.core.model.ListSource;
import dev.sieve.risk.RiskCsv;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.metrics.MetricNameFilter;
import org.apache.beam.sdk.metrics.MetricQueryResults;
import org.apache.beam.sdk.metrics.MetricsFilter;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.PCollection;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ScoreTransactionsTest {

    private static final List<String> ROWS =
            List.of(
                    RiskCsv.TRANSACTION_HEADER,
                    // acct-1: ordinary spending
                    "t1,acct-1,Grocer,US,84.12,USD,2026-10-01T09:00:00Z,out",
                    "t2,acct-1,Employer Inc,US,3120.55,USD,2026-10-02T09:00:00Z,in",
                    // acct-2: three cash deposits just under 10,000 in two days
                    "t3,acct-2,Cash,,9500,USD,2026-10-01T09:00:00Z,in",
                    "t4,acct-2,Cash,,9800.50,USD,2026-10-02T09:00:00Z,in",
                    "t5,acct-2,Cash,,9900,USD,2026-10-03T09:00:00Z,in",
                    // acct-3: pays a listed bank in a call-for-action country
                    "t6,acct-3,Banco Nacional de Cuba,IR,730.25,USD,2026-10-01T09:00:00Z,out",
                    "not,a,valid,row");

    @Rule public final transient TestPipeline pipeline = TestPipeline.create();

    @Rule public final transient TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void shouldScoreEachSubjectWhenRowsArriveUnsorted() {
        PCollection<String> assessments =
                pipeline.apply(Create.of(ROWS))
                        .apply(
                                new ScoreTransactions(
                                        new ListedNamesScorerFactory(
                                                List.of("BANCO NACIONAL DE CUBA"),
                                                ListSource.OFAC_SDN,
                                                0.85)));

        PAssert.that(assessments)
                .containsInAnyOrder(
                        "acct-1,0,LOW,2,,",
                        "acct-2,35,MEDIUM,3,structuring,"
                                + "structuring: 3 amounts from 9000 to just under 10000 within 7"
                                + " days",
                        "acct-3,90,HIGH,1,listed-counterparty;high-risk-jurisdiction,"
                                + "listed-counterparty: Banco Nacional de Cuba matches BANCO"
                                + " NACIONAL DE CUBA on OFAC_SDN (1.00);"
                                + " high-risk-jurisdiction: 1 transaction(s) with counterparties"
                                + " in IR");

        PipelineResult result = pipeline.run();
        result.waitUntilFinish();

        MetricQueryResults metrics =
                result.metrics()
                        .queryMetrics(
                                MetricsFilter.builder()
                                        .addNameFilter(
                                                MetricNameFilter.named(
                                                        ScoreTransactions.class, "malformedRows"))
                                        .build());
        assertThat(metrics.getCounters())
                .singleElement()
                .satisfies(counter -> assertThat(counter.getCommitted()).isEqualTo(1L));
    }

    @Test
    public void shouldWriteAssessmentsFileWhenRunFromOptions() throws Exception {
        File input = folder.newFile("transactions.csv");
        Files.write(input.toPath(), ROWS, StandardCharsets.UTF_8);
        File names = folder.newFile("names.txt");
        Files.write(names.toPath(), List.of("BANCO NACIONAL DE CUBA", ""), StandardCharsets.UTF_8);
        String output = new File(folder.getRoot(), "assessments").getPath();

        TransactionRiskPipeline.Options options =
                PipelineOptionsFactory.fromArgs(
                                "--input=" + input.getPath(),
                                "--output=" + output,
                                "--listedNames=" + names.getPath())
                        .as(TransactionRiskPipeline.Options.class);
        TransactionRiskPipeline.build(pipeline, options);
        pipeline.run().waitUntilFinish();

        List<String> lines =
                Files.readAllLines(new File(output + ".csv").toPath(), StandardCharsets.UTF_8);
        assertThat(lines.get(0)).isEqualTo(RiskCsv.ASSESSMENT_HEADER);
        assertThat(lines.subList(1, lines.size()))
                .hasSize(3)
                .anySatisfy(line -> assertThat(line).startsWith("acct-3,90,HIGH,1,"));
    }
}
