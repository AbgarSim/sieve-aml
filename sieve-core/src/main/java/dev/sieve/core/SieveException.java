package dev.sieve.core;

/**
 * Root exception for all Sieve-specific errors.
 *
 * <p>All domain exceptions in the Sieve platform extend this class, enabling callers to catch a
 * single type for broad error handling while still allowing fine-grained catching of specific
 * subtypes.
 */
public class SieveException extends RuntimeException {

    /**
     * Creates a new exception with the given message.
     *
     * @param message descriptive error message
     */
    public SieveException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with the given message and cause.
     *
     * @param message descriptive error message
     * @param cause the underlying cause
     */
    public SieveException(String message, Throwable cause) {
        super(message, cause);
    }
}
