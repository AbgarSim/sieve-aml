package dev.sieve.server.schedule;

import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import dev.sieve.server.persistence.PersistentEntityIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Builds the screening index from PostgreSQL on startup, and runs a full import if the database is
 * still empty.
 *
 * <p>Listens for {@link ApplicationReadyEvent} so the database connection and schema are ready.
 * Entities stored by a previous run are served as soon as they are loaded; the scheduled refresh
 * keeps them current.
 */
@Component
public class StartupIngestionInitializer {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestionInitializer.class);

    private final IngestionOrchestrator orchestrator;
    private final PersistentEntityIndex entityIndex;

    public StartupIngestionInitializer(
            IngestionOrchestrator orchestrator, PersistentEntityIndex entityIndex) {
        this.orchestrator = orchestrator;
        this.entityIndex = entityIndex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        int loaded = entityIndex.load();
        if (loaded > 0) {
            log.info("Loaded stored entities, skipping startup import [entities={}]", loaded);
            return;
        }

        log.info("Database is empty, starting full import from all providers");
        try {
            IngestionReport report = orchestrator.ingest(entityIndex);
            log.info(
                    "Startup import complete [entities={}, duration={}ms]",
                    report.totalEntitiesLoaded(),
                    report.totalDuration().toMillis());
        } catch (Exception e) {
            log.error("Startup import failed", e);
        }
    }
}
