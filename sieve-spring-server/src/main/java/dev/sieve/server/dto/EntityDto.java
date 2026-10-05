package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Outbound DTO for a sanctioned entity.
 *
 * @param id source-specific entity ID
 * @param entityType type classification (INDIVIDUAL, ENTITY, COMPANY, ORGANIZATION, VESSEL,
 *     AIRCRAFT, CRYPTO_WALLET, SECURITY)
 * @param listSource originating sanctions list
 * @param primaryName the entity's primary name
 * @param aliases list of alternative names
 * @param nationalities known nationalities
 * @param programs sanctions programs the entity is listed under
 * @param topics why the entity is of interest (SANCTION, PEP, DEBARMENT and so on)
 * @param gender the gender the list records (MALE, FEMALE, OTHER), {@code null} if none
 * @param deceased {@code true} when the list says the person is dead, {@code null} if it says
 *     nothing about it
 * @param remarks free-text remarks
 * @param listingReasons the reasons the list gives for the listing
 * @param vessel what the list says about a vessel, {@code null} for anything else
 * @param lastUpdated last modification timestamp
 * @param firstSeen when Sieve first saw any value of the entity, {@code null} if unknown
 * @param lastSeen when Sieve last saw the entity on its list, {@code null} if unknown
 * @param provenance where and when each value was seen
 * @param images pictures of the entity the list publishes
 * @param links pages about the entity, such as its listing page or the acts that listed it
 */
@Schema(description = "Sanctioned entity from a sanctions list")
public record EntityDto(
        @Schema(description = "Source-specific entity ID", example = "12345") String id,
        @Schema(description = "Entity type", example = "INDIVIDUAL") String entityType,
        @Schema(description = "Originating sanctions list", example = "OFAC_SDN") String listSource,
        @Schema(description = "Primary name", example = "DOE, John") String primaryName,
        @Schema(description = "Alternative names / aliases") List<String> aliases,
        @Schema(description = "Known addresses") List<AddressDto> addresses,
        @Schema(description = "Known nationalities") List<String> nationalities,
        @Schema(description = "Sanctions programs") List<String> programs,
        @Schema(description = "Risk topics", example = "[\"SANCTION\"]") List<String> topics,
        @Schema(description = "Gender the list records", example = "MALE") String gender,
        @Schema(description = "True when the list says the person is dead, absent otherwise")
                Boolean deceased,
        @Schema(description = "Remarks") String remarks,
        @Schema(description = "Reasons the list gives for the listing") List<String> listingReasons,
        @Schema(description = "What the list says about a vessel") VesselDto vessel,
        @Schema(description = "Last updated timestamp") Instant lastUpdated,
        @Schema(description = "When any value of the entity was first seen") Instant firstSeen,
        @Schema(description = "When the entity was last seen on its list") Instant lastSeen,
        @Schema(description = "Where and when each name, date, identifier and other value was seen")
                List<ProvenanceDto> provenance,
        @Schema(description = "Pictures of the entity, each with its credit and licence")
                List<ImageDto> images,
        @Schema(description = "Pages about the entity") List<LinkDto> links) {}
