package dev.sieve.core;

/**
 * Thrown when a screening (matching) operation fails.
 *
 * <p>This may indicate an internal error in a match engine or an issue with the screening request
 * that was not caught by validation.
 */
public class ScreeningException extends SieveException {

    /**
     * Creates a new screening exception.
     *
     * @param message descriptive error message
     */
    public ScreeningException(String message) {
        super(message);
    }

    /**
     * Creates a new screening exception with a cause.
     *
     * @param message descriptive error message
     * @param cause the underlying cause
     */
    public ScreeningException(String message, Throwable cause) {
        super(message, cause);
    }
}
