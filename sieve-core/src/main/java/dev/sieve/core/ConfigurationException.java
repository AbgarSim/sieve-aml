package dev.sieve.core;

/**
 * Thrown when Sieve encounters an invalid or missing configuration.
 *
 * <p>This exception is typically raised during application startup when required configuration
 * properties are absent or have invalid values.
 */
public class ConfigurationException extends SieveException {

    /**
     * Creates a new configuration exception.
     *
     * @param message descriptive error message
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates a new configuration exception with a cause.
     *
     * @param message descriptive error message
     * @param cause the underlying cause
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
