package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How one screened fact compared with a matched record.
 *
 * @param field the fact: dateOfBirth, placeOfBirth, country, address, identifier or gender
 * @param outcome MATCH, PARTIAL, MISMATCH or NOT_ON_RECORD
 * @param recordValue the record's value that decided the outcome; empty when the record has none
 */
@Schema(description = "How one screened fact compared with the matched record")
public record FieldMatchDto(
        @Schema(description = "Fact compared", example = "dateOfBirth") String field,
        @Schema(description = "MATCH, PARTIAL, MISMATCH or NOT_ON_RECORD", example = "MATCH")
                String outcome,
        @Schema(description = "The record's value", example = "1970-05-12") String recordValue) {}
