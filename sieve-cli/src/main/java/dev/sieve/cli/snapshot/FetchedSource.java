package dev.sieve.cli.snapshot;

import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import dev.sieve.ingest.ListMetadata;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of fetching one list for a snapshot.
 *
 * @param source the list
 * @param status what happened
 * @param entities the entities fetched, empty unless {@link Status#LOADED}
 * @param metadata the provider's metadata after the fetch, if any
 * @param duration time spent fetching and parsing
 * @param error the failure message when {@link Status#FAILED}
 */
public record FetchedSource(
        ListSource source,
        Status status,
        List<SanctionedEntity> entities,
        Optional<ListMetadata> metadata,
        Duration duration,
        Optional<String> error) {

    /** Fetch outcome of one list. */
    public enum Status {
        /** Fetched with at least one entity. */
        LOADED,
        /** Fetched, but the list had no entities. */
        EMPTY,
        /** The fetch or parse failed. */
        FAILED,
        /** Not fetched because a required API key is missing. */
        NEEDS_KEY,
        /** Not fetched because it was excluded by the caller. */
        SKIPPED
    }

    public FetchedSource {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(duration, "duration must not be null");
        entities = entities == null ? List.of() : List.copyOf(entities);
        metadata = metadata == null ? Optional.empty() : metadata;
        error = error == null ? Optional.empty() : error;
    }
}
