package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Outbound DTO for a paginated list of entities.
 *
 * @param entities the entities on this page
 * @param page current page number (zero-based)
 * @param size page size
 * @param totalElements total number of entities across all pages
 * @param totalPages total number of pages
 */
@Schema(description = "Paginated list of sanctioned entities")
public record EntityPageDto(
        @Schema(description = "Entities on this page") List<EntityDto> entities,
        @Schema(description = "Current page number (zero-based)", example = "0") int page,
        @Schema(description = "Page size", example = "20") int size,
        @Schema(description = "Total entities", example = "12543") long totalElements,
        @Schema(description = "Total pages", example = "628") int totalPages) {}
