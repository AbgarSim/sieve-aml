package dev.sieve.server.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Inbound DTO for a batch screening request.
 *
 * @param requests the list of individual screening requests to process
 */
@Schema(description = "Batch request to screen multiple names against sanctions lists")
public record BatchScreeningRequestDto(
        @NotEmpty(message = "Requests must not be empty")
                @Size(max = 1000, message = "Batch size must not exceed 1000")
                @Valid
                @Schema(description = "List of screening requests")
                List<ScreeningRequestDto> requests) {}
