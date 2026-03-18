package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Outbound DTO wrapping a list of sanctions list statuses.
 *
 * @param lists the status of each known list source
 */
@Schema(description = "Response containing status of all sanctions lists")
public record ListsResponseDto(
        @Schema(description = "Status of each sanctions list source") List<ListStatusDto> lists) {}
