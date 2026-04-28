package dev.sieve.core;

import dev.sieve.core.model.ListSource;

/**
 * Thrown when fetching or parsing a sanctions list fails.
 *
 * <p>Carries structured context about which source failed and, optionally, which entity was being
 * processed when the error occurred.
 */
public class ListIngestionException extends SieveException {

    private final ListSource source;
    private final String entityId;

    /**
     * Creates a new ingestion exception for a source-level failure.
     *
     * @param message descriptive error message
     * @param source the sanctions list source that failed
     */
    public ListIngestionException(String message, ListSource source) {
        super(message);
        this.source = source;
        this.entityId = null;
    }

    /**
     * Creates a new ingestion exception for a source-level failure with a cause.
     *
     * @param message descriptive error message
     * @param source the sanctions list source that failed
     * @param cause the underlying cause
     */
    public ListIngestionException(String message, ListSource source, Throwable cause) {
        super(message, cause);
        this.source = source;
        this.entityId = null;
    }

    /**
     * Creates a new ingestion exception for an entity-level parse failure.
     *
     * @param message descriptive error message
     * @param source the sanctions list source
     * @param entityId the ID of the entity being parsed when the error occurred
     * @param cause the underlying cause
     */
    public ListIngestionException(
            String message, ListSource source, String entityId, Throwable cause) {
        super(message, cause);
        this.source = source;
        this.entityId = entityId;
    }

    /**
     * Returns the sanctions list source associated with this failure.
     *
     * @return the list source, may be {@code null} if not applicable
     */
    public ListSource getSource() {
        return source;
    }

    /**
     * Returns the entity ID that was being processed when the failure occurred.
     *
     * @return the entity ID, or {@code null} if the failure was at the source level
     */
    public String getEntityId() {
        return entityId;
    }
}
