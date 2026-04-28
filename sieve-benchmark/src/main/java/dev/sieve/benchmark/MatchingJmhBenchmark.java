package dev.sieve.benchmark;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.core.match.MatchResult;
import dev.sieve.core.match.ScreeningRequest;
import dev.sieve.core.model.EntityType;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.NameInfo;
import dev.sieve.core.model.NameStrength;
import dev.sieve.core.model.NameType;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.core.model.SanctionsProgram;
import dev.sieve.core.model.ScriptType;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH microbenchmarks for the Sieve matching engine.
 *
 * <p>Uses synthetic entities to provide a reproducible benchmark independent of network
 * availability. Run via:
 *
 * <pre>
 *   java -jar sieve-benchmark.jar jmh
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
@State(Scope.Benchmark)
public class MatchingJmhBenchmark {

    @Param({"1000", "5000", "15000"})
    private int indexSize;

    private EntityIndex index;
    private MatchEngine exactEngine;
    private MatchEngine fuzzyEngine;
    private MatchEngine compositeEngine;
    private String[] queryNames;

    @Setup(Level.Trial)
    public void setup() {
        index = new InMemoryEntityIndex();
        List<SanctionedEntity> entities = generateEntities(indexSize);
        index.addAll(entities);

        exactEngine = new ExactMatchEngine();
        fuzzyEngine = new FuzzyMatchEngine();
        compositeEngine = new CompositeMatchEngine(List.of(exactEngine, fuzzyEngine));

        queryNames =
                new String[] {
                    "John Smith",
                    "Acme Corp",
                    "Vladimir Petrov",
                    "Ali Hassan",
                    "Samsung Electronics",
                    "Mohammed Ahmed",
                    "Jane Williams",
                    "Al-Rashid Trading",
                    "Kim Yong",
                    "Deutsche Bank"
                };
    }

    @Benchmark
    @Threads(1)
    public List<MatchResult> exactMatch_singleThread() {
        return exactEngine.screen(nextRequest(0.95), index);
    }

    @Benchmark
    @Threads(1)
    public List<MatchResult> fuzzyMatch_singleThread() {
        return fuzzyEngine.screen(nextRequest(0.80), index);
    }

    @Benchmark
    @Threads(1)
    public List<MatchResult> compositeMatch_singleThread() {
        return compositeEngine.screen(nextRequest(0.80), index);
    }

    @Benchmark
    @Threads(4)
    public List<MatchResult> compositeMatch_4threads() {
        return compositeEngine.screen(nextRequest(0.80), index);
    }

    @Benchmark
    @Threads(16)
    public List<MatchResult> compositeMatch_16threads() {
        return compositeEngine.screen(nextRequest(0.80), index);
    }

    // ---- Helpers -----------------------------------------------------------

    private int queryIndex = 0;

    private ScreeningRequest nextRequest(double threshold) {
        String name = queryNames[queryIndex++ % queryNames.length];
        return ScreeningRequest.of(name, threshold);
    }

    private static List<SanctionedEntity> generateEntities(int count) {
        String[] firstNames = {
            "Mohammed", "Ali", "Hassan", "Omar", "Ahmed", "Yusuf", "Ibrahim",
            "Vladimir", "Sergei", "Dmitry", "Kim", "Park", "John", "James",
            "Abdul", "Khalid", "Tariq", "Chen", "Wang", "Li"
        };
        String[] lastNames = {
            "Al-Rashid", "Hussein", "Bin Laden", "Petrov", "Ivanov", "Kuznetsov",
            "Jong-un", "Yong-chol", "Smith", "Johnson", "Williams", "Brown",
            "Al-Hassan", "Al-Tikriti", "Zhang", "Liu", "Kumar", "Singh"
        };
        String[] entityNames = {
            "Acme Trading",
            "Global Holdings",
            "Eastern Corp",
            "Northern Bank",
            "Pacific Shipping",
            "Continental Mining",
            "Desert Industries",
            "Al-Quds Foundation",
            "Red Star LLC",
            "Dragon Enterprises"
        };
        ListSource[] sources = ListSource.values();

        List<SanctionedEntity> entities = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean isIndividual = i % 3 != 0;
            String fullName;
            EntityType type;

            if (isIndividual) {
                String first = firstNames[i % firstNames.length];
                String last = lastNames[(i / firstNames.length) % lastNames.length];
                fullName = first + " " + last;
                type = EntityType.INDIVIDUAL;
            } else {
                fullName = entityNames[(i / 3) % entityNames.length] + " " + (i / 30 + 1);
                type = EntityType.ENTITY;
            }

            NameInfo primaryName =
                    new NameInfo(
                            fullName,
                            isIndividual ? fullName.split(" ")[0] : null,
                            isIndividual && fullName.contains(" ")
                                    ? fullName.substring(fullName.indexOf(' ') + 1)
                                    : null,
                            null,
                            null,
                            NameType.PRIMARY,
                            NameStrength.STRONG,
                            ScriptType.LATIN);

            List<NameInfo> aliases = new ArrayList<>();
            if (i % 5 == 0) {
                aliases.add(
                        new NameInfo(
                                "Alias of " + fullName,
                                null,
                                null,
                                null,
                                null,
                                NameType.AKA,
                                NameStrength.STRONG,
                                ScriptType.LATIN));
            }

            ListSource source = sources[i % sources.length];
            entities.add(
                    new SanctionedEntity(
                            "BENCH-" + i,
                            type,
                            source,
                            primaryName,
                            aliases,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            null,
                            List.of(new SanctionsProgram("TEST", "Test Program", source)),
                            Instant.now(),
                            Instant.now()));
        }
        return entities;
    }
}
