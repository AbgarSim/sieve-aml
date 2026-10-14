package dev.sieve.beam;

import dev.sieve.risk.BehaviouralRiskScorer;
import dev.sieve.risk.RiskCsv;
import dev.sieve.risk.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns transaction CSV rows into one assessment CSV row per subject.
 *
 * <p>Rows are keyed by subject, grouped, and each subject's transactions are scored by the
 * behavioural rules, which also screen every counterparty against the lists. The header and blank
 * lines are skipped; malformed rows are counted in the {@code malformedRows} metric and dropped, so
 * one bad row does not stop a batch.
 */
public final class ScoreTransactions extends PTransform<PCollection<String>, PCollection<String>> {

    private static final long serialVersionUID = 1L;

    private final ScorerFactory scorerFactory;

    /**
     * Creates the transform.
     *
     * @param scorerFactory builds the scorer on each worker
     */
    public ScoreTransactions(ScorerFactory scorerFactory) {
        this.scorerFactory =
                Objects.requireNonNull(scorerFactory, "scorerFactory must not be null");
    }

    @Override
    public PCollection<String> expand(PCollection<String> rows) {
        return rows.apply("KeyBySubject", ParDo.of(new KeyBySubjectFn()))
                .apply("GroupBySubject", GroupByKey.create())
                .apply("AssessSubject", ParDo.of(new AssessSubjectFn(scorerFactory)));
    }

    /** Parses a row and keys it by its subject, keeping the row itself as the value. */
    static final class KeyBySubjectFn extends DoFn<String, KV<String, String>> {

        private static final long serialVersionUID = 1L;
        private static final Logger log = LoggerFactory.getLogger(KeyBySubjectFn.class);

        private final Counter malformed = Metrics.counter(ScoreTransactions.class, "malformedRows");

        @ProcessElement
        public void processElement(@Element String row, OutputReceiver<KV<String, String>> out) {
            if (row.isBlank() || RiskCsv.isHeader(row)) {
                return;
            }
            try {
                out.output(KV.of(RiskCsv.parseTransaction(row).subjectId(), row));
            } catch (IllegalArgumentException e) {
                malformed.inc();
                log.warn("Skipped malformed transaction row [reason={}]", e.getMessage());
            }
        }
    }

    /** Scores one subject's grouped rows. */
    static final class AssessSubjectFn extends DoFn<KV<String, Iterable<String>>, String> {

        private static final long serialVersionUID = 1L;

        private final ScorerFactory scorerFactory;
        private transient BehaviouralRiskScorer scorer;

        AssessSubjectFn(ScorerFactory scorerFactory) {
            this.scorerFactory = scorerFactory;
        }

        @Setup
        public void setup() {
            scorer = scorerFactory.create();
        }

        @ProcessElement
        public void processElement(
                @Element KV<String, Iterable<String>> subject, OutputReceiver<String> out) {
            List<Transaction> transactions = new ArrayList<>();
            for (String row : subject.getValue()) {
                transactions.add(RiskCsv.parseTransaction(row));
            }
            out.output(RiskCsv.formatAssessment(scorer.assess(subject.getKey(), transactions)));
        }
    }
}
