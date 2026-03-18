package dev.sieve.server.schedule;

import dev.sieve.core.index.EntityIndex;
import dev.sieve.ingest.IngestionOrchestrator;
import dev.sieve.ingest.IngestionReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that periodically refreshes sanctions lists.
 *
 * <p>The cron expression is configurable via {@code sieve.lists.ofac-sdn.refresh-cron} in {@code
 * application.yml}. Defaults to daily at 2:00 AM.
 */
@Component
public class ListRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ListRefreshScheduler.class);

    private final IngestionOrchestrator orchestrator;
    private final EntityIndex entityIndex;

    /**
     * Creates a new list refresh scheduler.
     *
     * @param orchestrator the ingestion orchestrator
     * @param entityIndex the entity index to populate
     */
    public ListRefreshScheduler(IngestionOrchestrator orchestrator, EntityIndex entityIndex) {
        this.orchestrator = orchestrator;
        this.entityIndex = entityIndex;
    }

    /** Executes the scheduled list refresh. */
    @Scheduled(cron = "${sieve.lists.ofac-sdn.refresh-cron:0 0 2 * * *}")
    public void refreshLists() {
        log.info("Scheduled list refresh starting");
        try {
            IngestionReport report = orchestrator.ingest(entityIndex);
            log.info(
                    "Scheduled list refresh complete [entities={}, duration={}ms]",
                    report.totalEntitiesLoaded(),
                    report.totalDuration().toMillis());
        } catch (Exception e) {
            log.error("Scheduled list refresh failed", e);
        }
    }
}
