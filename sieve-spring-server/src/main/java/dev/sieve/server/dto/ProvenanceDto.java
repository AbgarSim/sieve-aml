package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Where and when one value of an entity was seen.
 *
 * @param kind the kind of value (NAME, BIRTH_DATE, IDENTIFIER and so on)
 * @param value the value, for example a name or {@code PASSPORT:123}
 * @param source the list that published it
 * @param sourceUrl the address it was read from, may be {@code null}
 * @param firstSeen when Sieve first saw it on the list
 * @param lastSeen when Sieve last saw it on the list
 */
@Schema(description = "Provenance of one value of an entity")
public record ProvenanceDto(
        @Schema(description = "Kind of value", example = "IDENTIFIER") String kind,
        @Schema(description = "The value", example = "PASSPORT:123456") String value,
        @Schema(description = "List that published the value", example = "OFAC_SDN") String source,
        @Schema(description = "Address the value was read from") String sourceUrl,
        @Schema(description = "When the value was first seen on the list") Instant firstSeen,
        @Schema(description = "When the value was last seen on the list") Instant lastSeen) {}
