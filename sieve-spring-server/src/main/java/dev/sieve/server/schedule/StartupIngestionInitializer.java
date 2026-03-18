package dev.sieve.server.schedule;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Performs a full sanctions list import on startup if the entity index is empty.
 *
 * <p>Listens for {@link ApplicationReadyEvent} to ensure all beans (including the database
 * connection for PostgreSQL mode) are fully initialized before checking the index. If the index
 * already contains entities (e.g. persisted in PostgreSQL from a previous run), the import is
 * skipped.
 */
@Component
public class StartupIngestionInitializer {

    private static final Logger log = LoggerFactory.getLogger(StartupIngestionInitializer.class);

    private final IngestionOrchestrator orchestrator;
    private final EntityIndex entityIndex;

    public StartupIngestionInitializer(
            IngestionOrchestrator orchestrator, EntityIndex entityIndex) {
        this.orchestrator = orchestrator;
        this.entityIndex = entityIndex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        int currentSize = entityIndex.size();
        if (currentSize > 0) {
            log.info(
                    "Entity index already contains {} entities — skipping startup import",
                    currentSize);
            return;
        }

        log.info("Entity index is empty — starting full import from all providers");
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
