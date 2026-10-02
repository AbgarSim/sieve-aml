package dev.sieve.ingest;

import dev.sieve.core.model.ListSource;
import java.net.URI;
import java.util.Objects;

/**
 * Static facts about a sanctions list: who publishes it, for which jurisdiction, and in what
 * format.
 *
 * @param source the list
 * @param authority the body that publishes the list
 * @param jurisdiction ISO 3166-1 alpha-2 code of the issuing country, or {@code EU} or {@code UN}
 * @param format the download format the provider parses
 * @param homepage the publisher's page for the list
 */
public record SourceInfo(
        ListSource source, String authority, String jurisdiction, Format format, URI homepage) {

    /** Download formats of the published lists. */
    public enum Format {
        XML,
        JSON,
        XLSX,
        CSV,
        HTML
    }

    public SourceInfo {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
        Objects.requireNonNull(jurisdiction, "jurisdiction must not be null");
        Objects.requireNonNull(format, "format must not be null");
        Objects.requireNonNull(homepage, "homepage must not be null");
    }
}
