package dev.sieve.core.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Audit emitter that logs screening events at INFO level via SLF4J.
 *
 * <p>Useful as a default implementation for development and testing, or as a baseline audit trail
 * when no dedicated persistence layer is configured.
 */
final class LoggingAuditEmitter implements ScreeningAuditEmitter {

    private static final Logger log = LoggerFactory.getLogger("dev.sieve.audit");

    @Override
    public void emit(ScreeningAuditEvent event) {
        log.info(
                "SCREENING_AUDIT [eventId={}, name={}, outcome={}, matches={}, threshold={}, durationMs={}, listVersion={}]",
                event.eventId(),
                event.screenedName(),
                event.outcome(),
                event.matchCount(),
                event.threshold(),
                event.durationMs(),
                event.listVersionId());
    }
}
