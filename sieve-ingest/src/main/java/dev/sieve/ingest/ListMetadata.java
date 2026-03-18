package dev.sieve.ingest;

import dev.sieve.core.model.ListSource;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Metadata about a fetched sanctions list, used for delta detection and status reporting.
 *
 * @param source the sanctions list source
 * @param lastFetched when the list was last successfully fetched
 * @param etag the HTTP ETag header value from the last fetch, may be {@code null}
 * @param contentHash SHA-256 hex digest of the raw source file content
 * @param sourceUri the URI from which the list was fetched
 * @param entityCount number of entities parsed from the list
 */
public record ListMetadata(
        ListSource source,
        Instant lastFetched,
        String etag,
        String contentHash,
        URI sourceUri,
        int entityCount) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code source} or {@code sourceUri} is {@code null}
     */
    public ListMetadata {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(sourceUri, "sourceUri must not be null");
    }
}
