package dev.sieve.ingest;

import dev.sieve.core.model.ListSource;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a single {@link ListProvider} fetch operation within an ingestion cycle.
 *
 * @param source the list source that was fetched
 * @param status whether the fetch succeeded, failed, or was skipped
 * @param entityCount number of entities parsed (zero if failed or skipped)
 * @param removedCount number of entities removed because the source no longer lists them
 * @param duration time taken for this provider's fetch
 * @param error the error if the fetch failed, empty otherwise
 */
public record ProviderResult(
        ListSource source,
        Status status,
        int entityCount,
        int removedCount,
        Duration duration,
        Optional<String> error) {

    /** Outcome status for a provider fetch. */
    public enum Status {
        /** Fetch and parse completed successfully. */
        SUCCESS,
        /** Fetch or parse failed with an error. */
        FAILED,
        /** Provider was skipped (e.g., not selected for this run). */
        SKIPPED
    }

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if any required parameter is {@code null}
     */
    public ProviderResult {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        Objects.requireNonNull(error, "error must not be null; use Optional.empty()");
    }

    /**
     * Creates a successful result.
     *
     * @param source the list source
     * @param entityCount number of entities loaded
     * @param duration time taken
     * @return a success result
     */
    public static ProviderResult success(ListSource source, int entityCount, Duration duration) {
        return success(source, entityCount, 0, duration);
    }

    /**
     * Creates a successful result for a refresh that removed delisted entities.
     *
     * @param source the list source
     * @param entityCount number of entities loaded
     * @param removedCount number of entities removed because the source no longer lists them
     * @param duration time taken
     * @return a success result
     */
    public static ProviderResult success(
            ListSource source, int entityCount, int removedCount, Duration duration) {
        return new ProviderResult(
                source, Status.SUCCESS, entityCount, removedCount, duration, Optional.empty());
    }

    /**
     * Creates a failed result.
     *
     * @param source the list source
     * @param duration time taken before failure
     * @param errorMessage description of the failure
     * @return a failure result
     */
    public static ProviderResult failed(ListSource source, Duration duration, String errorMessage) {
        return new ProviderResult(source, Status.FAILED, 0, 0, duration, Optional.of(errorMessage));
    }

    /**
     * Creates a skipped result.
     *
     * @param source the list source
     * @return a skipped result with zero duration
     */
    public static ProviderResult skipped(ListSource source) {
        return new ProviderResult(source, Status.SKIPPED, 0, 0, Duration.ZERO, Optional.empty());
    }
}
