package dev.sieve.ingest;

import dev.sieve.core.ListIngestionException;
import dev.sieve.core.model.ListSource;
import dev.sieve.core.model.SanctionedEntity;
import java.util.List;

/**
 * Service Provider Interface for fetching and parsing a specific sanctions list.
 *
 * <p>Each implementation is responsible for a single {@link ListSource}: downloading the raw data,
 * parsing it into {@link SanctionedEntity} records, and tracking metadata for delta detection.
 */
public interface ListProvider {

    /**
     * Returns the sanctions list source this provider handles.
     *
     * @return the list source, never {@code null}
     */
    ListSource source();

    /**
     * Returns metadata from the most recent successful fetch.
     *
     * @return the metadata, never {@code null}
     */
    ListMetadata metadata();

    /**
     * Fetches and parses the sanctions list into normalized entities.
     *
     * @return the parsed entities, never {@code null}
     * @throws ListIngestionException if fetching or parsing fails
     */
    List<SanctionedEntity> fetch() throws ListIngestionException;

    /**
     * Checks whether the remote list has been updated since the given metadata snapshot.
     *
     * <p>Implementations should use lightweight mechanisms such as HTTP {@code If-None-Match}
     * (ETag) or {@code If-Modified-Since} headers to avoid downloading the full file.
     *
     * @param previousMetadata metadata from a prior fetch to compare against
     * @return {@code true} if the remote list has changed, {@code false} otherwise
     */
    boolean hasUpdates(ListMetadata previousMetadata);
}
