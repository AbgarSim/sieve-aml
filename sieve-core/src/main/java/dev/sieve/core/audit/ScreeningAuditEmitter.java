package dev.sieve.core.audit;

/**
 * Service Provider Interface for emitting screening audit events.
 *
 * <p>Implementations decide how and where audit events are persisted or forwarded — e.g., to a
 * database, a message queue (Kafka), a log file, or an external compliance system. The screening
 * engine invokes this after every screening operation, including no-match outcomes.
 *
 * <p>A no-op implementation ({@link #noop()}) is provided for environments where audit logging is
 * not required.
 */
public interface ScreeningAuditEmitter {

    /**
     * Emits a screening audit event.
     *
     * <p>Implementations should be non-blocking where possible. If the event cannot be emitted
     * (e.g., queue full), the implementation should log a warning but <em>not</em> throw — audit
     * emission must never block or fail the screening hot path.
     *
     * @param event the audit event to emit, never {@code null}
     */
    void emit(ScreeningAuditEvent event);

    /**
     * Returns a no-op emitter that silently discards all events.
     *
     * @return a no-op audit emitter
     */
    static ScreeningAuditEmitter noop() {
        return event -> {};
    }

    /**
     * Returns an emitter that logs events at INFO level via SLF4J.
     *
     * @return a logging audit emitter
     */
    static ScreeningAuditEmitter logging() {
        return new LoggingAuditEmitter();
    }
}
