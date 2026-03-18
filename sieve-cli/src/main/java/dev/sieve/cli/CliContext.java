package dev.sieve.cli;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.core.index.InMemoryEntityIndex;
import dev.sieve.core.match.MatchEngine;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.ListProvider;
import dev.sieve.ingest.ofac.OfacSdnProvider;
import dev.sieve.match.CompositeMatchEngine;
import dev.sieve.match.ExactMatchEngine;
import dev.sieve.match.FuzzyMatchEngine;
import java.util.List;

/**
 * Shared application context for CLI commands.
 *
 * <p>Provides lazily-initialized singletons for the entity index, match engine, providers, and
 * ingestion orchestrator. This avoids Spring dependency while keeping a single consistent state
 * across subcommands.
 */
public final class CliContext {

    private static final CliContext INSTANCE = new CliContext();

    private final EntityIndex entityIndex;
    private final MatchEngine matchEngine;
    private final List<ListProvider> providers;
    private final IngestionOrchestrator orchestrator;

    private CliContext() {
        this.entityIndex = new InMemoryEntityIndex();
        this.matchEngine =
                new CompositeMatchEngine(List.of(new ExactMatchEngine(), new FuzzyMatchEngine()));
        this.providers = List.of(new OfacSdnProvider());
        this.orchestrator = new IngestionOrchestrator(providers);
    }

    /**
     * Returns the singleton CLI context.
     *
     * @return the shared context instance
     */
    public static CliContext instance() {
        return INSTANCE;
    }

    /**
     * Returns the in-memory entity index.
     *
     * @return the entity index
     */
    public EntityIndex entityIndex() {
        return entityIndex;
    }

    /**
     * Returns the composite match engine.
     *
     * @return the match engine
     */
    public MatchEngine matchEngine() {
        return matchEngine;
    }

    /**
     * Returns the registered list providers.
     *
     * @return the providers
     */
    public List<ListProvider> providers() {
        return providers;
    }

    /**
     * Returns the ingestion orchestrator.
     *
     * @return the orchestrator
     */
    public IngestionOrchestrator orchestrator() {
        return orchestrator;
    }
}
